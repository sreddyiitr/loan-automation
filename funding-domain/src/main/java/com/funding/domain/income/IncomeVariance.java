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
        return percent(variance, 2);
    }

    /**
     * Display only: |variance| as a percent with 2 dp, or with as many more (up to 6) as it takes to
     * differ from the threshold's display, so a failing 15.00001% never reads "15.00% exceeds 15.00%".
     */
    public static String percentAgainst(BigDecimal variance, BigDecimal threshold) {
        BigDecimal a = variance.abs();
        if (a.compareTo(threshold) == 0) {
            return percent(a, 2);
        }
        int dp = 2;
        while (dp < 6 && percent(a, dp).equals(percent(threshold, dp))) {
            dp++;
        }
        return percent(a, dp);
    }

    private static String percent(BigDecimal v, int dp) {
        return v.movePointRight(2).setScale(dp, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
