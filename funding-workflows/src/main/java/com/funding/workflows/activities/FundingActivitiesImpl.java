package com.funding.workflows.activities;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.DocumentSnapshot;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.StipRequest;
import com.funding.domain.status.VersionStatus;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.ResultRepository;
import com.funding.persistence.SnapshotRepository;
import com.funding.persistence.StipRepository;
import com.funding.persistence.SupersededException;
import com.funding.persistence.VersionRepository;
import com.funding.persistence.VersionRow;
import com.funding.workflows.FundingActivities;
import com.funding.workflows.VersionView;
import com.funding.workflows.WorkflowIds;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import com.funding.domain.rules.RiscCompleteness;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB activities. Every write runs in one transaction that first locks the version row and checks
 * it is still latest (AS-9.3); a superseded version is reported, never written. Status changes are
 * audited with actor {@code system:<workflowId>} (FR-10).
 */
public class FundingActivitiesImpl implements FundingActivities {

    private static final Logger LOG = LoggerFactory.getLogger(FundingActivitiesImpl.class);

    private final String env;
    private final TransactionTemplate tx;
    private final SnapshotRepository snapshots;
    private final VersionRepository versions;
    private final ResultRepository results;
    private final StipRepository stips;
    private final AuditRepository audit;
    private final Supplier<RulesConfig> config;
    /**
     * T606: every check always runs. The set still travels in {@link VersionView} because it is part
     * of recorded activity results: a run from S1, when only TILA was enabled, must replay with that.
     */
    private static final Set<Check> ALL_CHECKS = java.util.EnumSet.allOf(Check.class);
    private final Duration expireAfter;

    /** @param expireAfter {@code funding.workflow.expire-after}: how long after {@code submitted_at} a version expires (AS-8.6) */
    public FundingActivitiesImpl(String env, JdbcClient jdbc, TransactionTemplate tx, Supplier<RulesConfig> config, Duration expireAfter) {
        this.env = env;
        this.tx = tx;
        this.snapshots = new SnapshotRepository(jdbc);
        this.versions = new VersionRepository(jdbc);
        this.results = new ResultRepository(jdbc);
        this.stips = new StipRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
        this.config = config;
        this.expireAfter = expireAfter;
    }

    @Override
    public VersionView loadVersionSnapshot(long versionId) {
        mdc(versionId);
        SnapshotRepository.Loaded l = snapshots.load(versionId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure("no application version " + versionId, "NotFound"));
        VersionRow v = l.version();
        long submitted = v.submittedAt() == null ? 0L : v.submittedAt().toInstant().toEpochMilli();
        return new VersionView(l.snapshot(), config.get(), ALL_CHECKS, v.status(), v.latest(), submitted,
                submitted == 0L ? 0L : submitted + expireAfter.toMillis());
    }

    @Override
    public boolean recordOutcome(long versionId, CheckOutcome outcome, int attempt) {
        mdc(versionId);
        return Boolean.TRUE.equals(tx.execute(s -> {
            VersionRow v;
            try {
                v = versions.lockLatest(versionId);
            } catch (SupersededException e) {
                LOG.info("version {} superseded; not recording {} result", versionId, outcome.check());
                return false;
            }
            if (VersionStatus.valueOf(v.status()).terminal()) {
                // e.g. rejected while its checks were still running: no new results or stips on a closed version
                LOG.info("version {} is {}; not recording {} result", versionId, v.status(), outcome.check());
                return false;
            }
            Map<String, Long> docIds = documentIds(versionId);
            results.record(versionId, outcome, attempt, runId(), docIds);
            Long docId = docIds.get(StipRepository.triggeringDocumentType(outcome.check()));
            for (StipRequest stip : outcome.stips()) {
                Long stipId = stips.createIfAbsent(versionId, outcome.check(), stip, docId);
                if (stipId != null) {
                    audit.record("STIPULATION", Long.toString(stipId), "CREATED", actor(versionId), null,
                            Map.of("code", stip.code(), "check", outcome.check().name(), "status", "PENDING"));
                }
            }
            return true;
        }));
    }

