package com.funding.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RulesConfigTest {

    @Test
    void NFR_07_fieldsAddedLaterAreFilledWhenReplayingOldPayloads() {
        // An S1 history only knows the TILA tolerances.
        RulesConfig old = new RulesConfig(new BigDecimal("0.00"), new BigDecimal("0.01"), null, null, null, null);
        assertThat(old.priceTolerance()).isEqualByComparingTo("0.00");
        assertThat(old.confidenceFlagBelow()).isEqualByComparingTo("0.90");
        assertThat(old.primaryLenderName()).isEqualTo("Summit Auto Finance");
        assertThat(old.incomeVarianceThreshold()).isEqualByComparingTo("0.15");
    }

    @Test
    void FR_12_missingConfigIsStillRejectedAtStartup() {
        assertThatThrownBy(() -> RulesConfig.from(k -> k.equals("funding.price.tolerance") ? null : RulesConfig.defaults().value(k)))
                .hasMessageContaining("funding.price.tolerance");
    }

    @Test
    void withChangesOneValue() {
        RulesConfig c = RulesConfig.defaults().with("funding.price.tolerance", "0.05");
        assertThat(c.priceTolerance()).isEqualByComparingTo("0.05");
        assertThat(c.tilaPaymentTolerance()).isEqualByComparingTo("0.01");
    }
}
