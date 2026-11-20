package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RuleEngine;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * Plan "FundingWorkflow.run": load snapshot -> fan out the check children in parallel ->
 * applyOutcome -> disburse when APPROVED, otherwise wait for funder action until the version
 * expires (AS-8.6). The children run in parallel and independently (FR-04): one failing doesn't
 * affect the others. Funder actions are DB writes made by the API, then signalled here; after any
 * signal the workflow re-runs requested checks and re-settles the status with {@code applyOutcome}.
 * A new latest document re-runs every check (AS-1.8); a newer submitted version supersedes the run,
 * which then cancels its running checks and completes {@code SUPERSEDED} (AS-9.2, AS-9.5).
 */
public class FundingWorkflowImpl implements FundingWorkflow {

    private static final Logger LOG = Workflow.getLogger(FundingWorkflowImpl.class);
    private static final RuleEngine ENGINE = new RuleEngine();
    /** {@code Workflow.getVersion} change id for the INCOME child (slice S3). */
    static final String INCOME_CHECK_CHANGE = "income-check";
    /** Change id for S4's signal loop: expiry timer, explicit disbursement retry, re-runs, continueAsNew. */
    static final String FUNDER_SIGNALS_CHANGE = "funder-signals";
    /**
     * Change id (S5 review): a re-run requested while checks are running (a new document, or a
     * funder's re-run) is done before the status is settled, so a version is never approved, and
     * paid, on outcomes from documents that have since been replaced.
     */
    static final String RERUN_BEFORE_SETTLING_CHANGE = "rerun-before-settling";
    /** Plan step 7: continue as new once a run has handled this many signals. */
    public static final int MAX_SIGNALS_PER_RUN = 1000;

    private final ActivityOptionsConfig options;
    private final int maxSignalsPerRun;
    private final Map<Check, CheckOutcome> outcomes = new EnumMap<>(Check.class);
    private final Map<Check, Integer> attempts = new EnumMap<>(Check.class);
    private final Set<Check> rerunRequested = new TreeSet<>();
    private String step = "STARTING";
    private String status = "PENDING_REVIEW";
    private int wakeups;
    /** Wake-ups already acted on: a signal that arrives while the run is busy is handled at the next wait (S4). */
    private int handled;
    private int signals;
    private long expiresAt;
    private boolean disbursementFailed;
    private boolean retryRequested;
    /** S5: every check re-runs at the next wake-up (a new latest document, AS-1.8). */
    private boolean rerunAll;
    private final Set<Long> documentsHandled = new TreeSet<>();
    private boolean supersedeRequested;

    public FundingWorkflowImpl(ActivityOptionsConfig options) {
        this(options, MAX_SIGNALS_PER_RUN);
    }

    /** {@code maxSignalsPerRun} is fixed per deployment; tests lower it to exercise continueAsNew. */
    public FundingWorkflowImpl(ActivityOptionsConfig options, int maxSignalsPerRun) {
        this.options = options;
        this.maxSignalsPerRun = maxSignalsPerRun;
    }

