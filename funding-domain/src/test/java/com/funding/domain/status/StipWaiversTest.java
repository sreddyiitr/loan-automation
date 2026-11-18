package com.funding.domain.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StipWaiversTest {

    @Test
    void AS_8_10_riscStipsAreNotWaivable() {
        assertThat(StipWaivers.waivable("STIP_TILA_MATH_DISCREPANCY", "anything")).isFalse();
        assertThat(StipWaivers.waivable("STIP_EXTRACTION_REVIEW_REQUIRED", "Extraction review required: RISC.finance_charge")).isFalse();
        assertThat(StipWaivers.waivable("STIP_EXTRACTION_REVIEW_REQUIRED",
                "Extraction review required: BUYERS_ORDER.vin, RISC.cash_price")).isFalse();
    }

    @Test
    void otherStipsAreWaivable() {
        assertThat(StipWaivers.waivable("STIP_EXTRACTION_REVIEW_REQUIRED", "Extraction review required: BUYERS_ORDER.vin")).isTrue();
        assertThat(StipWaivers.waivable("STIP_PRICE_MISMATCH", "RISC.cash_price 31450.01 vs ...")).isTrue();
        assertThat(StipWaivers.waivable("STIP_PROOF_OF_INCOME", null)).isTrue();
    }
}
