package com.funding.seed;

import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import java.util.Collection;

/** The version status that check outcomes plus the disbursement intent lead to (spec §6, AS-8.1–8.4). */
public final class ExpectedStatus {
    private ExpectedStatus() {}

    public static String derive(Collection<CheckOutcome> outcomes, String disbursementIntent) {
        if (outcomes.stream().anyMatch(o -> o.outcome() == Outcome.ERROR)) {
            return "PENDING_REVIEW";
        }
        if (outcomes.stream().anyMatch(o -> o.outcome() == Outcome.FAIL || o.outcome() == Outcome.NEEDS_REVIEW)) {
            return "STIPULATED";
        }
        return "SUCCESS".equals(disbursementIntent) ? "FUNDED" : "APPROVED";
    }
}
