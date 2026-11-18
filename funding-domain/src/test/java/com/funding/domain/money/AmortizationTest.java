package com.funding.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmortizationTest {

    @ParameterizedTest(name = "A={0} apr={1} n={2} -> {3}")
    @CsvSource({
            "28450.00, 6.99, 60, 563.21",   // seed baseline
            "31875.50, 8.49, 72, 566.54",   // LA-1005 rounding edge
            "24000.00, 0,    48, 500.00",   // LA-1004 0% APR -> A/n
            "1000.00,  0,     3, 333.33",   // A/n rounds half-up
            "10000.00, 12,   12, 888.49"})
    void AS_2_3_amortizedPayment(String a, String apr, int n, String expected) {
        assertThat(Amortization.monthlyPayment(new BigDecimal(a), new BigDecimal(apr), n))
                .isEqualByComparingTo(expected);
    }

    @Test
    void AS_2_3_zeroAprUsesAOverN() {
        assertThat(Amortization.monthlyPayment(new BigDecimal("24000.00"), BigDecimal.ZERO, 48))
                .isEqualByComparingTo("500.00");
    }

    @Test
    void C_9_scheduledTotalWithAndWithoutFinalPayment() {
        BigDecimal p = new BigDecimal("563.21");
        assertThat(Amortization.scheduledTotal(p, 60, null)).isEqualByComparingTo("33792.60");
        assertThat(Amortization.scheduledTotal(p, 60, new BigDecimal("563.36"))).isEqualByComparingTo("33792.75");
    }
}
