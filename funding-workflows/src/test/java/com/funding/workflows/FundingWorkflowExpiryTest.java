package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
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
 * T404 / AS-8.6: a version not FUNDED or REJECTED 30 days after it was first submitted expires, in
 * every waiting state; signals and re-runs don't move the deadline; an in-flight disbursement
 * finishes first. Time-skipping test server; the audit row and stips are covered in
 * {@code FundingActivitiesDbTest}.
 */
class FundingWorkflowExpiryTest {

    private static final TaskQueues QUEUES = TaskQueues.forEnv("test");
    private static final Duration P30D = Duration.ofDays(30);
    private TestWorkflowEnvironment env;
    private FakeActivities acts;
    private WorkflowClient client;
    private long submittedAt;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        acts = new FakeActivities();
        submittedAt = env.currentTimeMillis();
        acts.submittedAt = submittedAt;
        acts.expireAfter = P30D;
        Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, acts, ActivityOptionsConfig.forTests());
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

    private static String result(FundingWorkflow wf) {
        return WorkflowStub.fromTyped(wf).getResult(FundingResult.class).result();
    }

    /** Waits until day 29, checks the run is still waiting, then lets it expire and checks it did so at day 30. */
    private void expiresAtDay30(FundingWorkflow wf, String waitingStatus) {
        env.sleep(Duration.ofDays(29));
        FundingState s = wf.getState();
        assertThat(s.status()).isEqualTo(waitingStatus);
        assertThat(s.step()).isEqualTo("WAITING_FOR_FUNDER");
        assertThat(s.expiresAtEpochMillis()).isEqualTo(submittedAt + P30D.toMillis());
        assertThat(acts.expireCalls.get()).isZero();

        assertThat(result(wf)).isEqualTo("EXPIRED");
        assertThat(acts.expireCalls.get()).isEqualTo(1);
        assertThat(acts.status).isEqualTo("EXPIRED");
        assertExpiredAtDay30();
    }

    /** Workflow time when expire ran (the client's clock keeps skipping after the run completes). */
    private void assertExpiredAtDay30() {
        assertThat(acts.expireScheduledAt - submittedAt).isBetween(P30D.toMillis(), P30D.plusMinutes(5).toMillis());
    }

    @Test
    void AS_8_6_expiresAt30DaysInPendingReviewHeld() {
        acts.incomeDown = true; // ERROR hold
        expiresAtDay30(startRun(1), "PENDING_REVIEW");
    }

    @Test
    void AS_8_6_expiresAt30DaysInStipulated() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(2);
        expiresAtDay30(wf, "STIPULATED");
        assertThat(acts.pendingStips).containsExactly("PRICE"); // PENDING stips stay as history
    }

    @Test
    void AS_8_6_expiresAt30DaysInApproved() {
        acts.disburseDown = true; // waiting on a failed disbursement
        expiresAtDay30(startRun(3), "APPROVED");
    }

    @Test
    void AS_8_6_signalsDoNotResetClock() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(4);
        for (int day = 8; day <= 24; day += 8) {
            env.sleep(Duration.ofDays(8));
            wf.stipResolved(); // nothing changed: just a wake-up
        }
        assertThat(result(wf)).isEqualTo("EXPIRED");
        assertExpiredAtDay30();
    }

    @Test
    void AS_8_6_rerunAtDay29StillExpiresAtDay30() {
        acts.incomeDown = true;
        FundingWorkflow wf = startRun(5);
        env.sleep(Duration.ofDays(29));
        wf.rerunChecks(List.of(Check.INCOME)); // still down: ERROR again, attempt 2
        assertThat(result(wf)).isEqualTo("EXPIRED");
        assertThat(wf.getState().attempts()).containsEntry(Check.INCOME, 2);
        assertExpiredAtDay30();
    }

    @Test
    void AS_8_6_inFlightDisbursementFinishesFirst() {
        // The deadline passes while the (real-time) disbursement call is in flight: it is not cancelled.
        acts.expireAfter = Duration.ofMillis(500);
        acts.disburseTakes = Duration.ofSeconds(2);
        assertThat(result(startRun(6))).isEqualTo("FUNDED");
        assertThat(acts.expireCalls.get()).isZero();
    }

    @Test
    void AS_8_6_inFlightDisbursementThatFailsThenExpires() {
        acts.expireAfter = Duration.ofMillis(500);
        acts.disburseTakes = Duration.ofMillis(800);
        acts.disburseDown = true;
        assertThat(result(startRun(7))).isEqualTo("EXPIRED");
        assertThat(acts.disburseCalls.get()).isEqualTo(ActivityOptionsConfig.forTests().maxAttempts());
        assertThat(acts.calls).containsSubsequence("disburse", "expire");
    }

    @Test
    void AS_8_6_terminalStatusWinsTheRaceWithExpiry() {
        acts.risc.put("cash_price", "31450.01");
        FundingWorkflow wf = startRun(8);
        env.sleep(Duration.ofDays(1));
        acts.status = "REJECTED"; // funder rejected; the signal is still on its way when the timer fires
        assertThat(result(wf)).isEqualTo("REJECTED");
    }
}
