package com.funding.domain;

import java.time.LocalDate;

/** One row of the state GAP form registry (US-6). {@code effectiveTo} null = open-ended. */
public record GapForm(String stateCode, String formNumber, LocalDate effectiveFrom, LocalDate effectiveTo) {

    public boolean effectiveOn(LocalDate d) {
        return !d.isBefore(effectiveFrom) && (effectiveTo == null || !d.isAfter(effectiveTo));
    }
}
