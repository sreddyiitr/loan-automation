package com.funding.domain.rules;

import com.funding.domain.ConfidencePolicy;
import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the fields one check needs (AS-7.2: only those) and collects every required field that is
 * missing, can't be parsed (FR-14) or is flagged (US-7) as {@code DOC.key}. A check with any such
 * field is NEEDS_REVIEW, never PASS or FAIL.
 */
final class FieldReader {
    private static final Pattern DECIMAL = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Pattern INTEGER = Pattern.compile("^[1-9]\\d{0,3}$");
    private static final Pattern YEAR = Pattern.compile("^(19|20)\\d{2}$");

    private final Snapshot snapshot;
    private final RulesConfig config;
    private final List<String> problems = new ArrayList<>();

    FieldReader(Snapshot snapshot, RulesConfig config) {
        this.snapshot = snapshot;
        this.config = config;
    }

    BigDecimal money(DocumentType doc, String key) {
        return trimmed(doc, key).flatMap(Money::parse).orElseGet(() -> problem(doc, key));
    }

    /** Optional money field: absent is fine; present but unparseable or flagged is a problem. */
    Optional<BigDecimal> optionalMoney(DocumentType doc, String key) {
        if (field(doc, key).map(FieldValue::raw).filter(s -> !s.isBlank()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(money(doc, key));
    }

    BigDecimal percent(DocumentType doc, String key) {
        return trimmed(doc, key).filter(s -> DECIMAL.matcher(s).matches()).map(BigDecimal::new)
                .orElseGet(() -> problem(doc, key));
    }

    Integer positiveInt(DocumentType doc, String key) {
        return trimmed(doc, key).filter(s -> INTEGER.matcher(s).matches()).map(Integer::valueOf)
                .orElseGet(() -> problem(doc, key));
    }

    Integer year(DocumentType doc, String key) {
        return trimmed(doc, key).filter(s -> YEAR.matcher(s).matches()).map(Integer::valueOf)
                .orElseGet(() -> problem(doc, key));
    }

    LocalDate date(DocumentType doc, String key) {
        return trimmed(doc, key).flatMap(FieldReader::isoDate).orElseGet(() -> problem(doc, key));
    }

    /** Non-blank text, trimmed. */
    String text(DocumentType doc, String key) {
        return trimmed(doc, key).orElseGet(() -> problem(doc, key));
    }

    /** Non-blank text exactly as extracted (VIN: AS-4.3 allows no normalization but upper-casing). */
    String exact(DocumentType doc, String key) {
        return accepted(doc, key).map(FieldValue::raw).filter(s -> !s.isBlank()).orElseGet(() -> problem(doc, key));
    }

    boolean ok() {
        return problems.isEmpty();
    }

    List<String> problems() {
        return List.copyOf(problems);
    }

    private Optional<String> trimmed(DocumentType doc, String key) {
        return accepted(doc, key).map(FieldValue::raw).map(String::trim).filter(s -> !s.isEmpty());
    }

    /** The field, unless it is flagged (below the confidence cut-off). */
    private Optional<FieldValue> accepted(DocumentType doc, String key) {
        return field(doc, key).filter(f -> !ConfidencePolicy.isFlagged(f.confidence(), config));
    }

    private Optional<FieldValue> field(DocumentType doc, String key) {
        return snapshot.document(doc).map(DocumentSnapshot::fields).map(f -> f.get(key)).filter(f -> f.raw() != null);
    }

    private static Optional<LocalDate> isoDate(String s) {
        try {
            return Optional.of(LocalDate.parse(s));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private <T> T problem(DocumentType doc, String key) {
        String k = doc.name() + "." + key;
        if (!problems.contains(k)) {
            problems.add(k);
        }
        return null;
    }
}
