package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.api.seed.FixtureRows;
import com.funding.domain.ExternalApi;
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
 * T408: the funder-action scenarios of {@code seed-data.md}, through the real API, signals,
 * Temporal, WireMock and Postgres. Each case acts on its own clone of a generated scenario (own
 * application ID, token and generated stubs), so it never competes with {@code ScenarioE2EIT} for
 * the seeded rows. All clones are submitted up front so their slow phases (income retries, an
 * exhausted disbursement, a 20-second expiry) overlap.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunderActionsE2EIT extends FunctionalTestBase {

    private static final String FUNDER = "funder-it";
    private static final Duration EXPIRES_IN = Duration.ofSeconds(20);

    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private WorkflowClient client;
    @Value("${funding.env}") private String env;

    private MockControl mocks;
    private final Map<String, Long> versions = new HashMap<>();

    @BeforeAll
    void submitAll() throws Exception {
        mocks = new MockControl(env, FunctionalEnvironment.WIREMOCK_URL, config, gates.generate().fixtures());
        submit("LA-1006", "waive", null, null, false);
        submit("LA-1012", "satisfy", null, null, false);
        submit("LA-1027", "partial", null, null, false);
        submit("LA-1016", "rerun", null, null, false);
        submit("LA-1016", "approve", null, null, false);
        submit("LA-1001", "manualstip", ExternalApi.DISBURSEMENT, "EXHAUSTED", false);
        submit("LA-1030", "retry", null, null, false);
        submit("LA-1012", "expire", null, null, true);
        submit("LA-1030", "expire", null, null, true);
        submit("LA-1020", "reject", null, null, false);
        submit("LA-1001", "funded", null, null, false);
        submit("LA-1026", "riscwaive", null, null, false);
        submit("LA-1002", "tilawaive", null, null, false);
    }

    /** Clones the scenario, optionally switches one external to another generated intent, writes it as DRAFT and submits it. */
    private void submit(String scenario, String suffix, ExternalApi api, String intent, boolean expiresSoon) throws Exception {
        Fixture clone = mocks.cloneScenario(scenario, suffix.toUpperCase(java.util.Locale.ROOT));
        if (api != null) {
            mocks.swap(clone.scenarioId(), api, intent);
        }
        List<Long> ids = FixtureRows.insert(jdbc, gates, clone);
        long id = ids.get(clone.versions().indexOf(clone.latest()));
        if (expiresSoon) {
            // AS-8.6 counts from submitted_at, which the DB stamps only when empty: back-date it so the
            // P30D deadline falls 20 s after submit, without changing funding.workflow.expire-after.
            jdbc.sql("UPDATE application_versions SET submitted_at = now() - ?::interval + ?::interval WHERE application_version_id = ?")
                    .params("30 days", EXPIRES_IN.toSeconds() + " seconds", id).update();
        }
        HttpResponse<String> r = httpPost(base() + "/api/v1/dev/applications/" + clone.loanApplicationId() + "/submit", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        versions.put(scenario + "/" + suffix, id);
    }

    private long v(String key) {
        return versions.get(key);
    }

    // ------------------------------------------------------------------ HTTP helpers

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

    private long stipId(long versionId, String code) {
        return jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ? AND stip_code = ? ORDER BY stip_id LIMIT 1")
                .params(versionId, code).query(Long.class).single();
    }

    private HttpResponse<String> resolve(long versionId, String code, String status) throws Exception {
        return call("PATCH", "/api/v1/versions/" + versionId + "/stipulations/" + stipId(versionId, code),
                "{\"status\":\"" + status + "\",\"reason\":\"checked by " + FUNDER + "\"}");
    }

    private void awaitStatus(long versionId, String want, Duration timeout) throws InterruptedException {
        await(timeout, () -> want.equals(status(versionId)), "version " + versionId + " -> " + want);
    }

    private FundingState state(long versionId) {
        try {
            return client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent(env, versionId)).getState();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void awaitWaiting(long versionId, Duration timeout) throws InterruptedException {
        await(timeout, () -> {
            FundingState s = state(versionId);
            return s != null && "WAITING_FOR_FUNDER".equals(s.step());
        }, "version " + versionId + " waiting for a funder");
    }

    private int count(String sql, long versionId) {
        return jdbc.sql(sql).param(versionId).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ cases

    @Test
    void LA_1006_approveWithPendingStipIs409ThenWaiveFunds() throws Exception {
        long id = v("LA-1006/waive");
        awaitStatus(id, "STIPULATED", Duration.ofSeconds(30));
        expect(call("POST", "/api/v1/versions/" + id + "/approve", null), 409, "PENDING_STIPS");
        expect(resolve(id, "STIP_PRICE_MISMATCH", "WAIVED"), 200, null);
        awaitStatus(id, "FUNDED", Duration.ofSeconds(30));
        assertThat(jdbc.sql("SELECT resolved_at IS NOT NULL FROM stipulations WHERE application_version_id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
        JsonNode audit = JSON.readTree(call("GET", "/api/v1/versions/" + id + "/audit", null).body());
        assertThat(audit.findValuesAsText("action")).contains("WAIVED");
        assertThat(count("SELECT count(*) FROM disbursements WHERE application_version_id = ? AND status = 'COMPLETED'", id)).isEqualTo(1);
    }

    @Test
    void LA_1012_satisfyIncomeStipFunds() throws Exception {
        long id = v("LA-1012/satisfy");
        awaitStatus(id, "STIPULATED", Duration.ofSeconds(30));
        expect(resolve(id, "STIP_INCOME_VARIANCE_EXCEEDED", "SATISFIED"), 200, null);
        awaitStatus(id, "FUNDED", Duration.ofSeconds(30));
    }

    @Test
    void LA_1027_partialResolutionStaysStipulated() throws Exception {
        long id = v("LA-1027/partial");
        awaitStatus(id, "STIPULATED", Duration.ofSeconds(30));
        awaitWaiting(id, Duration.ofSeconds(30));
        expect(resolve(id, "STIP_VIN_MISMATCH", "WAIVED"), 200, null);
        expect(resolve(id, "STIP_INCOME_VARIANCE_EXCEEDED", "SATISFIED"), 200, null);
        Thread.sleep(3000);
        awaitWaiting(id, Duration.ofSeconds(30));
        assertThat(status(id)).isEqualTo("STIPULATED"); // the TILA stip is still PENDING
        // The third (TILA) stip is RISC-bound: only a corrected RISC version resolves it (AS-8.10, slice S5).
        expect(resolve(id, "STIP_TILA_MATH_DISCREPANCY", "SATISFIED"), 409, "RISC_STIP_NOT_WAIVABLE");
    }

    @Test
    void LA_1016_switchIncomeIntentThenRerunFunds() throws Exception {
        long id = v("LA-1016/rerun");
        await(Duration.ofSeconds(60), () -> "ERROR".equals(outcome(id, "INCOME")), "INCOME ERROR");
        awaitWaiting(id, Duration.ofSeconds(30));
        assertThat(status(id)).isEqualTo("PENDING_REVIEW");
        mocks.swap("LA-1016-RERUN", ExternalApi.INCOME_VERIFICATION, "ok-100k");
        HttpResponse<String> r = call("POST", "/api/v1/versions/" + id + "/rerun", null); // default: the checks in ERROR
        expect(r, 200, null);
        assertThat(JSON.readTree(r.body()).path("checks").toString()).isEqualTo("[\"INCOME\"]");
        awaitStatus(id, "FUNDED", Duration.ofSeconds(30));
        assertThat(jdbc.sql("SELECT attempt FROM validation_results WHERE application_version_id = ? AND validation_check = 'INCOME' AND is_current")
                .param(id).query(Integer.class).single()).isEqualTo(2);
        assertThat(outcome(id, "INCOME")).isEqualTo("PASS");
    }

    @Test
    void LA_1016_manualApproveOverridesError() throws Exception {
        long id = v("LA-1016/approve");
        await(Duration.ofSeconds(60), () -> "ERROR".equals(outcome(id, "INCOME")), "INCOME ERROR");
        awaitWaiting(id, Duration.ofSeconds(30));
        expect(call("POST", "/api/v1/versions/" + id + "/approve", null), 200, null);
        awaitStatus(id, "FUNDED", Duration.ofSeconds(30));
    }

    @Test
    void LA_1001_manualStipBeforeDisbursementGoesStipulated() throws Exception {
        long id = v("LA-1001/manualstip");
        await(Duration.ofSeconds(60), () -> {
            FundingState s = state(id);
            return s != null && s.disbursementFailed() && "WAITING_FOR_FUNDER".equals(s.step());
        }, "disbursement exhausted");
        assertThat(status(id)).isEqualTo("APPROVED");
        expect(call("POST", "/api/v1/versions/" + id + "/stipulations",
                "{\"code\":\"STIP_PROOF_OF_INCOME\",\"description\":\"Upload two recent pay stubs\"}"), 201, null);
        assertThat(status(id)).isEqualTo("STIPULATED");
        assertThat(count("SELECT count(*) FROM stipulations WHERE application_version_id = ? AND stip_code = 'STIP_PROOF_OF_INCOME' "
                + "AND validation_check IS NULL AND status = 'PENDING'", id)).isEqualTo(1); // a funder's stip has no check
    }

    @Test
    void LA_1030_switchDisbursementIntentThenRetryFunds() throws Exception {
        long id = v("LA-1030/retry");
        await(Duration.ofSeconds(60), () -> {
            FundingState s = state(id);
            return s != null && s.disbursementFailed();
        }, "disbursement exhausted");
        assertThat(status(id)).isEqualTo("APPROVED");
        mocks.swap("LA-1030-RETRY", ExternalApi.DISBURSEMENT, "SUCCESS");
        expect(call("POST", "/api/v1/versions/" + id + "/retry-disbursement", null), 200, null);
        awaitStatus(id, "FUNDED", Duration.ofSeconds(30));
    }

    @Test
    void LA_1012_expiresKeepsStipAndRefusesWrites() throws Exception {
        long id = v("LA-1012/expire");
        awaitStatus(id, "EXPIRED", EXPIRES_IN.plusSeconds(30));
        assertThat(jdbc.sql("SELECT status FROM stipulations WHERE application_version_id = ?").param(id).query(String.class).list())
                .containsExactly("PENDING"); // kept as history
        assertThat(JSON.readTree(call("GET", "/api/v1/versions/" + id + "/audit", null).body()).toString())
                .contains("EXPIRED_AFTER_P30D", "system:funding-" + env + "-" + id);
        expect(resolve(id, "STIP_INCOME_VARIANCE_EXCEEDED", "SATISFIED"), 409, "VERSION_EXPIRED");
        // AS-9.7: an expired version's documents are frozen too (a Buyer's Order v2 like LA-1032's correction).
        Fixture.Document bo = mocks.fixture("LA-1032").amendments().get(0);
        expect(call("POST", "/api/v1/dev/applications/LA-1012-EXPIRE/documents", JSON.writeValueAsString(bo)), 409, "DOCUMENTS_FROZEN");
    }

    @Test
    void LA_1030_expiresWhileDisbursementIsDown() throws Exception {
        long id = v("LA-1030/expire");
        awaitStatus(id, "EXPIRED", EXPIRES_IN.plusSeconds(45));
        assertThat(count("SELECT count(*) FROM disbursements WHERE application_version_id = ? AND status = 'COMPLETED'", id)).isZero();
    }

    @Test
    void LA_1020_rejectLeavesNoDisbursement() throws Exception {
        long id = v("LA-1020/reject");
        awaitStatus(id, "STIPULATED", Duration.ofSeconds(30));
        expect(call("POST", "/api/v1/versions/" + id + "/reject", "{\"reason\":\"lender mismatch, dealer withdrew\"}"), 200, null);
        assertThat(status(id)).isEqualTo("REJECTED");
        await(Duration.ofSeconds(30), () -> state(id) == null || "DONE".equals(state(id).step()), "workflow completes");
        assertThat(count("SELECT count(*) FROM disbursements WHERE application_version_id = ?", id)).isZero();
    }

    @Test
    void LA_1001_fundedRefusesWrites() throws Exception {
        long id = v("LA-1001/funded");
        awaitStatus(id, "FUNDED", Duration.ofSeconds(30));
        expect(call("POST", "/api/v1/versions/" + id + "/approve", null), 409, "VERSION_TERMINAL");
        expect(call("POST", "/api/v1/versions/" + id + "/stipulations", "{\"code\":\"STIP_PROOF_OF_INCOME\",\"description\":\"x\"}"),
                409, "VERSION_TERMINAL");
    }

    @Test
    void riscStipsAreNotWaivable() throws Exception {
        long missing = v("LA-1026/riscwaive");
        awaitStatus(missing, "STIPULATED", Duration.ofSeconds(30));
        long riscStip = jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ? AND description LIKE '%RISC.%' LIMIT 1")
                .param(missing).query(Long.class).single();
        expect(call("PATCH", "/api/v1/versions/" + missing + "/stipulations/" + riscStip, "{\"status\":\"WAIVED\",\"reason\":\"x\"}"),
                409, "RISC_STIP_NOT_WAIVABLE");
        long tila = v("LA-1002/tilawaive");
        awaitStatus(tila, "STIPULATED", Duration.ofSeconds(30));
        expect(resolve(tila, "STIP_TILA_MATH_DISCREPANCY", "WAIVED"), 409, "RISC_STIP_NOT_WAIVABLE");
    }

    @Test
    void reassignWritesAudit() throws Exception {
        long id = v("LA-1027/partial");
        awaitStatus(id, "STIPULATED", Duration.ofSeconds(30));
        expect(call("PUT", "/api/v1/versions/" + id + "/assignee", "{\"funderId\":\"funder-9\"}"), 200, null);
        assertThat(jdbc.sql("SELECT assigned_funder FROM application_versions WHERE application_version_id = ?").param(id)
                .query(String.class).single()).isEqualTo("funder-9");
        assertThat(call("GET", "/api/v1/versions/" + id + "/audit", null).body()).contains("ASSIGNEE_CHANGED", "funder-9");
    }

    private String outcome(long versionId, String check) {
        return jdbc.sql("SELECT outcome FROM validation_results WHERE application_version_id = ? AND validation_check = ? AND is_current")
                .params(versionId, check).query(String.class).optional().orElse(null);
    }

    @AfterAll
    void P_5_7_noUnmatchedRequests() throws Exception {
        assertThat(unmatchedForThisEnv()).isZero();
    }
}
