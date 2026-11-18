package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RuleEngine;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * Plan "FundingWorkflow.run": load snapshot -> fan out the check children in parallel ->
 * applyOutcome -> disburse when APPROVED, otherwise wait for funder action.
 * The children run in parallel and independently (FR-04): one failing doesn't affect the others.
 * Signals beyond {@code stipResolved}, expiry and continueAsNew arrive in slice S4.
 */
public class FundingWorkflowImpl implements FundingWorkflow {

    private static final Logger LOG = Workflow.getLogger(FundingWorkflowImpl.class);
    private static final RuleEngine ENGINE = new RuleEngine();

    private final ActivityOptionsConfig options;
    private final Map<Check, CheckOutcome> outcomes = new EnumMap<>(Check.class);
    private final Map<Check, Integer> attempts = new EnumMap<>(Check.class);
    private String step = "STARTING";
    private String status = "PENDING_REVIEW";
    private int wakeups;

    public FundingWorkflowImpl(ActivityOptionsConfig options) {
        this.options = options;
    }

    @Override
    public FundingResult run(FundingInput in) {
        long id = in.applicationVersionId();
        TaskQueues q = in.taskQueues();
        FundingActivities db = Workflow.newActivityStub(FundingActivities.class, options.db(q.validation()));
        DisbursementActivities disbursement = Workflow.newActivityStub(DisbursementActivities.class, options.external(q.external()));

        step = "LOADING";
        VersionView view = db.loadVersionSnapshot(id);
        if (!view.latest()) {
            return superseded();
        }
        Set<Check> checks = new TreeSet<>(view.enabledChecks());
        checks.retainAll(ENGINE.implementedChecks());

        step = "VALIDATING";
        runChecks(checks, view, q, db);

        step = "APPLYING";
        status = db.applyOutcome(id, outcomes);
        while (true) {
            if ("SUPERSEDED".equals(status)) {
                return superseded();
            }
            if ("APPROVED".equals(status)) {
                step = "DISBURSING";
                String reference = null;
                try {
                    reference = disbursement.disburse(id);
                } catch (ActivityFailure e) {
                    LOG.warn("disbursement failed for version {}; staying APPROVED until a funder retries (C-8)", id);
                }
                if (reference != null) {
                    status = db.markFunded(id);
                    if ("FUNDED".equals(status)) {
                        step = "DONE";
                        return new FundingResult("FUNDED");
                    }
                    continue;
                }
            }
            step = "WAITING_FOR_FUNDER";
            int seen = wakeups;
            Workflow.await(() -> wakeups > seen);
            step = "APPLYING";
            status = db.applyOutcome(id, outcomes);
        }
    }

    private void runChecks(Set<Check> checks, VersionView view, TaskQueues q, FundingActivities db) {
        String parentId = Workflow.getInfo().getWorkflowId();
        Map<Check, Promise<CheckOutcome>> running = new LinkedHashMap<>();
        for (Check check : checks) {
            int attempt = attempts.merge(check, 1, Integer::sum);
            ChildWorkflowOptions opts = ChildWorkflowOptions.newBuilder()
                    .setWorkflowId(WorkflowIds.child(parentId, check, attempt))
                    .setTaskQueue(q.validation())
                    .build();
            CheckInput input = new CheckInput(check, attempt, view.snapshot(), view.config());
            running.put(check, start(check, opts, input));
        }
        running.forEach((check, p) -> {
            try {
                outcomes.put(check, p.get());
            } catch (ChildWorkflowFailure e) {
                // Retries exhausted inside the child: a technical failure, not a business result (FR-04).
                CheckOutcome error = CheckOutcome.error(check, "check failed technically: " + rootMessage(e));
                outcomes.put(check, error);
                try {
                    db.recordOutcome(view.snapshot().applicationVersionId(), error, attempts.get(check));
                } catch (ActivityFailure ignored) {
                    LOG.warn("could not record ERROR result for {}", check);
                }
            }
        });
    }

    private static Promise<CheckOutcome> start(Check check, ChildWorkflowOptions opts, CheckInput input) {
        return switch (check) {
            case TILA -> Async.function(Workflow.newChildWorkflowStub(TilaCheckWorkflow.class, opts)::run, input);
            case PRICE -> Async.function(Workflow.newChildWorkflowStub(PriceParityWorkflow.class, opts)::run, input);
            case VIN -> Async.function(Workflow.newChildWorkflowStub(VinConsistencyWorkflow.class, opts)::run, input);
            case GAP -> Async.function(Workflow.newChildWorkflowStub(GapValidationWorkflow.class, opts)::run, input);
            default -> throw new IllegalStateException("no child workflow for " + check);
        };
    }

    private FundingResult superseded() {
        step = "DONE";
        status = "SUPERSEDED";
        return new FundingResult("SUPERSEDED");
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage();
    }

    @Override
    public void stipResolved() {
        wakeups++;
    }

    @Override
    public FundingState getState() {
        Map<Check, Outcome> o = new EnumMap<>(Check.class);
        outcomes.forEach((k, v) -> o.put(k, v.outcome()));
        return new FundingState(step, status, o, new EnumMap<>(attempts));
    }
}
