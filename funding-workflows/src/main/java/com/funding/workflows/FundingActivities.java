package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import io.temporal.activity.ActivityInterface;
import java.util.Map;

/** DB activities on the validation task queue. Every write is guarded by is_latest_version in its transaction (AS-9.3). */
@ActivityInterface
public interface FundingActivities {

    VersionView loadVersionSnapshot(long applicationVersionId);

    /** Persists the result row and creates the check's stips. Returns false if the version was superseded. */
    boolean recordOutcome(long applicationVersionId, CheckOutcome outcome, int attempt);

    /** Sets APPROVED / STIPULATED / PENDING_REVIEW from the outcomes; returns the status, or SUPERSEDED. */
    String applyOutcome(long applicationVersionId, Map<Check, CheckOutcome> outcomes);

    /** APPROVED -> FUNDED, audited. Returns the status, or SUPERSEDED. */
    String markFunded(long applicationVersionId);
}
