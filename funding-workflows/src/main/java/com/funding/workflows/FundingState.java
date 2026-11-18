package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.Outcome;
import java.util.Map;

/**
 * {@code getState()} query (plan step 6): outcomes, attempt per check, what the run waits on, when
 * it expires (epoch millis, 0 = no deadline), and whether a disbursement failed and awaits a retry.
 */
public record FundingState(String step, String status, Map<Check, Outcome> outcomes, Map<Check, Integer> attempts,
                           long expiresAtEpochMillis, boolean disbursementFailed) {}
