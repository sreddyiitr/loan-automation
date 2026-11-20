package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T504: resubmission and document re-runs (US-9, AS-1.8) against in-memory activities. The DB-level
 * refusal of writes for a non-latest version (AS-9.3) is covered with real activities in
 * {@code FundingActivitiesDbTest}.
 */
class FundingWorkflowVersioningTest {

    private static final TaskQueues QUEUES = TaskQueues.forEnv("test");
    private TestWorkflowEnvironment env;
    private FakeActivities acts;
    private WorkflowClient client;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        acts = new FakeActivities();
        acts.submittedAt = env.currentTimeMillis();
        Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, acts, ActivityOptionsConfig.forTests());
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void stop() {
        if (acts.incomeGate != null) {
            acts.incomeGate.countDown();
        }
        env.close();
    }

    private FundingWorkflow startRun(long id) {
        FundingWorkflow wf = client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.parent("test", id)).setTaskQueue(QUEUES.orchestration()).build());
        WorkflowClient.start(wf::run, new FundingInput(id, FundingInput.VALIDATE, QUEUES));
        return wf;
    }

    private FundingState settle(FundingWorkflow wf) {
        env.sleep(Duration.ofSeconds(30));
        return wf.getState();
    }

    private static String result(FundingWorkflow wf) {
        return WorkflowStub.fromTyped(wf).getResult(FundingResult.class).result();
    }

    private long count(String call) {
        synchronized (acts.calls) {
            return acts.calls.stream().filter(call::equals).count();
        }
    }

    private static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private WorkflowExecutionStatus statusOf(String workflowId) {
        return client.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build()).build())
                .getWorkflowExecutionInfo().getStatus();
    }

    @Test
    void AS_9_2_supersedeCancelsChildrenAndKeepsHistory() {
        acts.incomeGate = new CountDownLatch(1); // the INCOME child is still calling the API when V2 arrives
        FundingWorkflow wf = startRun(1);
        until(() -> acts.recorded.keySet().containsAll(List.of(Check.TILA, Check.PRICE, Check.VIN, Check.GAP))
                && acts.calls.contains("verifyIncome"), "four checks recorded and INCOME in flight");

        wf.supersede();
        assertThat(result(wf)).isEqualTo("SUPERSEDED");
        // Partial results stay as history; nothing settles the status (AS-9.2: V1's status isn't changed further).
        assertThat(acts.recorded).containsOnlyKeys(Check.TILA, Check.PRICE, Check.VIN, Check.GAP);
        assertThat(count("applyOutcome")).isZero();
        assertThat(acts.status).isEqualTo("PENDING_REVIEW");
        assertThat(wf.getState().status()).isEqualTo("SUPERSEDED");
        // The INCOME child doesn't outlive its superseded parent.
        String incomeChild = WorkflowIds.child(WorkflowIds.parent("test", 1), Check.INCOME, 1);
        until(() -> statusOf(incomeChild) != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING, "INCOME child closed");
        assertThat(statusOf(incomeChild)).isIn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED);
    }

    @Test
    void AS_9_2_supersedeWhileWaitingForFunderLeavesStatusAlone() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(2);
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");
        long applied = count("applyOutcome");

        wf.supersede();
        assertThat(result(wf)).isEqualTo("SUPERSEDED");
        assertThat(count("applyOutcome")).isEqualTo(applied);
        assertThat(acts.status).isEqualTo("STIPULATED");
        assertThat(acts.pendingStips).containsExactly("PRICE"); // kept as history
        assertThat(acts.disburseCalls.get()).isZero();
    }

    @Test
    void AS_9_5_notLatestCompletesSuperseded() {
        // V2 is created while V1's checks run: the activities stop writing and report SUPERSEDED.
        acts.incomeGate = new CountDownLatch(1);
        FundingWorkflow wf = startRun(3);
        until(() -> acts.calls.contains("verifyIncome"), "INCOME in flight");
        acts.latest = false;
        acts.incomeGate.countDown();
        assertThat(result(wf)).isEqualTo("SUPERSEDED");
        assertThat(acts.status).isEqualTo("PENDING_REVIEW");
        assertThat(acts.disburseCalls.get()).isZero();
    }

    @Test
    void AS_9_5_rerunOnANonLatestVersionCompletesSuperseded() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(4);
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");
        acts.latest = false;
        wf.documentsUpdated(77L);
        assertThat(result(wf)).isEqualTo("SUPERSEDED");
        assertThat(wf.getState().attempts()).containsEntry(Check.PRICE, 1); // the reload saw "not latest"; nothing re-ran
    }

    @Test
    void AS_1_8_documentsUpdatedReRunsAllChecks() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(5);
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");

        acts.risc.put("cash_price", "31450.00"); // the dealer's corrected document (the API moved the version to PENDING_REVIEW)
        acts.status = "PENDING_REVIEW";
        wf.documentsUpdated(42L);
        assertThat(result(wf)).isEqualTo("FUNDED");
        // Every check re-ran as attempt 2 on a fresh snapshot, and the price stip was auto-satisfied (AS-8.8).
        assertThat(wf.getState().attempts()).containsOnly(
                java.util.Map.entry(Check.TILA, 2), java.util.Map.entry(Check.PRICE, 2), java.util.Map.entry(Check.VIN, 2),
                java.util.Map.entry(Check.GAP, 2), java.util.Map.entry(Check.INCOME, 2));
        assertThat(count("loadVersionSnapshot")).isEqualTo(2);
        assertThat(acts.calls).contains("autoSatisfied:PRICE");
    }

    @Test
    void AS_1_8_aRedeliveredDocumentEventDoesNotReRunAgain() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(6);
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");

        wf.documentsUpdated(42L);
        assertThat(settle(wf).attempts()).containsEntry(Check.PRICE, 2); // still failing: STIPULATED again
        long applied = count("applyOutcome");

        wf.documentsUpdated(42L); // the same event again (SQS is at-least-once)
        wf.stipResolved();        // an unrelated wake-up: it must not pick up a re-run from the duplicate
        until(() -> count("applyOutcome") > applied, "the wake-up was handled");
        assertThat(wf.getState().attempts()).containsEntry(Check.PRICE, 2);

        wf.documentsUpdated(43L); // a genuinely new document does re-run
        assertThat(settle(wf).attempts()).containsEntry(Check.PRICE, 3);
    }
}
