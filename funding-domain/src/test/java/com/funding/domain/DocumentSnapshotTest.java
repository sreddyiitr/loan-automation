package com.funding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentSnapshotTest {
    @Test
    void highestConfidenceValueWinsWhenKeyRepeats() {
        DocumentSnapshot d = DocumentSnapshot.resolve(DocumentType.RISC, 1, List.of(
                new ExtractedField("cash_price", "31450.00", new BigDecimal("0.91")),
                new ExtractedField("cash_price", "31460.00", new BigDecimal("0.98")),
                new ExtractedField("cash_price", "31470.00", new BigDecimal("0.95"))));
        assertThat(d.fields().get("cash_price").raw()).isEqualTo("31460.00");
    }
}
