package com.funding.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void FR_08_roundsHalfUpToTwoPlaces() {
        assertThat(Money.round(new BigDecimal("563.205"))).isEqualByComparingTo("563.21");
        assertThat(Money.round(new BigDecimal("563.2049999"))).isEqualByComparingTo("563.20");
        assertThat(Money.round(new BigDecimal("-0.005"))).isEqualByComparingTo("-0.01");
        assertThat(Money.round(new BigDecimal("10")).scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"31450.00", "31450", "0.5", "-12.34"})
    void FR_14_parsesValidMoney(String raw) {
        assertThat(Money.parse(raw)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"31,450.00", "$31450.00", "31450.001", "", " ", "abc", "1e5", "31450."})
    void FR_14_rejectsUnparseableMoney(String raw) {
        assertThat(Money.parse(raw)).isEqualTo(Optional.empty());
    }

    @Test
    void FR_14_nullIsUnparseable() {
        assertThat(Money.parse(null)).isEmpty();
    }
}
