package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.ExternalApi;
import com.funding.domain.Outcome;
import com.funding.persistence.ApplicationWriter;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStub;
import com.funding.seed.mock.MockStubGenerator;
import com.funding.workflows.FundingCarry;
import com.funding.workflows.FundingInput;
import com.funding.workflows.FundingResult;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * T601: every Disbursement catalog entry (classes × amounts, plus each RETRY_THEN_OK depth) through
 * the real parent workflow's disbursement path: the real HTTP client, the production activity
 * timeout and retry policy, the AS-8.1 pre-check and {@code markFunded}. Each entry gets its own
 * APPROVED version whose loan id and RISC {@code amount_financed} are the entry's; the parent is
 * started in {@code CONTINUE} mode (as after continueAsNew) with every check PASS, so it goes
 * straight to paying. All entries run in parallel.
 *
 * <ul>
 *   <li>Paid classes end FUNDED with exactly one COMPLETED row. TIMEOUT_BUT_PROCESSED is retried
 *       after the timeout and the API's replay returns the same reference, so the dealer is paid once.</li>
 *   <li>KEY_CONFLICT, DEALER_INVALID and MALFORMED are not retried: one call, a FAILED row, an
 *       ERROR audit row, and the version stays APPROVED for a funder (C-8).</li>
 *   <li>EXHAUSTED is retried up to max-attempts and stays APPROVED with the outcome unknown (REQUESTED row).</li>
 * </ul>
 */
class DisbursementMatrixIT extends FunctionalTestBase {

    private static final Set<String> PAID = Set.of("SUCCESS", "IDEMPOTENT_REPLAY", "TIMEOUT_BUT_PROCESSED", "RETRY_THEN_OK");
    private static final Set<String> REFUSED = Set.of("KEY_CONFLICT", "DEALER_INVALID", "MALFORMED");

    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private WorkflowClient client;
    @Autowired private TaskQueues queues;
    @Value("${funding.env}") private String env;

