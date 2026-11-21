package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T602, NFR-07: every recorded history in {@code src/test/resources/histories/} replays against the
 * current workflow code, and the rule "a workflow code change is guarded by {@code Workflow.getVersion}
 * and adds a recorded history" is enforced: every change id is used through a named constant, appears
 * in at least one history (its new path replays) and is absent from at least one (the older path
 * still replays). Record new histories with {@code FundingWorkflowHistoryTest -Drecord.histories=true}.
 */
class FundingWorkflowReplayTest {

    private static final Path DIR = Path.of("src/test/resources/histories");
    private static final Path IMPL = Path.of("src/main/java/com/funding/workflows/FundingWorkflowImpl.java");
    private static final TaskQueues QUEUES = TaskQueues.forEnv("history");
    private static final Map<String, WorkflowExecutionHistory> HISTORIES = new LinkedHashMap<>();

    @BeforeAll
    static void load() throws Exception {
        try (Stream<Path> s = Files.list(DIR)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                // File name = workflow ID suffix; child IDs derive from the parent's ID, so replay needs it.
                String name = f.getFileName().toString().replace(".json", "");
                HISTORIES.put(name, WorkflowExecutionHistory.fromJson(Files.readString(f), "funding-history-" + name));
            }
        }
    }

    @Test
    void NFR_07_everyRecordedHistoryReplays() throws Exception {
        assertThat(HISTORIES).as("recorded histories").isNotEmpty();
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            // Same factory as production (Workers.register), so the replayed code is the deployed code.
            io.temporal.worker.Worker worker = env.newWorker(QUEUES.orchestration());
            worker.registerWorkflowImplementationFactory(FundingWorkflow.class,
                    () -> new FundingWorkflowImpl(ActivityOptionsConfig.forTests()));
            List<String> failed = new ArrayList<>();
            for (Map.Entry<String, WorkflowExecutionHistory> h : HISTORIES.entrySet()) {
                try {
                    WorkflowReplayer.replayWorkflowExecution(h.getValue(), worker);
                } catch (Exception e) {
                    failed.add(h.getKey() + ": " + e.getMessage());
                }
            }
            assertThat(failed).as("histories that no longer replay (an unguarded workflow change?)").isEmpty();
        }
    }

    @Test
    void NFR_07_everyVersionChangeIsNamedAndCoveredByHistoriesOnBothSides() throws Exception {
        Set<String> changes = new TreeSet<>();
        for (Field f : FundingWorkflowImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class && f.getName().endsWith("_CHANGE")) {
                f.setAccessible(true);
                changes.add((String) f.get(null));
            }
        }
        assertThat(changes).isNotEmpty();

        // Every getVersion call goes through one of those constants (no inline change ids to miss).
        String source = Files.readString(IMPL);
        Matcher m = Pattern.compile("Workflow\\.getVersion\\(\\s*([^,]+),").matcher(source);
        List<String> args = new ArrayList<>();
        while (m.find()) {
            args.add(m.group(1).trim());
        }
        assertThat(args).isNotEmpty().allSatisfy(a -> assertThat(a).as("getVersion change id").endsWith("_CHANGE"));

        for (String change : changes) {
            List<String> with = HISTORIES.entrySet().stream().filter(h -> markers(h.getValue()).contains(change)).map(Map.Entry::getKey).toList();
            assertThat(with).as("a recorded history taking the new path of change '%s'", change).isNotEmpty();
            assertThat(HISTORIES.size() - with.size()).as("a recorded history from before change '%s'", change).isPositive();
        }
    }

    /** The change ids of the version markers in a history. */
    private static Set<String> markers(WorkflowExecutionHistory h) {
        Set<String> out = new TreeSet<>();
        for (HistoryEvent e : h.getEvents()) {
            if (e.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED) {
                var details = e.getMarkerRecordedEventAttributes().getDetailsMap();
                if (details.containsKey("changeId")) {
                    String id = details.get("changeId").getPayloads(0).getData().toStringUtf8();
                    out.add(id.replace("\"", ""));
                }
            }
        }
        return out;
    }
}
