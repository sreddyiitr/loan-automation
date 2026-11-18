package com.funding.domain.rules;

import static com.funding.domain.DocumentType.RISC;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.CheckOutcome;
import com.funding.domain.ConfidencePolicy;
import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.ExtractedField;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T204: US-7 confidence gating and FR-14 parsing, across checks. */
class ConfidenceAndParsingTest {
    private final RulesConfig config = RulesConfig.defaults();

    @Test
    void AS_7_1_flaggedRequiredFieldIsNeedsReviewWithFlaggedKeys() {
        CheckOutcome out = new PriceParityRule().evaluate(RuleTestData.baseline().confidence(RISC, "cash_price", "0.89").snapshot(), config);
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.flaggedFields()).containsExactly("RISC.cash_price");
        assertThat(out.stips()).extracting(s -> s.code()).containsExactly("STIP_EXTRACTION_REVIEW_REQUIRED");
    }

    @Test
    void B4_confidenceAtCutOffIsNotFlagged() {
        assertThat(ConfidencePolicy.isFlagged(new BigDecimal("0.90"), config)).isFalse();
        assertThat(ConfidencePolicy.isFlagged(new BigDecimal("0.89"), config)).isTrue();
        assertThat(new TilaRule().evaluate(RuleTestData.baseline().confidence(RISC, "apr", "0.90").snapshot(), config).outcome())
                .isEqualTo(Outcome.PASS);
    }

    @Test
    void B4_cutOffIsConfigDriven() {
        RulesConfig strict = config.with("funding.confidence.flag-below", "0.98");
        assertThat(new TilaRule().evaluate(RuleTestData.baseline().snapshot(), strict).outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
    }

    @Test
    void AS_7_2_unusedFlaggedFieldIgnored() {
        RuleTestData d = RuleTestData.baseline();
        d.set(RISC, "dealer_notes", "rush").confidence(RISC, "dealer_notes", "0.40");
        // cash_price is used by PRICE, not by TILA: flagging it doesn't affect TILA.
        d.confidence(RISC, "cash_price", "0.50");
        assertThat(new TilaRule().evaluate(d.snapshot(), config).outcome()).isEqualTo(Outcome.PASS);
        assertThat(new VinRule().evaluate(d.snapshot(), config).outcome()).isEqualTo(Outcome.PASS);
        assertThat(new GapRule().evaluate(d.snapshot(), config).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void FR_14_missingFieldIsNeedsReview() {
        assertThat(new TilaRule().evaluate(RuleTestData.baseline().remove(RISC, "finance_charge").snapshot(), config).flaggedFields())
                .containsExactly("RISC.finance_charge");
    }

    @Test
    void FR_14_commaInMoneyIsNeedsReview() {
        CheckOutcome out = new PriceParityRule().evaluate(RuleTestData.baseline().set(RISC, "cash_price", "31,450.00").snapshot(), config);
        assertThat(out.outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(out.flaggedFields()).containsExactly("RISC.cash_price");
    }

    @Test
    void FR_14_unparseableYearIsNeedsReview() {
        assertThat(new VinRule().evaluate(RuleTestData.baseline().set(DocumentType.BUYERS_ORDER, "vehicle_year", "MY24").snapshot(), config).outcome())
                .isEqualTo(Outcome.NEEDS_REVIEW);
    }

    @Test
    void section8_highestConfidenceValueWinsWhenKeyRepeats() {
        DocumentSnapshot d = DocumentSnapshot.resolve(RISC, 1, List.of(
                new ExtractedField("cash_price", "31450.00", new BigDecimal("0.85")),
                new ExtractedField("cash_price", "31450.00", new BigDecimal("0.95"))));
        // The winning value is not flagged, so the field is accepted.
        assertThat(ConfidencePolicy.isFlagged(d.fields().get("cash_price").confidence(), config)).isFalse();
    }

    @Test
    void AS_8_10_riscCompletenessListsMissingFlaggedAndUnparseableFields() {
        assertThat(RiscCompleteness.problems(RuleTestData.baseline().snapshot(), config)).isEmpty();
        RuleTestData d = RuleTestData.baseline().remove(RISC, "finance_charge").confidence(RISC, "vin", "0.50")
                .set(RISC, "contract_date", "yesterday");
        assertThat(RiscCompleteness.problems(d.snapshot(), config))
                .containsExactlyInAnyOrder("RISC.finance_charge", "RISC.vin", "RISC.contract_date");
        assertThat(RiscCompleteness.problems(RuleTestData.baseline().without(RISC).snapshot(), config)).containsExactly("RISC");
    }
}
