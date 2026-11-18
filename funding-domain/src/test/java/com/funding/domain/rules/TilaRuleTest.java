package com.funding.domain.rules;

import static com.funding.domain.rules.RuleTestData.baselineRisc;
import static com.funding.domain.rules.RuleTestData.snapshotWithRisc;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TilaRuleTest {

    private final RulesConfig config = RulesConfig.defaults();
    private final TilaRule rule = new TilaRule();

    private CheckOutcome eval(Map<String, String> risc) {
        return rule.evaluate(snapshotWithRisc(risc), config);
    }

    @Test
    void AS_2_1_sumWithinToleranceIsPass() {
        CheckOutcome out = eval(baselineRisc());
        assertThat(out.check()).isEqualTo(Check.TILA);
        assertThat(out.outcome()).isEqualTo(Outcome.PASS);
        assertThat(out.stips()).isEmpty();
    }

    @Test
    void AS_2_2_sumMismatchCreatesTilaStip() {
        Map<String, String> risc = baselineRisc();
        risc.put("total_of_payments", "33802.60");
        CheckOutcome out = eval(risc);
        assertThat(out.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(out.stips()).extracting(s -> s.code()).containsExactly("STIP_TILA_MATH_DISCREPANCY");
        assertThat(out.comparisons()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("amount_financed + finance_charge = total_of_payments");
            assertThat(c.expected()).isEqualTo("33792.60");
            assertThat(c.actual()).isEqualTo("33802.60");
            assertThat(c.ok()).isFalse();
        });
    }

    @Test
    void AS_2_3_paymentOffByMoreThanToleranceFails() {
        Map<String, String> risc = baselineRisc();
        // payment +5.00 with total and finance charge rebalanced, so only the payment is wrong
        risc.put("monthly_payment", "568.21");
        risc.put("total_of_payments", "34092.60");
        risc.put("finance_charge", "5642.60");
        CheckOutcome out = eval(risc);
        assertThat(out.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(out.stips()).extracting(s -> s.code()).containsExactly("STIP_TILA_MATH_DISCREPANCY");
        assertThat(out.comparisons()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("amortized monthly_payment");
            assertThat(c.expected()).isEqualTo("563.21");
            assertThat(c.actual()).isEqualTo("568.21");
        });
    }

    @Test
    void AS_2_3_paymentOffByExactlyToleranceIsPass() {
        Map<String, String> risc = rebalanced("563.22"); // +0.01 = payment-tolerance
        assertThat(eval(risc).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_2_3_paymentOffByOneCentMoreThanToleranceFails() {
        Map<String, String> risc = rebalanced("563.23"); // +0.02
        assertThat(eval(risc).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_2_3_zeroAprUsesAOverN() {
        Map<String, String> risc = baselineRisc();
        risc.put("amount_financed", "24000.00");
        risc.put("apr", "0");
        risc.put("term_months", "48");
        risc.put("monthly_payment", "500.00");
        risc.put("total_of_payments", "24000.00");
        risc.put("finance_charge", "0.00");
        assertThat(eval(risc).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void C_9_finalPaymentChangesExpectedTotal() {
        Map<String, String> risc = baselineRisc();
        risc.put("final_payment", "563.36");
        risc.put("total_of_payments", "33792.75");
        risc.put("finance_charge", "5342.75");
        assertThat(eval(risc).outcome()).isEqualTo(Outcome.PASS);

        // Without final_payment the same total no longer matches the equal-payment schedule.
        risc.remove("final_payment");
        assertThat(eval(risc).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void FR_14_missingRequiredFieldIsNeedsReviewNotFail() {
        Map<String, String> risc = baselineRisc();
        risc.remove("finance_charge");
        CheckOutcome out = eval(risc);
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.flaggedFields()).containsExactly("RISC.finance_charge");
        assertThat(out.stips()).extracting(s -> s.code()).containsExactly("STIP_EXTRACTION_REVIEW_REQUIRED");
    }

    @Test
    void FR_14_unparseableMoneyIsNeedsReview() {
        Map<String, String> risc = baselineRisc();
        risc.put("amount_financed", "28,450.00");
        assertThat(eval(risc).outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
    }

    @Test
    void FR_05_sameInputSameOutcome() {
        assertThat(eval(baselineRisc())).isEqualTo(eval(baselineRisc()));
    }

    private static Map<String, String> rebalanced(String payment) {
        Map<String, String> risc = baselineRisc();
        java.math.BigDecimal p = new java.math.BigDecimal(payment);
        java.math.BigDecimal total = p.multiply(java.math.BigDecimal.valueOf(60));
        risc.put("monthly_payment", payment);
        risc.put("total_of_payments", total.toPlainString());
        risc.put("finance_charge", total.subtract(new java.math.BigDecimal("28450.00")).toPlainString());
        return risc;
    }
}
