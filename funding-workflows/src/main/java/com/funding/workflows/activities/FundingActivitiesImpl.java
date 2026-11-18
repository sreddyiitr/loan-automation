package com.funding.workflows.activities;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.StipRequest;
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
import java.util.LinkedHashMap;
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
    /** Which document a check's stip points at (triggered_by_document_id). */
    private static final Map<Check, DocumentType> PRIMARY_DOC = Map.of(
            Check.TILA, DocumentType.RISC, Check.PRICE, DocumentType.BUYERS_ORDER,
            Check.VIN, DocumentType.RISC, Check.GAP, DocumentType.GAP_AGREEMENT);

    private final String env;
    private final TransactionTemplate tx;
    private final SnapshotRepository snapshots;
    private final VersionRepository versions;
    private final ResultRepository results;
    private final StipRepository stips;
    private final AuditRepository audit;
    private final Supplier<RulesConfig> config;
    private final Set<Check> enabledChecks;

    public FundingActivitiesImpl(String env, JdbcClient jdbc, TransactionTemplate tx, Supplier<RulesConfig> config,
                                 Set<Check> enabledChecks) {
        this.env = env;
        this.tx = tx;
        this.snapshots = new SnapshotRepository(jdbc);
        this.versions = new VersionRepository(jdbc);
        this.results = new ResultRepository(jdbc);
        this.stips = new StipRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
        this.config = config;
        this.enabledChecks = Set.copyOf(enabledChecks);
    }

    @Override
    public VersionView loadVersionSnapshot(long versionId) {
        mdc(versionId);
        SnapshotRepository.Loaded l = snapshots.load(versionId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure("no application version " + versionId, "NotFound"));
        VersionRow v = l.version();
        return new VersionView(l.snapshot(), config.get(), enabledChecks, v.status(), v.latest(),
                v.submittedAt() == null ? 0L : v.submittedAt().toInstant().toEpochMilli());
    }

    @Override
    public boolean recordOutcome(long versionId, CheckOutcome outcome, int attempt) {
        mdc(versionId);
        return Boolean.TRUE.equals(tx.execute(s -> {
            try {
                versions.lockLatest(versionId);
            } catch (SupersededException e) {
                LOG.info("version {} superseded; not recording {} result", versionId, outcome.check());
                return false;
            }
            Map<String, Long> docIds = documentIds(versionId);
            results.record(versionId, outcome, attempt, runId(), docIds);
            Long docId = docIds.get(PRIMARY_DOC.getOrDefault(outcome.check(), DocumentType.RISC).name());
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
            if (Set.of("FUNDED", "REJECTED", "EXPIRED").contains(v.status())) {
                return v.status();
            }
            String actor = actor(versionId);
            // AS-8.8: a check that passes now resolves the PENDING stips it raised earlier.
            outcomes.values().stream().filter(o -> o.outcome() == Outcome.PASS).forEach(o ->
                    stips.autoSatisfy(versionId, o.check(), actor, "auto-resolved by re-run").forEach(st ->
                            audit.record("STIPULATION", Long.toString(st.stipId()), "SATISFIED", actor,
                                    Map.of("status", "PENDING"), Map.of("status", "SATISFIED", "note", "auto-resolved by re-run"))));
            boolean error = outcomes.values().stream().anyMatch(o -> o.outcome() == Outcome.ERROR);
            boolean pending = !stips.pending(versionId).isEmpty();
            // ERROR holds in PENDING_REVIEW (AS-8.4); any PENDING stip -> STIPULATED (AS-8.2); else APPROVED (AS-8.1, AS-8.3).
            String next = error ? "PENDING_REVIEW" : pending ? "STIPULATED" : "APPROVED";
            transition(v, next, actor, "VALIDATION_OUTCOME");
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

    private void transition(VersionRow v, String next, String actor, String reason) {
        if (next.equals(v.status())) {
            return;
        }
        versions.setStatus(v.applicationVersionId(), next);
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
