package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * NFR-07 groundwork (T132): recorded histories in {@code src/test/resources/histories/} must keep
 * replaying against the current workflow code. Re-record with {@code -Drecord.histories=true} only
 * when a change is guarded by {@code Workflow.getVersion} (the full replay suite is T602, slice S6).
 */
class FundingWorkflowHistoryTest {

    private static final Path DIR = Path.of("src/test/resources/histories");
    private static final TaskQueues QUEUES = TaskQueues.forEnv("history");

    @Test
    void NFR_07_recordedHistoriesReplay() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(DIR)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("recorded histories").isNotEmpty();
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            // Same factory as production (Workers.register), so the replayed code is the deployed code.
            io.temporal.worker.Worker worker = env.newWorker(QUEUES.orchestration());
            worker.registerWorkflowImplementationFactory(FundingWorkflow.class,
                    () -> new FundingWorkflowImpl(ActivityOptionsConfig.forTests()));
            for (Path f : files) {
                // File name = workflow ID suffix; child IDs derive from the parent's ID, so replay needs it.
                String workflowId = "funding-history-" + f.getFileName().toString().replace(".json", "");
                WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(Files.readString(f), workflowId), worker);
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "record.histories", matches = "true")
    void recordHistories() throws IOException {
        // Recorded per slice; older files stay so replay keeps covering in-flight runs from earlier code.
        record("S2-LA-1001-funded", false);
        record("S2-LA-1006-stipulated", true);
    }

    private void record(String name, boolean brokenPrice) throws IOException {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities acts = new FakeActivities();
            if (brokenPrice) {
                acts.risc.put("cash_price", "31450.01"); // PRICE fails; TILA, VIN and GAP pass
            }
            Workers.register(env.getWorkerFactory(), QUEUES, acts, acts, ActivityOptionsConfig.forTests());
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
