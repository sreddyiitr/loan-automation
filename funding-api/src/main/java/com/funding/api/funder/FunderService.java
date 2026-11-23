package com.funding.api.funder;

import com.funding.api.ApiProblem;
import com.funding.api.auth.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.domain.Check;
import com.funding.domain.Outcome;
import com.funding.domain.Role;
import com.funding.domain.routing.Permissions;
import com.funding.domain.routing.Permissions.Action;
import com.funding.domain.routing.WorkRouting;
import com.funding.domain.RulesConfig;
import com.funding.domain.rules.RiscCompleteness;
import com.funding.domain.StipCodes;
import com.funding.domain.status.StipWaivers;
import com.funding.domain.status.VersionStatus;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.DisbursementRepository;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String env;
    private final TransactionTemplate tx;
    private final WorkflowClient client;
    private final RulesConfig rules;
    private final VersionRepository versions;
    private final StipRepository stips;
    private final AuditRepository audit;
    private final SnapshotRepository snapshots;
    private final FunderQueries queries;
    private final DisbursementRepository disbursements;
    private final Duration expireAfter;

    /** Workflow steps with nothing running: every other step means checks, a re-run or a payment is under way (AS-2.2). */
    private static final Set<String> IDLE_STEPS = Set.of("WAITING_FOR_FUNDER", "DONE");

    public FunderService(@Value("${funding.env}") String env, JdbcClient jdbc, TransactionTemplate tx, WorkflowClient client,
                         RulesConfig rules, @Value("${funding.workflow.expire-after}") Duration expireAfter) {
        this.env = env;
        this.expireAfter = expireAfter;
        this.disbursements = new DisbursementRepository(jdbc);
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

    public Map<String, Object> overview(String loanApplicationId, Actor viewer) {
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
        // Spec 002 T103 (AS-2.1, AS-2.2, AS-2.5; plan §5)
        FundingState state = state(v.applicationVersionId());
        String step = state == null ? null : state.step();
        m.put("expiresAt", expiresAt(v));
        m.put("workflowId", WorkflowIds.parent(env, v.applicationVersionId()));
        m.put("workflowStep", step);
        m.put("inProgress", step != null && !IDLE_STEPS.contains(step));
        m.put("payment", payment(v.applicationVersionId(), step));
        // Spec 002 T205 (FR-05b, AS-11.2): who the deal is routed to, and what this viewer may do to it,
        // from the same Permissions the writes use, so the screen and the API can't disagree.
        WorkRouting.Deal deal = routingFacts(v);
        m.put("workKinds", WorkRouting.kinds(deal).stream().map(Enum::name).toList());
        m.put("routedTo", WorkRouting.route(deal).map(Enum::name).orElse(null));
        m.put("viewer", Map.of("id", viewer.id(), "role", viewer.role().name()));
        List<Map<String, Object>> allowed = new ArrayList<>();
        Permissions.all(viewer.role(), viewer.id(), deal, v.assignedFunder()).forEach((action, d) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("action", action.name());
            a.put("allowed", d.allowed());
            a.put("reason", d.reason());
            allowed.add(a);
        });
        m.put("allowedActions", allowed);
        return m;
    }

    /** AS-8.6: the workflow's own deadline. None before submission, and none once paid or rejected. */
    private OffsetDateTime expiresAt(VersionRow v) {
        if (v.submittedAt() == null || "FUNDED".equals(v.status()) || "REJECTED".equals(v.status())) {
            return null;
        }
        return v.submittedAt().plus(expireAfter);
    }

    /** AS-2.1: not started, in flight, paid (reference and amount), failed, or outcome unknown (C-8). */
    private Map<String, Object> payment(long versionId, String step) {
        Map<String, Object> p = new LinkedHashMap<>();
        Optional<DisbursementRepository.Disbursement> d = disbursements.find(versionId);
        if (d.isEmpty()) {
            p.put("state", "NOT_STARTED");
            return p;
        }
        p.put("state", switch (d.get().status()) {
            case "COMPLETED" -> "PAID";
            case "FAILED" -> "FAILED";
            default -> "DISBURSING".equals(step) ? "IN_FLIGHT" : "UNKNOWN";   // REQUESTED
        });
        p.put("reference", d.get().reference());
        p.put("amount", d.get().amount());
        return p;
    }

    /**
     * Spec 002 AS-1.8 "My work": {@code mine} is every open deal assigned to the viewer; {@code ready}
     * is every unassigned open deal routed to the viewer's role (for the supervisor, every unassigned
     * deal that needs someone). Each row carries what's holding it, from the same routing facts the
     * permissions use. Most urgent first.
     */
    public List<Map<String, Object>> worklist(String queue, Actor viewer) {
        if (!"mine".equals(queue) && !"ready".equals(queue)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_QUEUE", "queue must be mine or ready, not " + queue);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id : queries.openLatestVersionIds()) {
            VersionRow v = version(id);
            boolean mine = viewer.id().equals(v.assignedFunder());
            if ("mine".equals(queue) != mine || ("ready".equals(queue) && v.assignedFunder() != null)) {
                continue;
            }
            WorkRouting.Deal deal = routingFacts(v);
            Optional<Role> routed = WorkRouting.route(deal);
            if ("ready".equals(queue) && (routed.isEmpty() || (viewer.role() != Role.SUPERVISOR && routed.get() != viewer.role()))) {
                continue;
            }
            rows.add(worklistRow(v, deal, routed));
        }
        rows.sort(java.util.Comparator.comparing((Map<String, Object> r) -> (OffsetDateTime) r.get("expiresAt"),
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(r -> (String) r.get("loanApplicationId")));
        return rows;
    }

    private Map<String, Object> worklistRow(VersionRow v, WorkRouting.Deal deal, Optional<Role> routed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("loanApplicationId", v.loanApplicationId());
        r.put("applicationVersionId", v.applicationVersionId());
        r.put("versionNumber", v.versionNumber());
        r.put("status", v.status());
        r.put("dealershipName", v.dealershipName());
        r.put("assignedFunder", v.assignedFunder());
        r.put("expiresAt", expiresAt(v));
        r.put("workKinds", WorkRouting.kinds(deal).stream().map(Enum::name).toList());
        r.put("routedTo", routed.map(Enum::name).orElse(null));
        r.put("earlierAssignees", queries.earlierAssignees(v.loanApplicationId(), v.versionNumber()));   // AS-12.3
        // what's holding it (AS-1.8): pending stips, checks that couldn't finish or need review, the RISC, the payment
        r.put("pendingStips", deal.pendingStips().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.code());
            m.put("check", s.check() == null ? null : s.check().name());
            return m;
        }).toList());
        r.put("checksNotPassed", deal.results().stream()
                .filter(x -> x.outcome() == Outcome.ERROR || (x.check() == Check.INCOME && x.outcome() == Outcome.NEEDS_REVIEW))
                .map(x -> Map.of("check", x.check().name(), "outcome", x.outcome().name())).toList());
        r.put("riscIncomplete", deal.riscIncomplete());
        r.put("payment", deal.payment().name());
        return r;
    }

    public List<Map<String, Object>> versions(String loanApplicationId) {
        List<Map<String, Object>> rows = queries.versions(loanApplicationId);
        if (rows.isEmpty()) {
            throw notFound("application " + loanApplicationId);
        }
        return rows;
    }

    /** AS-10.2, with each stip's {@code waivable} (AS-8.10), so the dashboard never offers Waive on a RISC stip (spec 002 AS-3.3). */
    public List<Map<String, Object>> stips(long versionId) {
        version(versionId);
        return queries.stips(versionId).stream().map(r -> {
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.put("waivable", StipWaivers.waivable((String) r.get("stip_code"), (String) r.get("description")));
            return out;
        }).toList();
    }

    /** Spec 002 D-19: the codes a funder may add, in catalog order. */
    public List<Map<String, Object>> stipCodes() {
        return StipCodes.catalog().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("description", c.description());
            m.put("raisedBy", c.raisedBy().stream().sorted().map(Enum::name).toList());
            m.put("manualOnly", c.manualOnly());
            m.put("waivable", c.waivable().name());
            return m;
        }).toList();
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
    public Map<String, Object> resolveStip(long versionId, long stipId, String status, String reason, Actor who) {
        String actor = who.id();
        requireText(reason, "reason");
        if (!"SATISFIED".equals(status) && !"WAIVED".equals(status)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status must be SATISFIED or WAIVED, not " + status);
        }
        StipRepository.Stip resolved = tx.execute(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.RESOLVE_STIP, v);
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
            audited(who).record("STIPULATION", Long.toString(stipId), status, actor, Map.of("status", "PENDING"),
                    Map.of("status", status, "note", reason));
            return r;
        });
        signal(versionId, FundingWorkflow::stipResolved);
        return Map.of("stipId", resolved.stipId(), "status", resolved.status(), "versionStatus", status(versionId));
    }

    /** AS-10.8: a funder's stip ({@code validation_check = NULL}); PENDING_REVIEW or APPROVED (not yet disbursing) -> STIPULATED. */
    public Map<String, Object> addManualStip(long versionId, String code, String description, Actor who) {
        String actor = who.id();
        requireText(code, "code");
        requireText(description, "description");
        if (StipCodes.find(code).isEmpty()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "UNKNOWN_STIP_CODE", code + " isn't a stip code; see GET /api/v1/stip-codes");
        }
        Long stipId = tx.execute(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.ADD_STIP, v);
            requireSubmitted(v);
            if ("APPROVED".equals(v.status())) {
                refuseWhileDisbursing(v);
            }
            Long id = stips.createManual(versionId, code, description);
            if (id == null) {
                throw new ApiProblem(HttpStatus.CONFLICT, "STIP_ALREADY_PENDING", "a manual " + code + " is already PENDING");
            }
            audited(who).record("STIPULATION", Long.toString(id), "CREATED", actor, null,
                    Map.of("code", code, "check", "MANUAL", "status", "PENDING"));
            if (!"STIPULATED".equals(v.status())) {
                transition(who, v, "STIPULATED", "MANUAL_STIP");
            }
            return id;
        });
        signal(versionId, FundingWorkflow::stipResolved);
        return Map.of("stipId", stipId, "status", "PENDING", "versionStatus", status(versionId));
    }

    /** AS-10.3. */
    /** AS-10.3 / spec 002 AS-2.4: reassign, or unassign with {@code funderId = null}. A blank id is refused. */
    public Map<String, Object> reassign(long versionId, String funderId, Actor who) {
        String actor = who.id();
        if (funderId != null) {
            requireText(funderId, "funderId");
        }
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.REASSIGN, v);
            versions.setAssignee(versionId, funderId);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("assignedFunder", v.assignedFunder());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("assignedFunder", funderId);
            audited(who).record("APPLICATION_VERSION", Long.toString(versionId), "ASSIGNEE_CHANGED", actor, before, after);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationVersionId", versionId);
        result.put("assignedFunder", funderId);
        return result;
    }

    /**
     * Spec 002 AS-2.6: assign the version to the caller, only if nobody has it. Checked under the
     * row lock, so two funders taking the same deal can't both win. Only a deal routed to the caller (AS-11.2).
     */
    public Map<String, Object> take(long versionId, Actor who) {
        String actor = who.id();
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.TAKE, v);
            if (actor.equals(v.assignedFunder())) {
                return;   // already yours: nothing changes, nothing to audit
            }
            if (v.assignedFunder() != null) {
                throw new ApiProblem(HttpStatus.CONFLICT, "ALREADY_ASSIGNED",
                        "version " + versionId + " is already assigned to " + v.assignedFunder());
            }
            versions.setAssignee(versionId, actor);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("assignedFunder", null);
            audited(who).record("APPLICATION_VERSION", Long.toString(versionId), "ASSIGNEE_CHANGED", actor, before,
                    Map.of("assignedFunder", actor, "reason", "TAKEN"));
        });
        return Map.of("applicationVersionId", versionId, "assignedFunder", actor);
    }

    /** AS-8.7 (override for checks in ERROR) and AS-8.10 (complete RISC, TILA PASS). */
    public Map<String, Object> approve(long versionId, Actor who) {
        String actor = who.id();
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.APPROVE, v);
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
            transition(who, v, "APPROVED", "MANUAL_APPROVE");
        });
        signal(versionId, wf -> wf.approve(actor));
        return Map.of("applicationVersionId", versionId, "status", "APPROVED");
    }

    /** AS-8.5. */
    public Map<String, Object> reject(long versionId, String reason, Actor who) {
        String actor = who.id();
        requireText(reason, "reason");
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.REJECT, v);
            requireSubmitted(v);
            if ("APPROVED".equals(v.status())) {
                refuseWhileDisbursing(v);
            }
            transition(who, v, "REJECTED", reason);
        });
        signal(versionId, wf -> wf.reject(actor, reason));
        return Map.of("applicationVersionId", versionId, "status", "REJECTED");
    }

    /** AS-8.4: re-run the given checks, or by default the ones currently in ERROR. STIPULATED -> PENDING_REVIEW. */
    public Map<String, Object> rerun(long versionId, List<Check> requested, Actor who) {
        String actor = who.id();
        List<Check> checks = tx.execute(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.RERUN, v);
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
            audited(who).record("APPLICATION_VERSION", Long.toString(versionId), "RERUN_REQUESTED", actor, null,
                    Map.of("checks", c.stream().map(Enum::name).toList()));
            if ("STIPULATED".equals(v.status())) {
                transition(who, v, "PENDING_REVIEW", "RERUN_STARTED");
            }
            return c;
        });
        signal(versionId, wf -> wf.rerunChecks(checks));
        return Map.of("applicationVersionId", versionId, "checks", checks.stream().map(Enum::name).toList());
    }

    /** C-8: only an APPROVED version whose workflow reports a failed disbursement. */
    public Map<String, Object> retryDisbursement(long versionId, Actor who) {
        String actor = who.id();
        tx.executeWithoutResult(s -> {
            VersionRow v = guard(versionId);
            authorize(who, Action.RETRY_PAYMENT, v);
            if (!"APPROVED".equals(v.status())) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOT_APPROVED", "version " + versionId + " is " + v.status());
            }
            FundingState state = state(versionId);
            if (state == null || !state.disbursementFailed()) {
                throw new ApiProblem(HttpStatus.CONFLICT, "NOTHING_TO_RETRY", "no failed disbursement to retry for version " + versionId);
            }
            audited(who).record("APPLICATION_VERSION", Long.toString(versionId), "DISBURSEMENT_RETRY_REQUESTED", actor, null, null);
        });
        signal(versionId, wf -> wf.retryDisbursement(actor));
        return Map.of("applicationVersionId", versionId, "status", "APPROVED");
    }

    // ------------------------------------------------------------------ permissions (spec 002 D2, T204)

    /**
     * AS-11.2, FR-11: may this user do this to the version? Asked right after the row lock, before the
     * business guards, so a refusal names the real reason (403 problem+json, AS-11.3). The supervisor
     * may do everything, so their routing facts aren't even read.
     */
    private void authorize(Actor who, Action action, VersionRow v) {
        if (who.role() == Role.SUPERVISOR) {
            return;
        }
        WorkRouting.Deal deal = routingFacts(v);
        Permissions.Decision d = Permissions.check(who.role(), who.id(), action, deal, v.assignedFunder());
        if (!d.allowed()) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, d.reason(), refusal(d.reason(), action, v, deal, who));
        }
    }

    private static String refusal(String reason, Action action, VersionRow v, WorkRouting.Deal deal, Actor who) {
        long id = v.applicationVersionId();
        return switch (reason) {
            case Permissions.NOT_YOUR_SPECIALTY -> "version " + id + " is routed to "
                    + WorkRouting.route(deal).map(Enum::name).orElse("nobody (nothing needs a specialist)")
                    + " (kinds of work " + WorkRouting.kinds(deal) + "); you are " + who.role();
            case Permissions.NOT_ASSIGNED_TO_YOU -> "version " + id + " is assigned to "
                    + (v.assignedFunder() == null ? "nobody; take it first" : v.assignedFunder());
            default -> action + " is the supervisor's; you are " + who.role();
        };
    }

    /**
     * What routing reads about this version (spec 002 §3), from the database under the caller's lock:
     * the PENDING stips with their check (null for a manual stip) and that check's flagged fields; the
     * current results; whether the RISC is incomplete (recomputed as approve does, AS-8.10); and, for
     * an APPROVED version, the payment's state.
     */
    WorkRouting.Deal routingFacts(VersionRow v) {
        long id = v.applicationVersionId();
        VersionStatus status = VersionStatus.valueOf(v.status());
        List<WorkRouting.CurrentResult> results = new ArrayList<>();
        Map<String, List<String>> flagged = new LinkedHashMap<>();
        for (Map<String, Object> r : queries.results(id, false)) {
            String check = (String) r.get("validation_check");
            results.add(new WorkRouting.CurrentResult(Check.valueOf(check), Outcome.valueOf((String) r.get("outcome"))));
            flagged.put(check, flaggedFields((String) r.get("details")));
        }
        List<WorkRouting.PendingStip> pending = new ArrayList<>();
        for (Map<String, Object> st : queries.stips(id)) {
            if (!"PENDING".equals(st.get("status"))) {
                continue;
            }
            String check = (String) st.get("validation_check");
            pending.add(new WorkRouting.PendingStip((String) st.get("stip_code"), check == null ? null : Check.valueOf(check),
                    check == null ? List.of() : flagged.getOrDefault(check, List.of())));
        }
        boolean riscIncomplete = status == VersionStatus.PENDING_REVIEW
                && !snapshots.load(id).map(l -> RiscCompleteness.problems(l.snapshot(), rules)).orElse(List.of()).isEmpty();
        WorkRouting.PaymentState payment = WorkRouting.PaymentState.NOT_STARTED;
        if (status == VersionStatus.APPROVED) {
            FundingState state = state(id);
            payment = WorkRouting.PaymentState.valueOf((String) payment(id, state == null ? null : state.step()).get("state"));
        }
        return new WorkRouting.Deal(status, pending, results, riscIncomplete, payment);
    }

    private static List<String> flaggedFields(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            JSON.readTree(detailsJson).path("flagged_fields").forEach(f -> out.add(f.asText()));
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    /** AS-11.4: every funder write's audit entry carries the actor's role in its after_state. */
    private AuditWithRole audited(Actor who) {
        return new AuditWithRole(audit, who.role());
    }

    private record AuditWithRole(AuditRepository audit, Role role) {
        void record(String entityType, String entityId, String action, String actor, Object before, Object after) {
            Map<String, Object> withRole = new LinkedHashMap<>();
            if (after instanceof Map<?, ?> m) {
                m.forEach((k, val) -> withRole.put(String.valueOf(k), val));
            }
            withRole.put("role", role.name());
            audit.record(entityType, entityId, action, actor, before, withRole);
        }
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

    private void transition(Actor who, VersionRow v, String next, String reason) {
        versions.setStatus(v, next);
        audited(who).record("APPLICATION_VERSION", Long.toString(v.applicationVersionId()), "STATUS_CHANGED", who.id(),
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
