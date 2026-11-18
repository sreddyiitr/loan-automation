package com.funding.domain.rules;

import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.Snapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hand-built inputs for rule unit tests. Scenario data proper comes from the seed generator (P-2). */
final class RuleTestData {
    private RuleTestData() {}

    static Map<String, String> baselineRisc() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cash_price", "31450.00");
        m.put("amount_financed", "28450.00");
        m.put("finance_charge", "5342.60");
        m.put("total_of_payments", "33792.60");
        m.put("apr", "6.99");
        m.put("term_months", "60");
        m.put("monthly_payment", "563.21");
        return m;
    }

    static Snapshot snapshotWithRisc(Map<String, String> risc) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        risc.forEach((k, v) -> fields.put(k, new FieldValue(v, new BigDecimal("0.97"))));
        return new Snapshot(1L, "LA-TEST", 1, "4T1C11AK7RU100101", new BigDecimal("96000.00"), true, "DLR-100",
                Map.of(DocumentType.RISC, new DocumentSnapshot(DocumentType.RISC, 11L, fields)));
    }
}
