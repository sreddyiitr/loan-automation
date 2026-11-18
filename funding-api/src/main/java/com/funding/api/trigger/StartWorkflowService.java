package com.funding.api.trigger;

import com.funding.api.ApiProblem;
import com.funding.persistence.VersionRepository;
import com.funding.persistence.VersionRow;
import com.funding.workflows.FundingInput;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.WorkflowIds;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Starts one {@link FundingWorkflow} per application version (FR-03). Duplicate deliveries while a
 * run is open return that run (200 {@code alreadyStarted}); stale messages get 409 so the Lambda
 * acknowledges them (AS-1.6). Supersede of the previous version arrives in slice S5.
 */
public class StartWorkflowService {

    public static final String PENDING_REVIEW_EVENT = "APPLICATION_VERSION_PENDING_REVIEW";
    private static final Logger LOG = LoggerFactory.getLogger(StartWorkflowService.class);
    private static final Set<String> EVENTS = Set.of(PENDING_REVIEW_EVENT);

    private final VersionRepository versions;
    private final WorkflowClient client;
    private final String env;
    private final TaskQueues queues;

    public StartWorkflowService(JdbcClient jdbc, WorkflowClient client, String env, TaskQueues queues) {
        this.versions = new VersionRepository(jdbc);
        this.client = client;
        this.env = env;
        this.queues = queues;
    }

    public record Started(String workflowId, String runId, boolean alreadyStarted) {}

    public Started start(StartWorkflowRequest r) {
        if (r == null || r.eventType() == null || !EVENTS.contains(r.eventType())
                || r.applicationVersionId() == null || r.applicationVersionId() <= 0) {
            throw new IllegalArgumentException("eventType must be one of " + EVENTS + " and applicationVersionId must be > 0");
        }
        long id = r.applicationVersionId();
        String workflowId = WorkflowIds.parent(env, id);
        VersionRow v = versions.find(id).orElseThrow(() ->
                new ApiProblem(HttpStatus.CONFLICT, "VERSION_NOT_FOUND", "application version " + id + " does not exist"));
        if (!v.latest()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_LATEST_VERSION", "application version " + id + " is no longer latest");
        }
        Optional<String> open = openRunId(workflowId);
        if (open.isPresent()) {
            LOG.info("duplicate start for {} (message {}): run {} is open", workflowId, r.sourceMessageId(), open.get());
            return new Started(workflowId, open.get(), true);
        }
        if (!"PENDING_REVIEW".equals(v.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_PENDING_REVIEW", "application version " + id + " is " + v.status());
        }
        FundingWorkflow wf = client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(queues.orchestration())
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build());
        WorkflowExecution exec = WorkflowClient.start(wf::run, new FundingInput(id, FundingInput.VALIDATE, queues));
        LOG.info("started {} run {} for {} (message {})", workflowId, exec.getRunId(), v.loanApplicationId(), r.sourceMessageId());
        return new Started(workflowId, exec.getRunId(), false);
    }

    public Map<String, Object> describe(long versionId) {
        String workflowId = WorkflowIds.parent(env, versionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        Optional<DescribeWorkflowExecutionResponse> d = describeRaw(workflowId);
        if (d.isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "NO_WORKFLOW", "no workflow for application version " + versionId);
        }
        var info = d.get().getWorkflowExecutionInfo();
        out.put("runId", info.getExecution().getRunId());
        out.put("runStatus", info.getStatus().name().replace("WORKFLOW_EXECUTION_STATUS_", ""));
        try {
            out.put("state", client.newWorkflowStub(FundingWorkflow.class, workflowId).getState());
        } catch (RuntimeException e) {
            out.put("state", null);
        }
        return out;
    }

    private Optional<String> openRunId(String workflowId) {
        return describeRaw(workflowId)
                .filter(d -> d.getWorkflowExecutionInfo().getStatus() == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING)
                .map(d -> d.getWorkflowExecutionInfo().getExecution().getRunId());
    }

    private Optional<DescribeWorkflowExecutionResponse> describeRaw(String workflowId) {
        try {
            return Optional.of(client.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                            .build()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

}
