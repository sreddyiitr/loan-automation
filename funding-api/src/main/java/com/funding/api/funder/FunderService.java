package com.funding.api.funder;

import com.funding.api.ApiProblem;
import com.funding.domain.Check;
import com.funding.domain.RulesConfig;
import com.funding.domain.rules.RiscCompleteness;
import com.funding.domain.status.StipWaivers;
import com.funding.domain.status.VersionStatus;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.FunderQueries;
import com.funding.persistence.SnapshotRepository;
import com.funding.persistence.StipRepository;
import com.funding.persistence.SupersededException;
import com.funding.persistence.VersionRepository;
import com.funding.persistence.VersionRow;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Funder operations (US-10, plan "Funder actions"). Every write: lock the version, check it is the
 * latest and not terminal (AS-10.7), make the guarded and audited DB change in one transaction, and
 * only after the commit signal the version's workflow. A closed run means the version is terminal
 * (C-11), so a missing workflow is logged, not an error.
 */
@Service
public class FunderService {

    private static final Logger LOG = LoggerFactory.getLogger(FunderService.class);

    private final String env;
    private final TransactionTemplate tx;
    private final WorkflowClient client;
    private final RulesConfig rules;
    private final VersionRepository versions;
    private final StipRepository stips;
    private final AuditRepository audit;
    private final SnapshotRepository snapshots;
    private final FunderQueries queries;

    public FunderService(@Value("${funding.env}") String env, JdbcClient jdbc, TransactionTemplate tx, WorkflowClient client,
                         RulesConfig rules) {
        this.env = env;
        this.tx = tx;
        this.client = client;
        this.rules = rules;
        this.versions = new VersionRepository(jdbc);
        this.stips = new StipRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
        this.snapshots = new SnapshotRepository(jdbc);
        this.queries = new FunderQueries(jdbc);
    }

    // ------------------------------------------------------------------ reads

    public Map<String, Object> overview(String loanApplicationId) {
        VersionRow v = versions.findLatest(loanApplicationId).orElseThrow(() -> notFound("application " + loanApplicationId));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanApplicationId", v.loanApplicationId());
        m.put("applicationVersionId", v.applicationVersionId());
        m.put("latestVersionNumber", v.versionNumber());
        m.put("status", v.status());
        m.put("dealershipId", v.dealershipId());
        m.put("dealershipName", v.dealershipName());
        m.put("borrowerName", v.borrowerName());
        m.put("coBorrowerName", v.coBorrowerName());
        m.put("statedIncomeAnnual", v.statedIncomeAnnual());
        m.put("vehicleVin", v.vehicleVin());
        m.put("assignedFunder", v.assignedFunder());
        m.put("submittedAt", v.submittedAt());
        return m;
    }

    public List<Map<String, Object>> versions(String loanApplicationId) {
        List<Map<String, Object>> rows = queries.versions(loanApplicationId);
        if (rows.isEmpty()) {
            throw notFound("application " + loanApplicationId);
        }
        return rows;
    }

    public List<Map<String, Object>> stips(long versionId) {
        version(versionId);
        return queries.stips(versionId);
    }

    public List<Map<String, Object>> results(long versionId, boolean history) {
        version(versionId);
        return queries.results(versionId, history);
    }

    public List<Map<String, Object>> documents(long versionId) {
        version(versionId);
        return queries.documents(versionId);
    }

    public List<Map<String, Object>> fields(long versionId, long documentId) {
        version(versionId);
        if (!queries.documentBelongsTo(versionId, documentId)) {
            throw notFound("document " + documentId + " of version " + versionId);
        }
        return queries.fields(versionId, documentId);
    }

    public List<Map<String, Object>> audit(long versionId) {
        version(versionId);
        return queries.audit(versionId);
    }

    // ------------------------------------------------------------------ writes

