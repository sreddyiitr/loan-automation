package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import java.util.Map;

/** What a run hands to its continueAsNew successor: the expiry deadline is never recomputed (AS-8.6). */
public record FundingCarry(Map<Check, CheckOutcome> outcomes, Map<Check, Integer> attempts, long expiresAtEpochMillis,
                           boolean disbursementFailed) {}
