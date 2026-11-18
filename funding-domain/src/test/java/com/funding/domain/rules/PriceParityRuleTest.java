package com.funding.domain.rules;

import static com.funding.domain.DocumentType.BUYERS_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import org.junit.jupiter.api.Test;

class PriceParityRuleTest {
    private final PriceParityRule rule = new PriceParityRule();
    private final RulesConfig config = RulesConfig.defaults();

    @Test
    void AS_3_1_exactMatchPasses() {
        assertThat(rule.evaluate(RuleTestData.baseline().snapshot(), config).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_3_2_anyDifferenceFails() {
        CheckOutcome out = rule.evaluate(RuleTestData.baseline().set(BUYERS_ORDER, "total_vehicle_cash_price", "31450.01").snapshot(), config);
        assertThat(out.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(out.stips()).singleElement().satisfies(s -> {
            assertThat(s.code()).isEqualTo("STIP_PRICE_MISMATCH");
            assertThat(s.description()).contains("31450.00").contains("31450.01");
        });
        assertThat(out.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.expected()).isEqualTo("31450.00");
            assertThat(c.actual()).isEqualTo("31450.01");
        });
    }

    @Test
    void FR_12_toleranceIsConfigDriven() {
        RulesConfig loose = config.with("funding.price.tolerance", "0.01");
        assertThat(rule.evaluate(RuleTestData.baseline().set(BUYERS_ORDER, "total_vehicle_cash_price", "31450.01").snapshot(), loose).outcome())
                .isEqualTo(Outcome.PASS);
        assertThat(rule.evaluate(RuleTestData.baseline().set(BUYERS_ORDER, "total_vehicle_cash_price", "31450.02").snapshot(), loose).outcome())
                .isEqualTo(Outcome.FAIL);
    }

    @Test
    void FR_14_missingBuyersOrderIsNeedsReview() {
        CheckOutcome out = rule.evaluate(RuleTestData.baseline().without(BUYERS_ORDER).snapshot(), config);
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.flaggedFields()).containsExactly("BUYERS_ORDER.total_vehicle_cash_price");
    }
}
