package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import io.temporal.activity.ActivityInterface;
import java.util.Map;

/** DB activities on the validation task queue. Every write is guarded by is_latest_version in its transaction (AS-9.3). */
@ActivityInterface
public interface FundingActivities {

    VersionView loadVersionSnapshot(long applicationVersionId);

    /** Persists the result row and creates the check's stips. Returns false (and writes nothing) if the version was superseded or is terminal. */
    boolean recordOutcome(long applicationVersionId, CheckOutcome outcome, int attempt);

    /** Sets APPROVED / STIPULATED / PENDING_REVIEW from the outcomes; returns the status, or SUPERSEDED. */
    String applyOutcome(long applicationVersionId, Map<Check, CheckOutcome> outcomes);

    /** APPROVED -> FUNDED, audited. Returns the status, or SUPERSEDED. */
    String markFunded(long applicationVersionId);

    /**
     * AS-8.6: a non-terminal latest version becomes EXPIRED, audited by {@code system:<workflowId>}
     * with reason {@code EXPIRED_AFTER_<expire-after>}; PENDING stips stay. Returns the status after
     * (EXPIRED, or the terminal status it already had), or SUPERSEDED.
     */
    String expire(long applicationVersionId);
}
