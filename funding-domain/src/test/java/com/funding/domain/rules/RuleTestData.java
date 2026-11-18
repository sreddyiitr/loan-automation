package com.funding.domain.rules;

import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.GapForm;
import com.funding.domain.Snapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-built inputs for rule unit tests: the seed baseline, mutable per test. Scenario data proper
 * comes from the seed generator (P-2); these tests pin edge cases around it.
 */
final class RuleTestData {
    static final String VIN = "4T1C11AK7RU100101";
    static final List<GapForm> REGISTRY = List.of(
            new GapForm("TX", "GAP-TX-2024-01", LocalDate.of(2024, 1, 1), null),
            new GapForm("TX", "GAP-TX-2019-07", LocalDate.of(2019, 7, 1), LocalDate.of(2023, 12, 31)),
            new GapForm("CA", "GAP-CA-2025-03", LocalDate.of(2025, 3, 1), null));

    final Map<DocumentType, Map<String, FieldValue>> docs = new EnumMap<>(DocumentType.class);
    String appVin = VIN;

    private RuleTestData() {}

    static RuleTestData baseline() {
        RuleTestData d = new RuleTestData();
        Map<String, String> risc = baselineRisc();
        risc.putAll(Map.of("vin", VIN, "vehicle_year", "2024", "vehicle_make", "Toyota", "vehicle_model", "Camry",
                "creditor_name", "Summit Auto Finance", "contract_date", "2026-11-02"));
        d.put(DocumentType.RISC, risc);
        d.put(DocumentType.BUYERS_ORDER, Map.of("total_vehicle_cash_price", "31450.00", "vin", VIN,
                "vehicle_year", "2024", "vehicle_make", "Toyota", "vehicle_model", "Camry"));
        d.put(DocumentType.GAP_AGREEMENT, Map.of("form_number", "GAP-TX-2024-01", "state", "TX",
                "creditor_name", "Summit Auto Finance", "vin", VIN, "vehicle_year", "2024",
                "vehicle_make", "Toyota", "vehicle_model", "Camry"));
        return d;
    }

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

    RuleTestData put(DocumentType doc, Map<String, String> fields) {
        Map<String, FieldValue> m = new LinkedHashMap<>();
        fields.forEach((k, v) -> m.put(k, new FieldValue(v, new BigDecimal("0.97"))));
        docs.put(doc, m);
        return this;
    }

    RuleTestData set(DocumentType doc, String key, String value) {
        docs.get(doc).put(key, new FieldValue(value, docs.get(doc).containsKey(key) ? docs.get(doc).get(key).confidence() : new BigDecimal("0.97")));
        return this;
    }

    RuleTestData confidence(DocumentType doc, String key, String confidence) {
        docs.get(doc).put(key, new FieldValue(docs.get(doc).get(key).raw(), new BigDecimal(confidence)));
        return this;
    }

    RuleTestData remove(DocumentType doc, String key) {
        docs.get(doc).remove(key);
        return this;
    }

    RuleTestData without(DocumentType doc) {
        docs.remove(doc);
        return this;
    }

    Snapshot snapshot() {
        Map<DocumentType, DocumentSnapshot> m = new EnumMap<>(DocumentType.class);
        long id = 10;
        for (var e : docs.entrySet()) {
            m.put(e.getKey(), new DocumentSnapshot(e.getKey(), id++, e.getValue()));
        }
        return new Snapshot(1L, "LA-TEST", 1, appVin, new BigDecimal("96000.00"), true, "DLR-100", m, REGISTRY);
    }

    /** RISC-only snapshot (TILA tests). */
    static Snapshot snapshotWithRisc(Map<String, String> risc) {
        RuleTestData d = baseline();
        Map<String, String> r = new LinkedHashMap<>();
        d.docs.get(DocumentType.RISC).forEach((k, v) -> r.put(k, v.raw()));
        baselineRisc().keySet().forEach(r::remove);
        r.putAll(risc);
        return d.put(DocumentType.RISC, r).snapshot();
    }
}
