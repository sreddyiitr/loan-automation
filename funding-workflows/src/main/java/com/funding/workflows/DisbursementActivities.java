package com.funding.workflows;

import io.temporal.activity.ActivityInterface;

/** External API activity on the external task queue (AS-8.1, AS-8.9). */
@ActivityInterface
public interface DisbursementActivities {

    /**
     * Re-checks latest + APPROVED + no PENDING stip in one transaction, then pays RISC
     * {@code amount_financed} to the dealer with idempotency key = workflow ID. Returns the external
     * reference, or {@code null} when the re-check says not to pay.
     */
    String disburse(long applicationVersionId);
}
