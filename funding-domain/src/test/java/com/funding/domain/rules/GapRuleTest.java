package com.funding.domain.rules;

import static com.funding.domain.DocumentType.GAP_AGREEMENT;
import static com.funding.domain.DocumentType.RISC;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.StipRequest;
import org.junit.jupiter.api.Test;

class GapRuleTest {
    private final GapRule rule = new GapRule();
    private final RulesConfig config = RulesConfig.defaults();

    private CheckOutcome eval(RuleTestData d) {
        return rule.evaluate(d.snapshot(), config);
    }

    @Test
    void AS_6_1_registeredEffectiveFormPasses() {
        assertThat(eval(RuleTestData.baseline()).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_6_1_expiredFormFails() {
        CheckOutcome out = eval(RuleTestData.baseline().set(GAP_AGREEMENT, "form_number", "GAP-TX-2019-07"));
        assertThat(out.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(out.stips()).extracting(StipRequest::code).containsExactly("STIP_GAP_FORM_MISMATCH");
    }

    @Test
    void AS_6_1_otherStateFormFails() {
        assertThat(eval(RuleTestData.baseline().set(GAP_AGREEMENT, "form_number", "GAP-CA-2025-03")).stips())
                .extracting(StipRequest::code).containsExactly("STIP_GAP_FORM_MISMATCH");
    }

    @Test
    void AS_6_1_effectiveDateBoundaries() {
        // GAP-TX-2019-07 is effective 2019-07-01 .. 2023-12-31 inclusive.
        RuleTestData d = RuleTestData.baseline().set(GAP_AGREEMENT, "form_number", "GAP-TX-2019-07");
        assertThat(eval(d.set(RISC, "contract_date", "2023-12-31")).outcome()).isEqualTo(Outcome.PASS);
        assertThat(eval(d.set(RISC, "contract_date", "2024-01-01")).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(eval(d.set(RISC, "contract_date", "2019-07-01")).outcome()).isEqualTo(Outcome.PASS);
        assertThat(eval(d.set(RISC, "contract_date", "2019-06-30")).outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void AS_6_2_lenderMismatchFails() {
        assertThat(eval(RuleTestData.baseline().set(GAP_AGREEMENT, "creditor_name", "Coastal Credit Union")).stips())
                .extracting(StipRequest::code).containsExactly("STIP_LENDER_MISMATCH");
        assertThat(eval(RuleTestData.baseline().set(RISC, "creditor_name", "Coastal Credit Union")).stips())
                .extracting(StipRequest::code).containsExactly("STIP_LENDER_MISMATCH");
    }

    @Test
    void AS_6_2_lenderComparisonIgnoresCaseAndWhitespace() {
        assertThat(eval(RuleTestData.baseline().set(GAP_AGREEMENT, "creditor_name", "  summit auto FINANCE ")).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void AS_6_3_bothSubChecksCanFail() {
        CheckOutcome out = eval(RuleTestData.baseline().set(GAP_AGREEMENT, "form_number", "GAP-TX-2019-07")
                .set(GAP_AGREEMENT, "creditor_name", "Coastal Credit Union"));
        assertThat(out.stips()).extracting(StipRequest::code)
                .containsExactly("STIP_GAP_FORM_MISMATCH", "STIP_LENDER_MISMATCH");
    }

    @Test
    void AS_6_4_noGapIsPassNotApplicable() {
        CheckOutcome out = eval(RuleTestData.baseline().without(GAP_AGREEMENT));
        assertThat(out.outcome()).isEqualTo(Outcome.PASS);
        assertThat(out.comparisons()).singleElement().satisfies(c -> assertThat(c.expected()).isEqualTo(GapRule.NOT_APPLICABLE));
    }

    @Test
    void FR_14_unparseableContractDateIsNeedsReview() {
        assertThat(eval(RuleTestData.baseline().set(RISC, "contract_date", "11/02/2026")).outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
    }
}
