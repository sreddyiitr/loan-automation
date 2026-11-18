package com.funding.domain.money;

import java.math.BigDecimal;

/** Level-payment amortization (AS-2.3) and the payment schedule total (C-9). */
public final class Amortization {
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    private Amortization() {}

    /**
     * {@code P = A·r / (1 − (1+r)^−n)}, r = APR% / 1200; r = 0 gives {@code A/n}. Rounded half-up to cents.
     */
    public static BigDecimal monthlyPayment(BigDecimal amountFinanced, BigDecimal aprPercent, int termMonths) {
        if (termMonths <= 0) {
            throw new IllegalArgumentException("termMonths must be > 0");
        }
        if (aprPercent.signum() == 0) {
            return Money.round(amountFinanced.divide(BigDecimal.valueOf(termMonths), Money.MC));
        }
        BigDecimal r = aprPercent.divide(TWELVE_HUNDRED, Money.MC);
        BigDecimal growth = BigDecimal.ONE.add(r, Money.MC).pow(termMonths, Money.MC);
        BigDecimal discount = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, Money.MC), Money.MC);
        return Money.round(amountFinanced.multiply(r, Money.MC).divide(discount, Money.MC));
    }

    /** C-9: equal payments, unless a final payment is given: {@code P × (n−1) + final}. */
    public static BigDecimal scheduledTotal(BigDecimal payment, int termMonths, BigDecimal finalPayment) {
        if (finalPayment == null) {
            return Money.round(payment.multiply(BigDecimal.valueOf(termMonths)));
        }
        return Money.round(payment.multiply(BigDecimal.valueOf(termMonths - 1L)).add(finalPayment));
    }
}