    @Override
    public FundingResult run(FundingInput in) {
        long id = in.applicationVersionId();
        TaskQueues q = in.taskQueues();
        FundingActivities db = Workflow.newActivityStub(FundingActivities.class, options.db(q.validation()));
        DisbursementActivities disbursement = Workflow.newActivityStub(DisbursementActivities.class, options.external(q.external()));
        // NFR-07: runs started before S4 keep the S1-S3 loop (no expiry timer, disbursement retried on any wake-up).
        boolean s4 = Workflow.getVersion(FUNDER_SIGNALS_CHANGE, Workflow.DEFAULT_VERSION, 1) >= 1;
        boolean rerunFirst = s4 && Workflow.getVersion(RERUN_BEFORE_SETTLING_CHANGE, Workflow.DEFAULT_VERSION, 1) >= 1;

        if (FundingInput.CONTINUE.equals(in.mode()) && in.carried() != null) {
            FundingCarry c = in.carried();
            outcomes.putAll(c.outcomes());
            attempts.putAll(c.attempts());
            expiresAt = c.expiresAtEpochMillis();
            disbursementFailed = c.disbursementFailed();
            if (c.documentsHandled() != null) {
                documentsHandled.addAll(c.documentsHandled());
            }
            step = "APPLYING";
            status = db.applyOutcome(id, outcomes);
        } else {
            step = "LOADING";
            VersionView view = db.loadVersionSnapshot(id);
            if (!view.latest()) {
                return superseded();
            }
            expiresAt = s4 ? view.expiresAtEpochMillis() : 0L; // computed once, carried through continueAsNew
            Set<Check> checks = new TreeSet<>(view.enabledChecks());
            checks.retainAll(ENGINE.implementedChecks());
            // NFR-07: INCOME was enabled in config before its rule existed (S3). A run started before
            // then never scheduled an INCOME child, so on replay it must not schedule one now. Adding a
            // rule for another enabled check needs the same kind of guard.
            if (Workflow.getVersion(INCOME_CHECK_CHANGE, Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
                checks.remove(Check.INCOME);
            }

            step = "VALIDATING";
            if (!runChecks(checks, view, q, db)) {
                return superseded();
            }

            if (!(rerunFirst && rerunPending())) { // else: re-run first, settle after (the wait below returns at once)
                step = "APPLYING";
                status = db.applyOutcome(id, outcomes);
            }
        }

        while (true) {
            if ("SUPERSEDED".equals(status) || supersedeRequested) {
                return superseded();
            }
            if (isTerminal(status)) { // pre-S4 histories never had a terminal applyOutcome: safe for them too
                return done(status);
            }
            if ("APPROVED".equals(status) && (!s4 || !disbursementFailed)) {
                step = "DISBURSING";
                String reference = null;
                try {
                    // Never cancelled by expiry: the deadline is checked only after this returns (AS-8.6).
                    reference = disbursement.disburse(id);
                } catch (ActivityFailure e) {
                    disbursementFailed = true;
                    LOG.warn("disbursement failed for version {}; staying APPROVED until a funder retries (C-8)", id);
                }
                if (reference != null) {
                    status = db.markFunded(id);
                    if ("FUNDED".equals(status)) {
                        return done("FUNDED");
                    }
                    continue;
                }
            }
            if (s4 && signals >= maxSignalsPerRun && wakeups == handled) { // never drop an unhandled signal
                step = "CONTINUING";
                Workflow.continueAsNew(new FundingInput(id, FundingInput.CONTINUE, q,
                        new FundingCarry(new EnumMap<>(outcomes), new EnumMap<>(attempts), expiresAt, disbursementFailed,
                                new TreeSet<>(documentsHandled))));
            }

            step = "WAITING_FOR_FUNDER";
            // Pre-S4 runs waited for a signal newer than the wait itself; S4 handles any not yet acted on.
            int seen = s4 ? handled : wakeups;
            if (s4 && expiresAt > 0) {
                long left = expiresAt - Workflow.currentTimeMillis();
                boolean woke = left > 0 && Workflow.await(Duration.ofMillis(left), () -> wakeups > seen);
                if (!woke) {
                    step = "EXPIRING";
                    status = db.expire(id);
                    continue; // EXPIRED (or a terminal status that won the race) ends the run above
                }
            } else {
                Workflow.await(() -> wakeups > seen);
            }

            handled = wakeups;
            if (supersedeRequested) {
                continue; // completes SUPERSEDED above, without touching the status
            }
            if (s4 && (rerunAll || !rerunRequested.isEmpty())) {
                Set<Check> rerun = new TreeSet<>(rerunAll ? outcomes.keySet() : rerunRequested);
                rerunRequested.clear();
                rerunAll = false;
                rerun.retainAll(outcomes.keySet()); // only checks this run has (an S3 replay has no INCOME, for example)
                if (!rerun.isEmpty()) {
                    step = "LOADING";
                    VersionView view = db.loadVersionSnapshot(id);
                    if (!view.latest()) {
                        return superseded();
                    }
                    step = "VALIDATING";
                    if (!runChecks(rerun, view, q, db)) {
                        return superseded();
                    }
                    if (rerunFirst && rerunPending()) {
                        continue; // another document or re-run arrived meanwhile: run it before settling
                    }
                }
            }
            if (retryRequested) {
                retryRequested = false;
                disbursementFailed = false;
            }
            step = "APPLYING";
            status = db.applyOutcome(id, outcomes);
            if (!"APPROVED".equals(status)) {
                disbursementFailed = false; // a later approval gets a fresh attempt, not a stale "failed"
            }
        }
    }

    /** A re-run was requested and not yet started. */
    private boolean rerunPending() {
        return rerunAll || !rerunRequested.isEmpty();
    }

    private static boolean isTerminal(String s) {
        return "FUNDED".equals(s) || "REJECTED".equals(s) || "EXPIRED".equals(s);
    }

    private FundingResult done(String result) {
        step = "DONE";
        status = result;
        return new FundingResult(result);
    }

    /**
     * Runs the checks as parallel children and collects their outcomes in check order. Returns false
     * if a {@code supersede} arrived first: the children still running are then cancelled (AS-9.2).
     * Without a supersede the commands are exactly those of the S1-S4 {@code p.get()} loop, so
     * recorded histories replay unchanged.
     */
    private boolean runChecks(Set<Check> checks, VersionView view, TaskQueues q, FundingActivities db) {
        if (supersedeRequested) {
            return false; // superseded while loading: start nothing
        }
        String parentId = Workflow.getInfo().getWorkflowId();
        Map<Check, Promise<CheckOutcome>> running = new LinkedHashMap<>();
        CancellationScope children = Workflow.newCancellationScope(() -> {
            for (Check check : checks) {
                int attempt = attempts.merge(check, 1, Integer::sum);
                ChildWorkflowOptions opts = ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(WorkflowIds.child(parentId, check, attempt))
                        .setTaskQueue(q.validation())
                        .build();
                CheckInput input = new CheckInput(check, attempt, view.snapshot(), view.config(), q.external());
                running.put(check, start(check, opts, input));
            }
        });
        children.run();
        for (Map.Entry<Check, Promise<CheckOutcome>> child : running.entrySet()) {
            Check check = child.getKey();
            Promise<CheckOutcome> p = child.getValue();
            Workflow.await(() -> p.isCompleted() || supersedeRequested);
            if (supersedeRequested) {
                children.cancel("superseded by a newer application version");
                return false;
            }
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
        }
        return true;
    }

    private static Promise<CheckOutcome> start(Check check, ChildWorkflowOptions opts, CheckInput input) {
        return switch (check) {
            case TILA -> Async.function(Workflow.newChildWorkflowStub(TilaCheckWorkflow.class, opts)::run, input);
            case PRICE -> Async.function(Workflow.newChildWorkflowStub(PriceParityWorkflow.class, opts)::run, input);
            case VIN -> Async.function(Workflow.newChildWorkflowStub(VinConsistencyWorkflow.class, opts)::run, input);
            case GAP -> Async.function(Workflow.newChildWorkflowStub(GapValidationWorkflow.class, opts)::run, input);
            case INCOME -> Async.function(Workflow.newChildWorkflowStub(IncomeVerificationWorkflow.class, opts)::run, input);
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
        wake();
    }

    @Override
    public void approve(String actor) {
        wake();
    }

    @Override
    public void reject(String actor, String reason) {
        wake();
    }

    @Override
    public void rerunChecks(List<Check> checks) {
        if (checks != null) {
            rerunRequested.addAll(checks);
        }
        wake();
    }

    @Override
    public void retryDisbursement(String actor) {
        retryRequested = true;
        wake();
    }

    @Override
    public void documentsUpdated(Long documentId) {
        if (documentId != null && !documentsHandled.add(documentId)) {
            return; // a redelivered event for a document this run already re-ran for
        }
        rerunAll = true;
        wake();
    }

    @Override
    public void supersede() {
        supersedeRequested = true;
        wake();
    }

    private void wake() {
        wakeups++;
        signals++;
    }

    @Override
    public FundingState getState() {
        Map<Check, Outcome> o = new EnumMap<>(Check.class);
        outcomes.forEach((k, v) -> o.put(k, v.outcome()));
        return new FundingState(step, status, o, new EnumMap<>(attempts), expiresAt, disbursementFailed);
    }
}