    @Test
    void T601_everyCatalogEntryPaysOnceOrStaysApproved() throws Exception {
        MockBudget budget = MockBudget.from(config);
        List<MockStub> cases = MockSet.generate(env, budget, config.rules().incomeVarianceThreshold(),
                        gates.generate().fixtures(), MockSet.catalogs(SeedPaths.discover())).stream()
                .filter(s -> s.api() == ExternalApi.DISBURSEMENT && "catalog".equals(s.source())).toList();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(48);

        Map<MockStub, Long> versions = new LinkedHashMap<>();
        for (MockStub s : cases) {
            long id = approvedVersion(s.attributes().get("loanApplicationId"), s.attributes().get("magnitude"));
            versions.put(s, id);
            FundingWorkflow wf = client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(WorkflowIds.parent(env, id)).setTaskQueue(queues.orchestration()).build());
            Map<Check, CheckOutcome> passed = new EnumMap<>(Check.class);
            Map<Check, Integer> attempts = new EnumMap<>(Check.class);
            for (Check c : Check.values()) {
                passed.put(c, new CheckOutcome(c, Outcome.PASS, List.of(), List.of(), List.of(), null));
                attempts.put(c, 1);
            }
            long expiresAt = System.currentTimeMillis() + Duration.ofHours(1).toMillis();
            WorkflowClient.start(wf::run, new FundingInput(id, FundingInput.CONTINUE, queues,
                    new FundingCarry(passed, attempts, expiresAt, false, Set.of())));
        }

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<MockStub, Long> e : versions.entrySet()) {
            MockStub s = e.getKey();
            long id = e.getValue();
            String cls = s.band();
            int k = s.attributes().containsKey("k") ? Integer.parseInt(s.attributes().get("k")) : 0;
            if (PAID.contains(cls)) {
                String result = WorkflowStub.fromTyped(stub(id)).getResult(2, java.util.concurrent.TimeUnit.MINUTES, FundingResult.class).result();
                expectEq(mismatches, s, "result", "FUNDED", result);
                expectEq(mismatches, s, "row", "COMPLETED", row(id));
                int wantCalls = switch (cls) {
                    case "TIMEOUT_BUT_PROCESSED" -> 2; // timed out, retried with the same key, replayed
                    case "RETRY_THEN_OK" -> k + 1;
                    default -> 1;
                };
                expectEq(mismatches, s, "calls", wantCalls, calls(s));
            } else {
                await(Duration.ofMinutes(2), () -> {
                    FundingState st = state(id);
                    return st != null && st.disbursementFailed() && "WAITING_FOR_FUNDER".equals(st.step());
                }, s.id() + " waiting for a funder after a failed disbursement");
                expectEq(mismatches, s, "status", "APPROVED", status(id));
                if (REFUSED.contains(cls)) {
                    expectEq(mismatches, s, "calls", 1, calls(s)); // not retried
                    expectEq(mismatches, s, "row", "FAILED", row(id));
                    expectEq(mismatches, s, "error audit", 1, jdbc.sql("""
                            SELECT count(*) FROM audit_log WHERE entity_type = 'APPLICATION_VERSION' AND entity_id = ?
                               AND action = 'DISBURSEMENT_ERROR'""").param(Long.toString(id)).query(Integer.class).single());
                } else { // EXHAUSTED: outcome unknown, retried to the limit
                    expectEq(mismatches, s, "calls", budget.maxAttempts(), calls(s));
                    expectEq(mismatches, s, "row", "REQUESTED", row(id));
                }
                WorkflowStub.fromTyped(stub(id)).terminate("T601: case checked");
            }
            expectEq(mismatches, s, "rows", 1, jdbc.sql("SELECT count(*) FROM disbursements WHERE application_version_id = ?")
                    .param(id).query(Integer.class).single());
            if (!s.expected().equals(status(id))) {
                mismatches.add(s.id() + ": catalog expects " + s.expected() + ", version is " + status(id));
            }
        }
        assertThat(mismatches).as("catalog entries whose disbursement differs from the catalog").isEmpty();
        assertThat(unmatchedForThisEnv()).isZero(); // P-5.7
    }

    private static void expectEq(List<String> mismatches, MockStub s, String what, Object want, Object got) {
        if (!want.equals(got)) {
            mismatches.add(s.id() + ": " + what + " expected " + want + " but was " + got);
        }
    }

    /** A version that is APPROVED with no stips and a RISC carrying only {@code amount_financed}: all that disbursing reads. */
    private long approvedVersion(String loanApplicationId, String amount) {
        long id = new ApplicationWriter(jdbc).insert(loanApplicationId, List.of(new ApplicationWriter.NewVersion(1, true, "DRAFT",
                "Matrix Case", null, null, new BigDecimal("96000.00"), "4T1C11AK7RU100101", "Matrix Motors", "DLR-100",
                List.of(new ApplicationWriter.NewDocument("RISC", 1, true, loanApplicationId + "-risc-v1.pdf", 5, List.of(
                        new ApplicationWriter.NewSection("Itemization", 2, 2, List.of(new ApplicationWriter.NewField(
                                "amount_financed", amount, new BigDecimal("0.97"), List.of(1, 2, 3, 4), 2))))))))).get(0);
        // DRAFT -> APPROVED directly: no outbox trigger (only DRAFT -> PENDING_REVIEW writes one), no workflow from the trigger path.
        jdbc.sql("UPDATE application_versions SET version_status = 'APPROVED' WHERE application_version_id = ?").param(id).update();
        return id;
    }

    private FundingWorkflow stub(long versionId) {
        return client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent(env, versionId));
    }

    private FundingState state(long versionId) {
        try {
            return stub(versionId).getState();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String row(long versionId) {
        return jdbc.sql("SELECT status FROM disbursements WHERE application_version_id = ?").param(versionId)
                .query(String.class).optional().orElse("none");
    }

    /** Requests WireMock received for this entry's own request pattern. */
    private int calls(MockStub s) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(FunctionalEnvironment.WIREMOCK_URL + "/__admin/requests/count"))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(MockStubGenerator.request(s)))).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode n = JSON.readTree(r.body());
        return n.path("count").asInt();
    }
}
