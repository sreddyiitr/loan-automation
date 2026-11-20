package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import java.util.Map;
import java.util.Set;

/**
 * What a run hands to its continueAsNew successor: the expiry deadline is never recomputed (AS-8.6).
 * {@code documentsHandled} (S5) may be null in a carry written before S5.
 */
public record FundingCarry(Map<Check, CheckOutcome> outcomes, Map<Check, Integer> attempts, long expiresAtEpochMillis,
                           boolean disbursementFailed, Set<Long> documentsHandled) {}