    @Override
    public String applyOutcome(long versionId, Map<Check, CheckOutcome> outcomes) {
        mdc(versionId);
        return tx.execute(s -> {
            VersionRow v;
            try {
                v = versions.lockLatest(versionId);
            } catch (SupersededException e) {
                return "SUPERSEDED";
            }
            if (VersionStatus.valueOf(v.status()).terminal()) {
                return v.status();
            }
            String actor = actor(versionId);
            boolean approved = "APPROVED".equals(v.status());
            // AS-8.8: a check that passes now resolves the PENDING stips it raised earlier.
            outcomes.values().stream().filter(o -> o.outcome() == Outcome.PASS).forEach(o ->
                    stips.autoSatisfy(versionId, o.check(), actor, "auto-resolved by re-run").forEach(st ->
                            audit.record("STIPULATION", Long.toString(st.stipId()), "SATISFIED", actor,
                                    Map.of("status", "PENDING"), Map.of("status", "SATISFIED", "note", "auto-resolved by re-run"))));
            boolean error = outcomes.values().stream().anyMatch(o -> o.outcome() == Outcome.ERROR);
            boolean pending = !stips.pending(versionId).isEmpty();
            // ERROR holds in PENDING_REVIEW (AS-8.4); any PENDING stip -> STIPULATED (AS-8.2); else APPROVED (AS-8.1, AS-8.3),
            // but only with a complete RISC and TILA PASS (AS-8.10); otherwise held for a funder.
            String next;
            String reason = "VALIDATION_OUTCOME";
            if (approved) {
                // Already APPROVED (automatically, or by a funder's manual approve overriding ERROR, AS-8.7):
                // it stays approved unless a PENDING stip appeared (manual stip, AS-10.8).
                next = pending ? "STIPULATED" : "APPROVED";
                reason = pending ? "MANUAL_STIP" : reason;
            } else if (error) {
                next = "PENDING_REVIEW";
            } else if (pending) {
                next = "STIPULATED";
            } else {
                List<String> incomplete = riscIncomplete(versionId, outcomes);
                next = incomplete.isEmpty() ? "APPROVED" : "PENDING_REVIEW";
                if (!incomplete.isEmpty()) {
                    reason = "RISC_INCOMPLETE " + incomplete;
                    LOG.warn("version {}: not auto-approving, RISC incomplete or TILA not PASS: {}", versionId, incomplete);
                }
            }
            transition(v, next, actor, reason);
            return next;
        });
    }

    @Override
    public String markFunded(long versionId) {
        mdc(versionId);
        return tx.execute(s -> {
            VersionRow v;
            try {
                v = versions.lockLatest(versionId);
            } catch (SupersededException e) {
                return "SUPERSEDED";
            }
            if ("APPROVED".equals(v.status())) {
                transition(v, "FUNDED", actor(versionId), "DISBURSED");
                return "FUNDED";
            }
            return v.status();
        });
    }

    @Override
    public String expire(long versionId) {
        mdc(versionId);
        return tx.execute(s -> {
            VersionRow v;
            try {
                v = versions.lockLatest(versionId);
            } catch (SupersededException e) {
                return "SUPERSEDED";
            }
            if (VersionStatus.valueOf(v.status()).terminal()) {
                return v.status();
            }
            transition(v, "EXPIRED", actor(versionId), "EXPIRED_AFTER_" + iso(expireAfter)); // PENDING stips stay as history
            return "EXPIRED";
        });
    }

    /** {@code P30D} rather than {@link Duration#toString()}'s {@code PT720H}: the audit reason the spec names (AS-8.6). */
    static String iso(Duration d) {
        return d.toSeconds() % 86_400 == 0 && !d.isZero() ? "P" + d.toDays() + "D" : d.toString();
    }

    /** AS-8.10: problems blocking approval; empty when the RISC is complete and TILA passed. */
    private List<String> riscIncomplete(long versionId, Map<Check, CheckOutcome> outcomes) {
        List<String> problems = new java.util.ArrayList<>(snapshots.load(versionId)
                .map(l -> RiscCompleteness.problems(l.snapshot(), config.get())).orElse(List.of("RISC")));
        CheckOutcome tila = outcomes.get(Check.TILA);
        if (tila == null || tila.outcome() != Outcome.PASS) {
            problems.add("TILA not PASS");
        }
        return problems;
    }

    private void transition(VersionRow v, String next, String actor, String reason) {
        if (next.equals(v.status())) {
            return;
        }
        versions.setStatus(v, next); // FR-09: rejects any change spec §6 doesn't list
        audit.record("APPLICATION_VERSION", Long.toString(v.applicationVersionId()), "STATUS_CHANGED", actor,
                Map.of("status", v.status()), Map.of("status", next, "reason", reason));
        LOG.info("version {}: {} -> {} ({})", v.applicationVersionId(), v.status(), next, reason);
    }

    private Map<String, Long> documentIds(long versionId) {
        Map<String, Long> m = new LinkedHashMap<>();
        snapshots.load(versionId).ifPresent(l -> l.snapshot().documents().values()
                .forEach((DocumentSnapshot d) -> m.put(d.type().name(), d.documentId())));
        return m;
    }

    private String actor(long versionId) {
        return WorkflowIds.systemActor(WorkflowIds.parent(env, versionId));
    }

    private static String runId() {
        try {
            return Activity.getExecutionContext().getInfo().getRunId();
        } catch (IllegalStateException | NullPointerException e) {
            return null; // called outside an activity (tests)
        }
    }

    private void mdc(long versionId) {
        MDC.put("applicationVersionId", Long.toString(versionId));
        MDC.put("workflowId", WorkflowIds.parent(env, versionId));
    }
}
