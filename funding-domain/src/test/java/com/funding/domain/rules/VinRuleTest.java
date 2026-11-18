package com.funding.domain.rules;

import static com.funding.domain.DocumentType.BUYERS_ORDER;
import static com.funding.domain.DocumentType.GAP_AGREEMENT;
import static com.funding.domain.DocumentType.RISC;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import org.junit.jupiter.api.Test;

class VinRuleTest {
    private final VinRule rule = new VinRule();
    private final RulesConfig config = RulesConfig.defaults();

    private CheckOutcome eval(RuleTestData d) {
        return rule.evaluate(d.snapshot(), config);
    }

    @Test
    void AS_4_1_allMatchAndValidPasses() {
        assertThat(eval(RuleTestData.baseline()).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_4_2_gapVinDiffersFailsListingEachDocumentsValue() {
        CheckOutcome out = eval(RuleTestData.baseline().set(GAP_AGREEMENT, "vin", "4T1C11AK7RU100102"));
        assertThat(out.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(out.stips()).singleElement().satisfies(s -> {
            assertThat(s.code()).isEqualTo("STIP_VIN_MISMATCH");
            assertThat(s.description()).contains("RISC=4T1C11AK7RU100101").contains("BUYERS_ORDER=4T1C11AK7RU100101")
                    .contains("GAP_AGREEMENT=4T1C11AK7RU100102");
        });
    }

    @Test
    void AS_4_2_modelDiffersFails() {
        assertThat(eval(RuleTestData.baseline().set(BUYERS_ORDER, "vehicle_model", "Camry SE")).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_4_2_yearDiffersFails() {
        assertThat(eval(RuleTestData.baseline().set(BUYERS_ORDER, "vehicle_year", "2023")).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_4_1_letterOInVinFails() {
        RuleTestData d = RuleTestData.baseline();
        String bad = "4T1C11AK7RU1O0101";
        d.set(RISC, "vin", bad).set(BUYERS_ORDER, "vin", bad).set(GAP_AGREEMENT, "vin", bad).appVin = bad;
        assertThat(eval(d).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_4_1_wrongLengthFails() {
        RuleTestData d = RuleTestData.baseline();
        String shortVin = "4T1C11AK7RU10010";
        d.set(RISC, "vin", shortVin).set(BUYERS_ORDER, "vin", shortVin).set(GAP_AGREEMENT, "vin", shortVin).appVin = shortVin;
        assertThat(eval(d).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_4_1_appVinDiffersFails() {
        RuleTestData d = RuleTestData.baseline();
        d.appVin = "4T1C11AK7RU100199";
        assertThat(eval(d).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_4_3_makeModelCaseAndSpaceIgnored() {
        assertThat(eval(RuleTestData.baseline().set(BUYERS_ORDER, "vehicle_make", "toyota ")
                .set(GAP_AGREEMENT, "vehicle_model", " CAMRY")).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_4_3_vinUpperCasedOnly() {
        assertThat(eval(RuleTestData.baseline().set(RISC, "vin", "4t1c11ak7ru100101")).outcome()).isEqualTo(Outcome.PASS);
        // No other normalization: surrounding whitespace in a VIN is a mismatch.
        assertThat(eval(RuleTestData.baseline().set(RISC, "vin", " 4T1C11AK7RU100101")).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_6_4_noGapComparesRiscAndBuyersOrderOnly() {
        assertThat(eval(RuleTestData.baseline().without(GAP_AGREEMENT)).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_7_1_flaggedVinIsNeedsReview() {
        CheckOutcome out = eval(RuleTestData.baseline().confidence(BUYERS_ORDER, "vin", "0.50"));
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.flaggedFields()).containsExactly("BUYERS_ORDER.vin");
    }
}
