package com.funding.domain.income;

import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** {@code v = (stated − verified) / verified}, exact (FR-15: never rounded before comparing). Two-sided (C-3). */
public final class IncomeVariance {
    private IncomeVariance() {}

    public static BigDecimal of(BigDecimal stated, BigDecimal verified) {
        return stated.subtract(verified).divide(verified, Money.MC);
    }

    public static boolean withinThreshold(BigDecimal variance, BigDecimal threshold) {
        return variance.abs().compareTo(threshold) <= 0;
    }

    /** Display only: percent with 2 dp. */
    public static String percent(BigDecimal variance) {
        return variance.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
