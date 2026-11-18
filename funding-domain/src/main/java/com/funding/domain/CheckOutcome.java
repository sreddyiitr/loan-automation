package com.funding.domain;

import java.util.List;

/**
 * Result of one check. Business FAIL is a value, never an exception, so Temporal never retries it.
 */
public record CheckOutcome(
        Check check,
        Outcome outcome,
        List<Comparison> comparisons,
        List<String> flaggedFields,
        List<StipRequest> stips,
        String error) {

    public CheckOutcome {
        comparisons = List.copyOf(comparisons);
        flaggedFields = List.copyOf(flaggedFields);
        stips = List.copyOf(stips);
    }

    public static CheckOutcome error(Check check, String message) {
        return new CheckOutcome(check, Outcome.ERROR, List.of(), List.of(), List.of(), message);
    }
}
