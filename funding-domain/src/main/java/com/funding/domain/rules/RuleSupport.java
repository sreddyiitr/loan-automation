package com.funding.domain.rules;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Comparison;
import com.funding.domain.Outcome;
import com.funding.domain.StipCodes;
import com.funding.domain.StipRequest;
import java.util.List;

/** Outcome builders shared by the rules. */
final class RuleSupport {
    private RuleSupport() {}

    /** US-7 / FR-14: missing, unparseable or flagged required fields. */
    static CheckOutcome needsReview(Check check, List<String> fields) {
        return new CheckOutcome(check, Outcome.NEEDS_REVIEW, List.of(), fields,
                List.of(new StipRequest(StipCodes.EXTRACTION_REVIEW_REQUIRED, "Extraction review required: " + String.join(", ", fields))),
                null);
    }

    static CheckOutcome pass(Check check, List<Comparison> comparisons) {
        return new CheckOutcome(check, Outcome.PASS, comparisons, List.of(), List.of(), null);
    }

    static CheckOutcome fail(Check check, List<Comparison> comparisons, List<StipRequest> stips) {
        return new CheckOutcome(check, Outcome.FAIL, comparisons, List.of(), stips, null);
    }

    static String describeFailures(List<Comparison> comparisons) {
        return String.join("; ", comparisons.stream().filter(c -> !c.ok())
                .map(c -> c.name() + " (expected " + c.expected() + ", actual " + c.actual() + ")").toList());
    }
}