    /** AS-10.2: SATISFIED or WAIVED with a reason; the second of two concurrent resolutions gets 409 ALREADY_RESOLVED. */
    public Map<String, Object> resolveStip(long versionId, long stipId, String status, String reason, String actor) {
        requireText(reason, "reason");
        if (!"SATISFIED".equals(status) && !"WAIVED".equals(status)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status must be SATISFIED or WAIVED, not " + status);
        }
        StipRepository.Stip resolved = tx.execute(s -> {
            guard(versionId);
            StipRepository.Stip stip = stips.find(stipId).filter(x -> x.versionId() == versionId)
                    .orElseThrow(() -> notFound("stip " + stipId + " of version " + versionId));
            // AS-8.10: resolved only by a corrected RISC version that re-runs and passes (AS-8.8), so a
            // funder can neither waive nor satisfy it by hand.
            if (!StipWaivers.waivable(stip.code(), stip.description())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "RISC_STIP_NOT_WAIVABLE", stip.code()
                        + " is raised against RISC fields; it resolves when a corrected RISC version re-runs and passes (AS-8.10)");
            }
            StipRepository.Stip r = stips.resolve(stipId, status, actor, reason).orElseThrow(() ->
                    new ApiProblem(HttpStatus.CONFLICT, "ALREADY_RESOLVED", "stip " + stipId + " is already " + stip.status()));
            audit.record("STIPULATION", Long.toString(stipId), status, actor, Map.of("status", "PENDING"),
                    Map.of("status", status, "note", reason));
            return r;
        });
        signal(versionId, FundingWorkflow::stipResolved);
        return Map.of("stipId", resolved.stipId(), "status", resolved.status(), "versionStatus", status(versionId));
    }

    /** AS-10.8: a funder's stip ({@code validation_check = NULL}); PENDING_REVIEW or APPROVED (not yet disbursing) -> STIPULATED. */
    public Map<String, Object> addManualStip(long versionId, String code, String description, String actor) {
        requireText(code, "code");
        requireText(description, "description");
        Long stipId = tx.execute(s -> {
            VersionRow v = guard(versionId);
            requireSubmitted(v);
            if ("APPROVED".equals(v.status())) {
                refuseWhileDisbursing(v);
            }
            Long id = stips.createManual(versionId, code, description);
            if (id == null) {
                throw new ApiProblem(HttpStatus.CONFLICT, "STIP_ALREADY_PENDING", "a manual " + code + " is already PENDING");
            }
            audit.record("STIPULATION", Long.toString(id), "CREATED", actor, null,
                    Map.of("code", code, "check", "MANUAL", "status", "PENDING"));
            if (!"STIPULATED".equals(v.status())) {
                transition(v, "STIPULATED", actor, "MANUAL_STIP");
            }
            return id;
        });
        signal(versionId, FundingWorkflow::stipResolved);
        return Map.of("stipId", stipId, "status", "PENDING", "versionStatus", status(versionId));
    }

    /** AS-10.3. */
    public Map<String, Object> reassign(long versionId, String funderId, String actor) {
        requireText(funderId, "funderId");
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            versions.setAssignee(versionId, funderId);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("assignedFunder", v.assignedFunder());
            audit.record("APPLICATION_VERSION", Long.toString(versionId), "ASSIGNEE_CHANGED", actor, before,
                    Map.of("assignedFunder", funderId));
        });
        return Map.of("applicationVersionId", versionId, "assignedFunder", funderId);
    }

    /** AS-8.7 (override for checks in ERROR) and AS-8.10 (complete RISC, TILA PASS). */
    public Map<String, Object> approve(long versionId, String actor) {
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            requireSubmitted(v);
            if ("APPROVED".equals(v.status())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "ALREADY_APPROVED", "version " + versionId + " is already APPROVED");
            }
            int pending = stips.pending(versionId).size();
            if (pending > 0) {
                throw new ApiProblem(HttpStatus.CONFLICT, "PENDING_STIPS", pending + " stip(s) are PENDING; resolve them before approving");
            }
            List<String> problems = new ArrayList<>(snapshots.load(versionId)
                    .map(l -> RiscCompleteness.problems(l.snapshot(), rules)).orElse(List.of("RISC")));
            if (!"PASS".equals(queries.currentOutcomes(versionId).get(Check.TILA.name()))) {
                problems.add("TILA not PASS");
            }
            if (!problems.isEmpty()) {
                throw new ApiProblem(HttpStatus.CONFLICT, "RISC_INCOMPLETE", "can't approve: " + problems);
            }
            transition(v, "APPROVED", actor, "MANUAL_APPROVE");
        });
        signal(versionId, wf -> wf.approve(actor));
        return Map.of("applicationVersionId", versionId, "status", "APPROVED");
    }

    /** AS-8.5. */
    public Map<String, Object> reject(long versionId, String reason, String actor) {
        requireText(reason, "reason");
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            requireSubmitted(v);
            if ("APPROVED".equals(v.status())) {
                refuseWhileDisbursing(v);
            }
            transition(v, "REJECTED", actor, reason);
        });
        signal(versionId, wf -> wf.reject(actor, reason));
        return Map.of("applicationVersionId", versionId, "status", "REJECTED");
    }

    /** AS-8.4: re-run the given checks, or by default the ones currently in ERROR. STIPULATED -> PENDING_REVIEW. */
    public Map<String, Object> rerun(long versionId, List<Check> requested, String actor) {
        List<Check> checks = tx.execute(s -> {
            VersionRow v = guard(versionId);
            if (!"PENDING_REVIEW".equals(v.status()) && !"STIPULATED".equals(v.status())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOT_RERUNNABLE", "version " + versionId + " is " + v.status()
                        + "; checks re-run from PENDING_REVIEW or STIPULATED");
            }
            List<Check> c = requested != null && !requested.isEmpty() ? List.copyOf(requested)
                    : queries.currentOutcomes(versionId).entrySet().stream().filter(e -> "ERROR".equals(e.getValue()))
                            .map(e -> Check.valueOf(e.getKey())).toList();
            if (c.isEmpty()) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOTHING_TO_RERUN", "no check is in ERROR; name the checks to re-run");
            }
            audit.record("APPLICATION_VERSION", Long.toString(versionId), "RERUN_REQUESTED", actor, null,
                    Map.of("checks", c.stream().map(Enum::name).toList()));
            if ("STIPULATED".equals(v.status())) {
                transition(v, "PENDING_REVIEW", actor, "RERUN_STARTED");
            }
            return c;
        });
        signal(versionId, wf -> wf.rerunChecks(checks));
        return Map.of("applicationVersionId", versionId, "checks", checks.stream().map(Enum::name).toList());
    }

    /** C-8: only an APPROVED version whose workflow reports a failed disbursement. */
    public Map<String, Object> retryDisbursement(long versionId, String actor) {
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            if (!"APPROVED".equals(v.status())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOT_APPROVED", "version " + versionId + " is " + v.status());
            }
            FundingState state = state(versionId);
            if (state == null || !state.disbursementFailed()) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOTHING_TO_RETRY", "no failed disbursement to retry for version " + versionId);
            }
            audit.record("APPLICATION_VERSION", Long.toString(versionId), "DISBURSEMENT_RETRY_REQUESTED", actor, null, null);
        });
        signal(versionId, wf -> wf.retryDisbursement(actor));
        return Map.of("applicationVersionId", versionId, "status", "APPROVED");
    }

    // ------------------------------------------------------------------ guards

    private VersionRow version(long versionId) {
        return versions.find(versionId).orElseThrow(() -> notFound("version " + versionId));
    }

    /** AS-10.7: lock; latest and not terminal, else 409 (VERSION_EXPIRED for an expired one, AS-8.6). */
    private VersionRow guard(long versionId) {
        version(versionId);
        VersionRow v;
        try {
            v = versions.lockLatest(versionId);
        } catch (SupersededException e) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_LATEST_VERSION", "version " + versionId + " is not the latest version");
        }
        if ("EXPIRED".equals(v.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "VERSION_EXPIRED", "version " + versionId
                    + " expired; the dealer resubmits as a new application version");
        }
        if (VersionStatus.valueOf(v.status()).terminal()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "VERSION_TERMINAL", "version " + versionId + " is " + v.status());
        }
        return v;
    }

    private static void requireSubmitted(VersionRow v) {
        if ("DRAFT".equals(v.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_SUBMITTED", "version " + v.applicationVersionId() + " is still DRAFT");
        }
    }

    /**
     * The disbursement activity re-checks status and stips under this row lock before it calls the
     * API (AS-8.1); once it has decided to pay, a stip or reject can't stop the call. The workflow
     * reports DISBURSING from scheduling the call until it returns, so writes wait for that.
     */
    private void refuseWhileDisbursing(VersionRow v) {
        FundingState state = state(v.applicationVersionId());
        if (state != null && "DISBURSING".equals(state.step())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "DISBURSEMENT_IN_PROGRESS",
                    "a disbursement for version " + v.applicationVersionId() + " is in progress; try again when it has finished");
        }
    }

    private void transition(VersionRow v, String next, String actor, String reason) {
        versions.setStatus(v, next);
        audit.record("APPLICATION_VERSION", Long.toString(v.applicationVersionId()), "STATUS_CHANGED", actor,
                Map.of("status", v.status()), Map.of("status", next, "reason", reason));
    }

    private FundingState state(long versionId) {
        try {
            return stub(versionId).getState();
        } catch (WorkflowException | io.grpc.StatusRuntimeException e) {
            return null; // no open run
        }
    }

    private void signal(long versionId, Consumer<FundingWorkflow> signal) {
        try {
            signal.accept(stub(versionId));
        } catch (WorkflowException e) {
            // C-11: no resume. The DB change stands; a closed run means the version settled already.
            LOG.warn("version {}: no open workflow to signal ({})", versionId, e.getClass().getSimpleName());
        }
    }

    private FundingWorkflow stub(long versionId) {
        return client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent(env, versionId));
    }

    private String status(long versionId) {
        return version(versionId).status();
    }

    private static void requireText(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "MISSING_" + name.toUpperCase(java.util.Locale.ROOT), name + " is required");
        }
    }

    private static ApiProblem notFound(String what) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "no " + what);
    }
}
