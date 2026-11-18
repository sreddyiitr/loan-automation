package com.funding.domain.rules;

import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.Snapshot;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the fields a check needs and collects every required field that is missing or can't be
 * parsed (FR-14) as {@code DOC.key}. A check with any such field is NEEDS_REVIEW, never PASS or FAIL.
 */
final class FieldReader {
    private static final Pattern DECIMAL = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Pattern INTEGER = Pattern.compile("^[1-9]\\d{0,3}$");

    private final Snapshot snapshot;
    private final List<String> problems = new ArrayList<>();

    FieldReader(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    BigDecimal money(DocumentType doc, String key) {
        return raw(doc, key).flatMap(Money::parse).orElseGet(() -> problem(doc, key));
    }

    /** Optional money field: absent is fine, present-but-unparseable is a problem. */
    Optional<BigDecimal> optionalMoney(DocumentType doc, String key) {
        Optional<String> raw = raw(doc, key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<BigDecimal> v = Money.parse(raw.get());
        if (v.isEmpty()) {
            problem(doc, key);
        }
        return v;
    }

    BigDecimal percent(DocumentType doc, String key) {
        return raw(doc, key).filter(s -> DECIMAL.matcher(s).matches()).map(BigDecimal::new)
                .orElseGet(() -> problem(doc, key));
    }

    Integer positiveInt(DocumentType doc, String key) {
        return raw(doc, key).filter(s -> INTEGER.matcher(s).matches()).map(Integer::valueOf)
                .orElseGet(() -> problem(doc, key));
    }

    boolean ok() {
        return problems.isEmpty();
    }

    List<String> problems() {
        return List.copyOf(problems);
    }

    private Optional<String> raw(DocumentType doc, String key) {
        return snapshot.document(doc)
                .map(DocumentSnapshot::fields)
                .map(f -> f.get(key))
                .map(FieldValue::raw)
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }

    private <T> T problem(DocumentType doc, String key) {
        problems.add(doc.name() + "." + key);
        return null;
    }
}
