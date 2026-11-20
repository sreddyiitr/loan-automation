package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.api.seed.FixtureRows;
import com.funding.seed.Fixture;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.WorkflowIds;
import io.temporal.client.WorkflowClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * T508: resubmission and document re-runs (US-9, AS-1.8) through the real path: dev endpoints, DB
 * triggers, outbox, SQS, the Lambda handler, the start API, Temporal, WireMock and Postgres. Each
 * case acts on its own clone of a generated scenario; corrected documents are the intent's generated
 * {@code amend} documents, never typed here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersioningE2EIT extends FunctionalTestBase {

    private static final String FUNDER = "funder-it";

    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private WorkflowClient client;
    @Autowired private org.springframework.transaction.support.TransactionTemplate tx;
    @Value("${funding.env}") private String env;

    private MockControl mocks;
    private final Map<String, Fixture> clones = new HashMap<>();
    private final Map<String, List<Long>> ids = new HashMap<>();

    @BeforeAll
    void submitAll() throws Exception {
        mocks = new MockControl(env, FunctionalEnvironment.WIREMOCK_URL, config, gates.generate().fixtures());
        submit("LA-1032", "amend");
        submit("LA-1032", "supersede");
        submit("LA-1002", "riscv2");
        submit("LA-1023", "riscv2");
        submit("LA-1028", "resubmit");
        submit("LA-1001", "frozen");
        submit("LA-1030", "frozen");
    }

    private void submit(String scenario, String suffix) throws Exception {
        String key = scenario + "/" + suffix;
        Fixture clone = mocks.cloneScenario(scenario, suffix.toUpperCase(java.util.Locale.ROOT));
        List<Long> versionIds = tx.execute(s -> FixtureRows.insert(jdbc, gates, clone)); // earlier versions with their rows
        clones.put(key, clone);
        ids.put(key, versionIds);
        HttpResponse<String> r = httpPost(base() + "/api/v1/dev/applications/" + clone.loanApplicationId() + "/submit", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    /** The latest version as submitted. */
    private long v(String key) {
        Fixture f = clones.get(key);
        return ids.get(key).get(f.versions().indexOf(f.latest()));
    }

    private String app(String key) {
        return clones.get(key).loanApplicationId();
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json")
                .header("X-Funder-Id", FUNDER)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void expect(HttpResponse<String> r, int status, String reason) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        if (reason != null) {
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(JSON.readTree(r.body()).path("reason").asText()).isEqualTo(reason);
        }
    }

    /** POSTs the intent's generated corrected document(s) to the dev add-document endpoint. */
    private void addAmendedDocuments(String key) throws Exception {
        for (Fixture.Document d : clones.get(key).amendments()) {
            expect(call("POST", "/api/v1/dev/applications/" + app(key) + "/documents", JSON.writeValueAsString(d)), 201, null);
        }
    }

    private FundingState state(long versionId) {
        try {
            return client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent(env, versionId)).getState();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void awaitWaiting(long versionId) throws InterruptedException {
        await(Duration.ofSeconds(60), () -> {
            FundingState s = state(versionId);
            return s != null && "WAITING_FOR_FUNDER".equals(s.step());
        }, "version " + versionId + " waiting for a funder");
    }

    private void awaitStatus(long versionId, String want) throws InterruptedException {
        await(Duration.ofSeconds(60), () -> want.equals(status(versionId)), "version " + versionId + " -> " + want);
    }

    private List<String> stips(long versionId) {
        return jdbc.sql("SELECT stip_code || ':' || status || ':' || coalesce(resolved_by, '-') FROM stipulations WHERE application_version_id = ? ORDER BY stip_id")
                .param(versionId).query(String.class).list();
    }

    private int attempt(long versionId, String check) {
        return jdbc.sql("SELECT attempt FROM validation_results WHERE application_version_id = ? AND validation_check = ? AND is_current")
                .params(versionId, check).query(Integer.class).single();
    }

    private String system(long versionId) {
        return WorkflowIds.systemActor(WorkflowIds.parent(env, versionId));
    }

    // ------------------------------------------------------------------ cases

    /** LA-1032 (AS-1.8, AS-8.8): Buyer's Order v2 re-runs every check; the price stip is auto-SATISFIED; FUNDED. */
    @Test
    void LA_1032_documentFixReRunsEveryCheckAndFunds() throws Exception {
        long id = v("LA-1032/amend");
        awaitStatus(id, "STIPULATED");
        awaitWaiting(id);
        addAmendedDocuments("LA-1032/amend");
        awaitStatus(id, "FUNDED");
        assertThat(stips(id)).containsExactly("STIP_PRICE_MISMATCH:SATISFIED:" + system(id));
        for (String check : List.of("TILA", "PRICE", "VIN", "GAP", "INCOME")) {
            assertThat(attempt(id, check)).as(check).isEqualTo(2);
        }
        assertThat(jdbc.sql("""
                SELECT document_version_number FROM documents WHERE application_version_id = ? AND document_type = 'BUYERS_ORDER' AND is_latest_doc""")
                .param(id).query(Integer.class).single()).isEqualTo(2);
        assertThat(call("GET", "/api/v1/versions/" + id + "/audit", null).body()).contains("DOCUMENT_ADDED", "RERUN_REQUESTED");
    }

    /** LA-1002: a TILA stip can't be waived, but a corrected RISC v2 clears it. */
    @Test
    void LA_1002_riscV2ClearsTheTilaStip() throws Exception {
        long id = v("LA-1002/riscv2");
        awaitStatus(id, "STIPULATED");
        awaitWaiting(id);
        addAmendedDocuments("LA-1002/riscv2");
        awaitStatus(id, "FUNDED");
        assertThat(stips(id)).containsExactly("STIP_TILA_MATH_DISCREPANCY:SATISFIED:" + system(id));
    }

    /** LA-1023: the low-confidence RISC stip is not waivable; a re-scanned RISC v2 fixes it. */
    @Test
    void LA_1023_notWaivableThenFixedByRiscV2() throws Exception {
        long id = v("LA-1023/riscv2");
        awaitStatus(id, "STIPULATED");
        awaitWaiting(id);
        long stip = jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ?").param(id).query(Long.class).single();
        expect(call("PATCH", "/api/v1/versions/" + id + "/stipulations/" + stip, "{\"status\":\"WAIVED\",\"reason\":\"x\"}"),
                409, "RISC_STIP_NOT_WAIVABLE");
        addAmendedDocuments("LA-1023/riscv2");
        awaitStatus(id, "FUNDED");
    }

    /** LA-1028 (AS-9.1-9.3, AS-10.7): v2 funds; v1 stays STIPULATED with its stip as history and refuses writes. */
    @Test
    void LA_1028_resubmissionFundsV2AndV1StaysHistory() throws Exception {
        long v2 = v("LA-1028/resubmit");
        long v1 = ids.get("LA-1028/resubmit").get(0);
        awaitStatus(v2, "FUNDED");
        assertThat(status(v1)).isEqualTo("STIPULATED");
        assertThat(stips(v1)).containsExactly("STIP_PRICE_MISMATCH:PENDING:-");
        long stip = jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ?").param(v1).query(Long.class).single();
        expect(call("PATCH", "/api/v1/versions/" + v1 + "/stipulations/" + stip, "{\"status\":\"WAIVED\",\"reason\":\"x\"}"),
                409, "NOT_LATEST_VERSION");
        assertThat(JSON.readTree(call("GET", "/api/v1/applications/" + app("LA-1028/resubmit") + "/versions", null).body()).size())
                .isEqualTo(2);
    }

    /** AS-9.2 / AS-9.5: V2 is created and submitted while V1's run waits; V1's run completes SUPERSEDED, its status untouched. */
    @Test
    void AS_9_2_submittingV2SupersedesV1sRun() throws Exception {
        String key = "LA-1032/supersede";
        long v1 = v(key);
        awaitStatus(v1, "STIPULATED");
        awaitWaiting(v1);
        // The dealer resubmits: V2 = V1 with the corrected Buyer's Order (one transaction moves the latest flag, AS-9.1).
        String docs = JSON.writeValueAsString(Map.of("documents", clones.get(key).amendments()));
        HttpResponse<String> created = call("POST", "/api/v1/dev/applications/" + app(key) + "/versions", docs);
        expect(created, 201, null);
        long v2 = JSON.readTree(created.body()).path("applicationVersionId").asLong();
        expect(call("POST", "/api/v1/versions/" + v1 + "/approve", null), 409, "NOT_LATEST_VERSION"); // V1 is history now
        expect(httpPost(base() + "/api/v1/dev/applications/" + app(key) + "/submit", null), 200, null);

        awaitStatus(v2, "FUNDED");
        await(Duration.ofSeconds(30), () -> {
            FundingState s = state(v1);
            return s != null && "SUPERSEDED".equals(s.status());
        }, "V1's run completes SUPERSEDED");
        assertThat(status(v1)).isEqualTo("STIPULATED");
        assertThat(stips(v1)).containsExactly("STIP_PRICE_MISMATCH:PENDING:-");
        assertThat(jdbc.sql("SELECT count(*) FROM disbursements WHERE application_version_id = ?").param(v1).query(Integer.class).single())
                .isZero();
    }

    /** AS-9.7: documents freeze at APPROVED (held after a failed disbursement) and FUNDED. */
    @Test
    void AS_9_7_documentsAreFrozenOnceApprovedOrFunded() throws Exception {
        long funded = v("LA-1001/frozen");
        awaitStatus(funded, "FUNDED");
        String risc = JSON.writeValueAsString(clones.get("LA-1002/riscv2").amendments().get(0));
        expect(call("POST", "/api/v1/dev/applications/" + app("LA-1001/frozen") + "/documents", risc), 409, "DOCUMENTS_FROZEN");

        long held = v("LA-1030/frozen");
        await(Duration.ofSeconds(60), () -> {
            FundingState s = state(held);
            return s != null && s.disbursementFailed();
        }, "disbursement exhausted");
        assertThat(status(held)).isEqualTo("APPROVED");
        expect(call("POST", "/api/v1/dev/applications/" + app("LA-1030/frozen") + "/documents", risc), 409, "DOCUMENTS_FROZEN");
        assertThat(jdbc.sql("SELECT count(*) FROM documents WHERE application_version_id = ? AND document_type = 'RISC'")
                .param(held).query(Integer.class).single()).isEqualTo(1);
    }

    @AfterAll
    void P_5_7_noUnmatchedRequests() throws Exception {
        assertThat(unmatchedForThisEnv()).isZero();
    }
}
