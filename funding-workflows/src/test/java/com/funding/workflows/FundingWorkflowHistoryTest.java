package com.funding.workflows;


import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Records the histories that {@link FundingWorkflowReplayTest} replays (NFR-07). Run with
 * {@code -Drecord.histories=true} after a change guarded by {@code Workflow.getVersion}; it only
 * writes files that don't exist yet, so earlier slices' histories keep their original code's shape.
 */
class FundingWorkflowHistoryTest {

    private static final Path DIR = Path.of("src/test/resources/histories");
    private static final TaskQueues QUEUES = TaskQueues.forEnv("history");

    @Test
    @EnabledIfSystemProperty(named = "record.histories", matches = "true")
    void recordHistories() throws IOException {
        // Recorded per slice; older files stay so replay keeps covering in-flight runs from earlier code.
        // S3: INCOME runs (behind the income-check version marker). S2-LA-1006-income-enabled-stipulated.json was
        // recorded from the S2 commit with INCOME enabled in config, as production ran it: it proves the guard.
        record("S3-LA-1001-funded", false);
        record("S3-LA-1006-stipulated", true);
        // S4: signals (re-run, wake-ups, waive) ending FUNDED; and a run that expires (AS-8.6).
        recordS4("S4-LA-1006-signals-funded", (env, wf, acts) -> {
            wf.stipResolved();
            env.sleep(Duration.ofSeconds(5));
            acts.status = "PENDING_REVIEW";
            wf.rerunChecks(List.of(com.funding.domain.Check.PRICE)); // still broken: FAIL again, attempt 2
            env.sleep(Duration.ofSeconds(5));
            acts.pendingStips.remove("PRICE"); // waived
            wf.stipResolved();
            env.sleep(Duration.ofSeconds(5));
        });
        recordS4("S4-LA-1006-expired", (env, wf, acts) -> env.sleep(Duration.ofDays(31)));
        // S5: a new latest document re-runs every check and funds (AS-1.8); a newer version supersedes the run (AS-9.2).
        recordS4("S5-LA-1006-document-rerun-funded", (env, wf, acts) -> {
            acts.risc.put("cash_price", "31450.00"); // the corrected document
            acts.status = "PENDING_REVIEW";
            wf.documentsUpdated(101L);
            env.sleep(Duration.ofSeconds(10));
        });
        recordS4("S5-LA-1006-superseded", (env, wf, acts) -> {
            wf.supersede();
            env.sleep(Duration.ofSeconds(5));
        });
        // S5 review: a document arriving during the first validation re-runs before the status settles
        // (the rerun-before-settling marker). Baseline data, so the first round alone would have funded.
        recordDuringValidation("S5-LA-1001-document-during-validation");
    }

    private void recordDuringValidation(String name) throws IOException {
        if (Files.exists(DIR.resolve(name + ".json"))) {
            return;
        }
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities acts = new FakeActivities();
            acts.submittedAt = env.currentTimeMillis();
            acts.incomeGate = new java.util.concurrent.CountDownLatch(1);
            Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, acts, ActivityOptionsConfig.forTests());
            env.start();
            FundingWorkflow wf = env.getWorkflowClient().newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId("funding-history-" + name).setTaskQueue(QUEUES.orchestration()).build());
            WorkflowClient.start(wf::run, new FundingInput(1, FundingInput.VALIDATE, QUEUES));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!acts.calls.contains("verifyIncome") && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            acts.risc.put("cash_price", "31450.01"); // the new document breaks PRICE
            wf.documentsUpdated(201L);
            acts.incomeGate.countDown();
            env.sleep(Duration.ofSeconds(10));
            WorkflowExecutionHistory h = env.getWorkflowClient().fetchHistory(WorkflowStub.fromTyped(wf).getExecution().getWorkflowId());
            Files.writeString(DIR.resolve(name + ".json"), h.toJson(true));
        }
    }

    interface Script {
        void run(TestWorkflowEnvironment env, FundingWorkflow wf, FakeActivities acts);
    }

    /** Never overwrites: a history recorded from an earlier slice's code must stay as it was. */
    private void recordS4(String name, Script script) throws IOException {
        if (Files.exists(DIR.resolve(name + ".json"))) {
            return;
        }
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities acts = new FakeActivities();
            acts.risc.put("cash_price", "31450.01"); // PRICE fails
            acts.submittedAt = env.currentTimeMillis();
            Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, acts, ActivityOptionsConfig.forTests());
            env.start();
            FundingWorkflow wf = env.getWorkflowClient().newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId("funding-history-" + name).setTaskQueue(QUEUES.orchestration()).build());
            WorkflowClient.start(wf::run, new FundingInput(1, FundingInput.VALIDATE, QUEUES));
            env.sleep(Duration.ofSeconds(5));
            script.run(env, wf, acts);
            WorkflowExecutionHistory h = env.getWorkflowClient().fetchHistory(WorkflowStub.fromTyped(wf).getExecution().getWorkflowId());
            Files.writeString(DIR.resolve(name + ".json"), h.toJson(true));
        }
    }

    private void record(String name, boolean brokenPrice) throws IOException {
        if (Files.exists(DIR.resolve(name + ".json"))) {
            return;
        }
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities acts = new FakeActivities();
            if (brokenPrice) {
                acts.risc.put("cash_price", "31450.01"); // PRICE fails; TILA, VIN and GAP pass
            }
            Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, acts, ActivityOptionsConfig.forTests());
            env.start();
            FundingWorkflow wf = env.getWorkflowClient().newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId("funding-history-" + name).setTaskQueue(QUEUES.orchestration()).build());
            WorkflowClient.start(wf::run, new FundingInput(1, FundingInput.VALIDATE, QUEUES));
            env.sleep(Duration.ofSeconds(5));
            WorkflowExecutionHistory h = env.getWorkflowClient().fetchHistory(WorkflowStub.fromTyped(wf).getExecution().getWorkflowId());
            Files.writeString(DIR.resolve(name + ".json"), h.toJson(true));
        }
    }
}
