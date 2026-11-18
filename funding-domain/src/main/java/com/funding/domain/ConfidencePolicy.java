package com.funding.domain;

import java.math.BigDecimal;

/**
 * US-7 / owner decision B4: a field is flagged when its confidence is below
 * {@code funding.confidence.flag-below}. Exactly at the cut-off is not flagged. Computed here,
 * never stored.
 */
public final class ConfidencePolicy {
    private ConfidencePolicy() {}

    public static boolean isFlagged(BigDecimal confidence, RulesConfig config) {
        return confidence == null || confidence.compareTo(config.confidenceFlagBelow()) < 0;
    }
}
