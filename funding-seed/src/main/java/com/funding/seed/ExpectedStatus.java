package com.funding.seed;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import java.util.Collection;
import java.util.List;

/** The version status that check outcomes plus the disbursement intent lead to (spec §6, AS-8.1–8.4, AS-8.10). */
public final class ExpectedStatus {
    private ExpectedStatus() {}

    /**
     * @param riscProblems RISC completeness problems (AS-8.10); a version with any, or without TILA
     *                     PASS, is never auto-approved and stays held in PENDING_REVIEW
     */
    public static String derive(Collection<CheckOutcome> outcomes, String disbursementIntent, List<String> riscProblems) {
        if (outcomes.stream().anyMatch(o -> o.outcome() == Outcome.ERROR)) {
            return "PENDING_REVIEW";
        }
        if (outcomes.stream().anyMatch(o -> o.outcome() == Outcome.FAIL || o.outcome() == Outcome.NEEDS_REVIEW)) {
            return "STIPULATED";
        }
        boolean tilaPass = outcomes.stream().anyMatch(o -> o.check() == Check.TILA && o.outcome() == Outcome.PASS);
        if (!riscProblems.isEmpty() || !tilaPass) {
            return "PENDING_REVIEW";
        }
        return "SUCCESS".equals(disbursementIntent) ? "FUNDED" : "APPROVED";
    }
}
