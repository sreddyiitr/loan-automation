package com.funding.workflows;

import com.funding.domain.income.IncomeVerification;
import io.temporal.activity.ActivityInterface;

/** External API activity on the external task queue (US-5). Input is the version id only, never the SSN (FR-13). */
@ActivityInterface
public interface IncomeActivities {

    /**
     * Loads the encrypted SSN/ITIN by version id and calls the Income Verification API. Timeouts,
     * 5xx and 429 are retried by Temporal (429 honours {@code Retry-After}, capped at the retry
     * policy's maximum interval, C-13); malformed, schema-invalid and other 4xx responses are not.
     */
    IncomeVerification verifyIncome(long applicationVersionId);
}
