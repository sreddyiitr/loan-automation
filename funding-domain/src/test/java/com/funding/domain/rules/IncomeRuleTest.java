package com.funding.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.StipRequest;
import com.funding.domain.income.IncomeVerification;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IncomeRuleTest {
    private final IncomeRule rule = new IncomeRule();
    private final RulesConfig config = RulesConfig.defaults();

    private CheckOutcome eval(String stated, IncomeVerification v) {
        Snapshot base = RuleTestData.baseline().snapshot();
        Snapshot s = new Snapshot(base.applicationVersionId(), base.loanApplicationId(), 1, base.vehicleVin(),
                stated == null ? null : new BigDecimal(stated), true, "DLR-100", base.documents(), base.gapForms(), v);
        return rule.evaluate(s, config);
    }

    private static IncomeVerification verified(String v) {
        return IncomeVerification.verified(new BigDecimal(v));
    }

    @Test
    void AS_5_1_withinThresholdPasses() {
        assertThat(eval("96000.00", verified("100000.00")).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_5_1_exactly15PercentPasses() {
        assertThat(eval("115000.00", verified("100000.00")).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_5_2_15_01PercentFailsWithStatedVerifiedAndPercent() {
        CheckOutcome out = eval("115010.00", verified("100000.00"));
        assertThat(out.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(out.stips()).singleElement().satisfies(s -> {
            assertThat(s.code()).isEqualTo("STIP_INCOME_VARIANCE_EXCEEDED");
            assertThat(s.description()).contains("115010.00").contains("100000.00").contains("15.01%");
        });
    }

    @Test
    void C_3_underStatedIncomeFails() {
        assertThat(eval("80000.00", verified("100000.00")).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(eval("85000.00", verified("100000.00")).outcome()).isEqualTo(Outcome.PASS); // -15.00%
    }

    @Test
    void FR_15_ratioNotRoundedBeforeCompare() {
        // 115000.01 / 100000 - 1 = 0.1500001: rounding to 2 dp would wrongly pass it.
        assertThat(eval("115000.01", verified("100000.00")).outcome()).isEqualTo(Outcome.FAIL);
    }

    @ParameterizedTest(name = "threshold {0}: stated {1} -> {2}")
    @CsvSource({"0.15, 115000.00, PASS", "0.10, 115000.00, FAIL", "0.20, 119999.99, PASS"})
    void FR_12_thresholdIsConfigDriven(String t, String stated, Outcome want) {
        RulesConfig c = config.with("funding.income.variance-threshold", t);
        Snapshot base = RuleTestData.baseline().snapshot().withIncome(verified("100000.00"));
        Snapshot s = new Snapshot(1, "LA", 1, base.vehicleVin(), new BigDecimal(stated), true, "D", base.documents(), base.gapForms(),
                base.incomeVerification());
        assertThat(rule.evaluate(s, c).outcome()).isEqualTo(want);
    }

    @Test
    void AS_5_1_missingStatedIncomeIsNeedsReview() {
        CheckOutcome out = eval(null, verified("100000.00"));
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.flaggedFields()).containsExactly("APPLICATION.stated_income_annual");
    }

    @Test
    void AS_5_1_missingTokenIsNeedsReview() {
        Snapshot b = RuleTestData.baseline().snapshot();
        Snapshot s = new Snapshot(1, "LA", 1, b.vehicleVin(), new BigDecimal("96000.00"), false, "D", b.documents(), b.gapForms(), null);
        assertThat(rule.evaluate(s, config).flaggedFields()).containsExactly("APPLICATION.ssn_itin_encrypted");
    }

    @Test
    void C_12_notFoundIsNeedsReviewWithProofOfIncomeStip() {
        CheckOutcome out = eval("96000.00", IncomeVerification.notFound());
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.stips()).extracting(StipRequest::code).containsExactly("STIP_PROOF_OF_INCOME");
    }
}
