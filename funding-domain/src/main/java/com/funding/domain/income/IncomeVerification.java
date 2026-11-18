package com.funding.domain.income;

import java.math.BigDecimal;

/**
 * What the Income Verification API said (US-5): {@code VERIFIED} with the total verified annual
 * income, or {@code NOT_FOUND} (C-12). Never carries the SSN/ITIN (AS-5.4).
 */
public record IncomeVerification(String status, BigDecimal totalVerifiedAnnualIncome) {
    public static final String VERIFIED = "VERIFIED";
    public static final String NOT_FOUND = "NOT_FOUND";

    public static IncomeVerification verified(BigDecimal total) {
        return new IncomeVerification(VERIFIED, total);
    }

    public static IncomeVerification notFound() {
        return new IncomeVerification(NOT_FOUND, null);
    }
}
