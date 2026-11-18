package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.Outcome;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T126: parent workflow with the TILA child, against in-memory activities. */
class FundingWorkflowTest {

    private static final TaskQueues QUEUES = TaskQueues.forEnv("test");
    private TestWorkflowEnvironment env;
    private FakeActivities acts;
    private WorkflowClient client;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        acts = new FakeActivities();
        Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, ActivityOptionsConfig.forTests());
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    private FundingWorkflow workflow(long versionId) {
        return client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.parent("test", versionId))
                .setTaskQueue(QUEUES.orchestration())
                .build());
    }

    @Test
    void AS_8_1_allPassApprovesAndDisbursesOnce() {
        FundingResult r = workflow(1).run(new FundingInput(1, FundingInput.VALIDATE, QUEUES));
        assertThat(r.result()).isEqualTo("FUNDED");
        assertThat(acts.disburseCalls.get()).isEqualTo(1);
        assertThat(acts.calls).containsSubsequence("loadVersionSnapshot", "recordOutcome:TILA:PASS", "applyOutcome", "disburse", "markFunded");
    }

    @Test
    void AS_8_1_disburseSkippedWhenVersionNoLongerApproved() {
        acts.pendingManualStip = true; // a funder added a manual stip between applyOutcome and the call
        FundingWorkflow wf = workflow(2);
        WorkflowClient.start(wf::run, new FundingInput(2, FundingInput.VALIDATE, QUEUES));
        env.sleep(Duration.ofSeconds(5));
        assertThat(acts.disburseCalls.get()).isEqualTo(1);
        assertThat(acts.calls).doesNotContain("markFunded");
        assertThat(wf.getState().step()).isEqualTo("WAITING_FOR_FUNDER");
    }

    @Test
    void AS_8_2_failGoesStipulatedAndWaits() {
        acts.risc.put("total_of_payments", "33802.60");
        FundingWorkflow wf = workflow(3);
        WorkflowClient.start(wf::run, new FundingInput(3, FundingInput.VALIDATE, QUEUES));
        env.sleep(Duration.ofSeconds(5));
        FundingState s = wf.getState();
        assertThat(s.status()).isEqualTo("STIPULATED");
        assertThat(s.step()).isEqualTo("WAITING_FOR_FUNDER");
        assertThat(s.outcomes()).containsEntry(Check.TILA, Outcome.FAIL);
        assertThat(acts.disburseCalls.get()).isZero();
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus().name()).isEqualTo("WORKFLOW_EXECUTION_STATUS_RUNNING");
    }

    @Test
    void childTechnicalFailureBecomesError() {
        acts.failRecordOutcome = true; // the child's activity exhausts its retries
        FundingWorkflow wf = workflow(4);
        WorkflowClient.start(wf::run, new FundingInput(4, FundingInput.VALIDATE, QUEUES));
        env.sleep(Duration.ofMinutes(10));
        FundingState s = wf.getState();
        assertThat(s.outcomes()).containsEntry(Check.TILA, Outcome.ERROR);
        assertThat(s.status()).isEqualTo("PENDING_REVIEW"); // AS-8.4: held for a funder
        assertThat(acts.disburseCalls.get()).isZero();
    }

    @Test
    void businessFailIsNotRetried() {
        acts.risc.put("total_of_payments", "33802.60");
        WorkflowClient.start(workflow(5)::run, new FundingInput(5, FundingInput.VALIDATE, QUEUES));
        env.sleep(Duration.ofSeconds(5));
        assertThat(acts.recorded.get(Check.TILA)).hasSize(1);
    }

    @Test
    void AS_9_5_supersededVersionCompletesWithoutWriting() {
        acts.latest = false;
        FundingResult r = workflow(6).run(new FundingInput(6, FundingInput.VALIDATE, QUEUES));
        assertThat(r.result()).isEqualTo("SUPERSEDED");
        assertThat(acts.calls).noneMatch(c -> c.startsWith("recordOutcome") || c.equals("disburse"));
    }

    @Test
    void getStateReportsOutcomesAndAttempts() {
        acts.risc.put("total_of_payments", "33802.60");
        FundingWorkflow wf = workflow(7);
        WorkflowClient.start(wf::run, new FundingInput(7, FundingInput.VALIDATE, QUEUES));
        env.sleep(Duration.ofSeconds(5));
        assertThat(wf.getState().attempts()).containsEntry(Check.TILA, 1);
    }
}
