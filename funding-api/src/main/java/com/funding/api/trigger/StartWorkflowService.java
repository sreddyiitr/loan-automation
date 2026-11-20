package com.funding.api.trigger;

import com.funding.api.ApiProblem;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.SupersededException;
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
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts one {@link FundingWorkflow} per application version (FR-03). Duplicate deliveries while a
 * run is open return that run (200 {@code alreadyStarted}); stale messages get 409 so the Lambda
 * acknowledges them (AS-1.6).
 *
 * <p>Slice S5: before starting a version it sends {@code supersede} to the open run of every older
 * version of the same application (AS-9.2; none open is a no-op, AS-9.6). A
 * {@code DOCUMENT_VERSION_ADDED} event moves a STIPULATED version back to PENDING_REVIEW (audited,
 * once per document) and signals {@code documentsUpdated} to its open run (AS-1.8); with no open
 * run it is 409 {@code NO_OPEN_RUN}.
 */
public class StartWorkflowService {

    public static final String PENDING_REVIEW_EVENT = "APPLICATION_VERSION_PENDING_REVIEW";
    private static final Logger LOG = LoggerFactory.getLogger(StartWorkflowService.class);
    public static final String DOCUMENT_ADDED_EVENT = "DOCUMENT_VERSION_ADDED";
    private static final Set<String> EVENTS = Set.of(PENDING_REVIEW_EVENT, DOCUMENT_ADDED_EVENT);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final VersionRepository versions;
    private final AuditRepository audit;
    private final WorkflowClient client;
    private final String env;
    private final TaskQueues queues;

    public StartWorkflowService(JdbcClient jdbc, TransactionTemplate tx, WorkflowClient client, String env, TaskQueues queues) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.versions = new VersionRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
        this.client = client;
        this.env = env;
        this.queues = queues;
    }

    /** {@code signalled}: a document event was passed to the open run as {@code documentsUpdated}. */
    public record Started(String workflowId, String runId, boolean alreadyStarted, boolean signalled) {}

    public Started start(StartWorkflowRequest r) {
        if (r == null || r.eventType() == null || !EVENTS.contains(r.eventType())
                || r.applicationVersionId() == null || r.applicationVersionId() <= 0) {
            throw new IllegalArgumentException("eventType must be one of " + EVENTS + " and applicationVersionId must be > 0");
        }
        if (DOCUMENT_ADDED_EVENT.equals(r.eventType()) && (r.documentId() == null || r.documentId() <= 0)) {
            throw new IllegalArgumentException(DOCUMENT_ADDED_EVENT + " needs documentId > 0");
        }
        long id = r.applicationVersionId();
        String workflowId = WorkflowIds.parent(env, id);
        VersionRow v = versions.find(id).orElseThrow(() ->
                new ApiProblem(HttpStatus.CONFLICT, "VERSION_NOT_FOUND", "application version " + id + " does not exist"));
        if (!v.latest()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_LATEST_VERSION", "application version " + id + " is no longer latest");
        }
        if (DOCUMENT_ADDED_EVENT.equals(r.eventType())) {
            return documentAdded(v, r);
        }
        supersedeOlderVersions(v);
        Optional<String> open = openRunId(workflowId);
        if (open.isPresent()) {
            LOG.info("duplicate start for {} (message {}): run {} is open", workflowId, r.sourceMessageId(), open.get());
            return new Started(workflowId, open.get(), true, false);
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
        return new Started(workflowId, exec.getRunId(), false, false);
    }

    /** AS-9.2 / AS-9.6: older versions' open runs complete SUPERSEDED; closed or never-started runs are skipped. */
    private void supersedeOlderVersions(VersionRow v) {
        List<Long> older = jdbc.sql("""
                SELECT application_version_id FROM application_versions
                 WHERE loan_application_id = ? AND version_number < ? ORDER BY version_number""")
                .params(v.loanApplicationId(), v.versionNumber()).query(Long.class).list();
        for (long old : older) {
            String oldId = WorkflowIds.parent(env, old);
            try {
                client.newWorkflowStub(FundingWorkflow.class, oldId).supersede();
                LOG.info("superseded {} (version {} of {} was submitted)", oldId, v.versionNumber(), v.loanApplicationId());
            } catch (WorkflowNotFoundException e) {
                LOG.debug("no open run for {}; nothing to supersede", oldId); // AS-9.6
            }
        }
    }

    /**
     * AS-1.8: the open run re-runs every check. The status change and its audit row are written once
     * per document (a redelivered event finds the audit row and only signals again; the workflow
     * ignores a document it already handled). STIPULATED goes back to PENDING_REVIEW (spec §6 "re-run
     * started"). Only PENDING_REVIEW and STIPULATED re-run (C-20): an APPROVED or final version gets
     * 409 {@code NOT_RERUNNABLE}, which the Lambda acknowledges.
     */
    private Started documentAdded(VersionRow v, StartWorkflowRequest r) {
        long id = v.applicationVersionId();
        String workflowId = WorkflowIds.parent(env, id);
        String runId = openRunId(workflowId).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT, "NO_OPEN_RUN",
                "application version " + id + " has no open workflow run to re-run checks in"));
        String actor = WorkflowIds.systemActor(workflowId);
        tx.executeWithoutResult(s -> {
            VersionRow locked;
            try {
                locked = versions.lockLatest(id);
            } catch (SupersededException e) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOT_LATEST_VERSION", "application version " + id + " is no longer latest");
            }
            boolean handled = audit.forEntity("DOCUMENT", Long.toString(r.documentId())).stream()
                    .anyMatch(a -> "RERUN_REQUESTED".equals(a.action()));
            if (handled) {
                return;
            }
            if (!"PENDING_REVIEW".equals(locked.status()) && !"STIPULATED".equals(locked.status())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOT_RERUNNABLE", "application version " + id + " is " + locked.status()
                        + "; a new document re-runs checks only under review");
            }
            audit.record("DOCUMENT", Long.toString(r.documentId()), "RERUN_REQUESTED", actor, null,
                    Map.of("applicationVersionId", id, "checks", "ALL"));
            if ("STIPULATED".equals(locked.status())) {
                versions.setStatus(locked, "PENDING_REVIEW");
                audit.record("APPLICATION_VERSION", Long.toString(id), "STATUS_CHANGED", actor,
                        Map.of("status", "STIPULATED"), Map.of("status", "PENDING_REVIEW", "reason", "DOCUMENT_ADDED"));
            }
        });
        client.newWorkflowStub(FundingWorkflow.class, workflowId).documentsUpdated(r.documentId());
        LOG.info("document {} added to version {}: {} re-runs every check (message {})", r.documentId(), id, workflowId, r.sourceMessageId());
        return new Started(workflowId, runId, false, true);
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
