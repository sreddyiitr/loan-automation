package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.Outcome;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T403: funder signals against in-memory activities. As in production, the test plays the API: it
 * makes the DB change (the fake's status and stips) first, then sends the signal.
 */
class FundingWorkflowSignalsTest {

    private static final TaskQueues QUEUES = TaskQueues.forEnv("test");
    private static final int MAX_SIGNALS = 3;
    private TestWorkflowEnvironment env;
    private FakeActivities acts;
    private WorkflowClient client;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        acts = new FakeActivities();
        acts.submittedAt = env.currentTimeMillis();
        // Production registration, with a parent that continues as new after a few signals.
        Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, acts, ActivityOptionsConfig.forTests(), MAX_SIGNALS);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    private FundingWorkflow startRun(long id) {
        FundingWorkflow wf = client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.parent("test", id)).setTaskQueue(QUEUES.orchestration()).build());
        WorkflowClient.start(wf::run, new FundingInput(id, FundingInput.VALIDATE, QUEUES));
        return wf;
    }

    private FundingState settle(FundingWorkflow wf) {
        env.sleep(Duration.ofSeconds(30)); // past activity retries; far below the 30-day deadline
        return wf.getState();
    }

    private static String result(FundingWorkflow wf) {
        return WorkflowStub.fromTyped(wf).getResult(FundingResult.class).result();
    }

    private void breakPrice() {
        acts.risc.put("cash_price", "31450.01"); // PRICE fails, the others pass
    }

    @Test
    void AS_8_3_lastStipResolvedContinuesToDisbursement() {
        breakPrice();
        FundingWorkflow wf = startRun(1);
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");
        assertThat(acts.disburseCalls.get()).isZero();

        acts.pendingStips.remove("PRICE"); // funder SATISFIED it (API write)
        wf.stipResolved();
        assertThat(result(wf)).isEqualTo("FUNDED");
        assertThat(acts.disburseCalls.get()).isEqualTo(1);
    }

    @Test
    void AS_8_4_rerunErroredChecksIncrementsAttempt() {
        acts.incomeDown = true;
        FundingWorkflow wf = startRun(2);
        FundingState held = settle(wf);
        assertThat(held.status()).isEqualTo("PENDING_REVIEW");
        assertThat(held.outcomes()).containsEntry(Check.INCOME, Outcome.ERROR);
        assertThat(held.attempts()).containsEntry(Check.INCOME, 1);

        acts.incomeDown = false;
        wf.rerunChecks(List.of(Check.INCOME));
        assertThat(result(wf)).isEqualTo("FUNDED");
        // Only INCOME re-ran, as attempt 2, under its own child ID; the other checks kept attempt 1.
        assertThat(client.fetchHistory(WorkflowIds.child(WorkflowIds.parent("test", 2), Check.INCOME, 2)).getEvents()).isNotEmpty();
        assertThat(acts.recorded.get(Check.INCOME)).extracting(o -> o.outcome()).containsExactly(Outcome.ERROR, Outcome.PASS); // attempt 1 recorded by the parent
        assertThat(acts.recorded.get(Check.TILA)).hasSize(1);
        assertThat(acts.calls.stream().filter("loadVersionSnapshot"::equals)).hasSize(2); // fresh snapshot for the re-run
    }

    @Test
    void AS_8_5_rejectCompletesWithoutDisbursement() {
        breakPrice();
        FundingWorkflow wf = startRun(3);
        settle(wf);
        acts.status = "REJECTED"; // API write
        wf.reject("funder-7", "dealer withdrew");
        assertThat(result(wf)).isEqualTo("REJECTED");
        assertThat(acts.disburseCalls.get()).isZero();
    }

    @Test
    void AS_8_7_manualApproveOverridesError() {
        acts.incomeDown = true;
        FundingWorkflow wf = startRun(4);
        assertThat(settle(wf).status()).isEqualTo("PENDING_REVIEW");
        acts.status = "APPROVED"; // API write: allowed because no stip is PENDING (AS-8.7)
        wf.approve("funder-7");
        assertThat(result(wf)).isEqualTo("FUNDED");
        assertThat(acts.disburseCalls.get()).isEqualTo(1);
    }

    @Test
    void AS_8_8_rerunPassAutoSatisfiesStaleStip() {
        breakPrice();
        FundingWorkflow wf = startRun(5);
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");

        acts.risc.put("cash_price", "31450.00"); // corrected document data
        acts.status = "PENDING_REVIEW";          // API: STIPULATED -> PENDING_REVIEW, re-run started
        wf.rerunChecks(List.of(Check.PRICE));
        assertThat(result(wf)).isEqualTo("FUNDED");
        assertThat(acts.calls).contains("autoSatisfied:PRICE");
    }

    @Test
    void AS_8_8_rerunFailKeepsExistingStip() {
        breakPrice();
        FundingWorkflow wf = startRun(6);
        settle(wf);
        acts.status = "PENDING_REVIEW";
        wf.rerunChecks(List.of(Check.PRICE));
        FundingState s = settle(wf);
        assertThat(s.status()).isEqualTo("STIPULATED");
        assertThat(s.attempts()).containsEntry(Check.PRICE, 2);
        assertThat(acts.recorded.get(Check.PRICE)).extracting(o -> o.outcome()).containsExactly(Outcome.FAIL, Outcome.FAIL);
        assertThat(acts.pendingStips).containsExactly("PRICE"); // one stip, not two (FR-07)
        assertThat(acts.calls).doesNotContain("autoSatisfied:PRICE");
    }

    @Test
    void C_8_retryDisbursementAfterExhausted() {
        acts.disburseDown = true;
        FundingWorkflow wf = startRun(7);
        FundingState s = settle(wf);
        assertThat(s.status()).isEqualTo("APPROVED");
        assertThat(s.disbursementFailed()).isTrue();
        int attempts = acts.disburseCalls.get();
        assertThat(attempts).isEqualTo(ActivityOptionsConfig.forTests().maxAttempts());

        wf.stipResolved(); // an unrelated wake-up does not pay again
        settle(wf);
        assertThat(acts.disburseCalls.get()).isEqualTo(attempts);

        acts.disburseDown = false;
        wf.retryDisbursement("funder-7");
        assertThat(result(wf)).isEqualTo("FUNDED");
        assertThat(acts.disburseCalls.get()).isEqualTo(attempts + 1);
    }

    @Test
    void AS_10_8_manualStipBeforeDisbursementGoesStipulated() {
        acts.disburseDown = true; // APPROVED but not yet disbursed
        FundingWorkflow wf = startRun(8);
        assertThat(settle(wf).status()).isEqualTo("APPROVED");

        acts.pendingStips.add("MANUAL"); // API: manual stip, APPROVED -> STIPULATED
        acts.status = "STIPULATED";
        wf.stipResolved();
        assertThat(settle(wf).status()).isEqualTo("STIPULATED");
        assertThat(wf.getState().disbursementFailed()).isFalse();

        acts.disburseDown = false;
        acts.pendingStips.remove("MANUAL"); // funder satisfied it
        wf.stipResolved();
        assertThat(result(wf)).isEqualTo("FUNDED"); // a fresh approval gets a fresh disbursement attempt
    }

    @Test
    void continuesAsNewAfterManySignalsKeepingDeadlineAndState() {
        breakPrice();
        FundingWorkflow wf = startRun(9);
        FundingState before = settle(wf);
        for (int i = 0; i < MAX_SIGNALS; i++) {
            wf.stipResolved();
            settle(wf);
        }
        String firstRun = WorkflowStub.fromTyped(wf).getExecution().getRunId();
        FundingWorkflow latest = client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent("test", 9));
        FundingState after = latest.getState();
        // The current run was continued from the first one.
        assertThat(client.fetchHistory(WorkflowIds.parent("test", 9)).getEvents().get(0)
                .getWorkflowExecutionStartedEventAttributes().getContinuedExecutionRunId()).isEqualTo(firstRun);
        assertThat(after.expiresAtEpochMillis()).isEqualTo(before.expiresAtEpochMillis()).isPositive();
        assertThat(after.outcomes()).isEqualTo(before.outcomes());
        assertThat(after.attempts()).isEqualTo(before.attempts());
        assertThat(acts.calls.stream().filter("loadVersionSnapshot"::equals)).hasSize(1); // CONTINUE doesn't reload or re-run

        acts.pendingStips.remove("PRICE");
        latest.stipResolved();
        assertThat(client.newUntypedWorkflowStub(WorkflowIds.parent("test", 9)).getResult(FundingResult.class).result()).isEqualTo("FUNDED");
    }

    @Test
    void signalArrivingWhileBusyIsNotLost() {
        breakPrice();
        FundingWorkflow wf = startRun(10);
        wf.rerunChecks(List.of(Check.PRICE)); // sent while the first validation is still running
        FundingState s = settle(wf);
        assertThat(s.attempts()).as("the early re-run was processed without another wake-up").containsEntry(Check.PRICE, 2);
        assertThat(s.status()).isEqualTo("STIPULATED");
    }

    /** S5: the documents a run already re-ran for travel through continueAsNew, so a redelivery after it is still ignored. */
    @Test
    void handledDocumentsSurviveContinueAsNew() {
        breakPrice();
        FundingWorkflow wf = startRun(11);
        settle(wf);
        wf.documentsUpdated(7L); // re-runs (PRICE still fails): attempt 2
        settle(wf);
        for (int i = 1; i < MAX_SIGNALS; i++) {
            wf.stipResolved();
            settle(wf);
        }
        FundingWorkflow latest = client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent("test", 11));
        assertThat(client.fetchHistory(WorkflowIds.parent("test", 11)).getEvents().get(0)
                .getWorkflowExecutionStartedEventAttributes().getContinuedExecutionRunId()).isNotEmpty();
        assertThat(latest.getState().attempts()).containsEntry(Check.PRICE, 2);

        latest.documentsUpdated(7L); // redelivered after the run continued as new
        latest.stipResolved();
        assertThat(settle(latest).attempts()).containsEntry(Check.PRICE, 2);
        latest.documentsUpdated(8L);
        assertThat(settle(latest).attempts()).containsEntry(Check.PRICE, 3);
    }
}
