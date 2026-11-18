package com.funding.domain;

import com.funding.domain.money.Amortization;
import com.funding.domain.money.Money;
import com.funding.domain.rules.CheckRule;
import com.funding.domain.rules.GapRule;
import com.funding.domain.rules.PriceParityRule;
import com.funding.domain.rules.TilaRule;
import com.funding.domain.rules.VinRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every business rule (constitution P-2.4). The coverage gate requires a scenario for each listed
 * side; the lock gate fingerprints each rule's {@code sources} plus the {@code configKeys} it reads.
 *
 * <p>Sides are {@code PASS | FAIL | PASS_BOUNDARY | FAIL_BOUNDARY | NEEDS_REVIEW | ERROR}, optionally
 * with a variant after a colon ({@code PASS:zero-apr}). A rule with {@code check == null} is
 * cross-cutting (it applies inside every check, like confidence gating): its scenarios declare the
 * side's outcome on whichever check reads the field.
 */
public enum RuleCatalog {

    TILA_SUM(Check.TILA, TilaRule.class, sources(TilaRule.class, Amortization.class),
            List.of("funding.tila.sum-tolerance", "funding.confidence.flag-below"),
            List.of("PASS", "FAIL")),

    TILA_PAYMENT(Check.TILA, TilaRule.class, sources(TilaRule.class, Amortization.class),
            List.of("funding.tila.payment-tolerance", "funding.tila.sum-tolerance", "funding.confidence.flag-below"),
            List.of("PASS", "FAIL", "PASS:zero-apr", "PASS:rounding", "PASS:final-payment")),

    PRICE_PARITY(Check.PRICE, PriceParityRule.class, sources(PriceParityRule.class),
            List.of("funding.price.tolerance", "funding.confidence.flag-below"),
            List.of("PASS", "FAIL_BOUNDARY")),

    VIN_FORMAT(Check.VIN, VinRule.class, sources(VinRule.class), List.of("funding.confidence.flag-below"),
            List.of("PASS", "FAIL")),

    VIN_CROSS_DOC(Check.VIN, VinRule.class, sources(VinRule.class), List.of("funding.confidence.flag-below"),
            List.of("PASS", "FAIL", "PASS:normalized")),

    VEHICLE_ATTRS(Check.VIN, VinRule.class, sources(VinRule.class), List.of("funding.confidence.flag-below"),
            List.of("PASS", "FAIL")),

    VIN_APP_MATCH(Check.VIN, VinRule.class, sources(VinRule.class), List.of("funding.confidence.flag-below"),
            List.of("PASS", "FAIL")),

    GAP_FORM(Check.GAP, GapRule.class, sources(GapRule.class, GapForm.class), List.of("funding.confidence.flag-below"),
            List.of("PASS", "FAIL:expired", "FAIL:other-state")),

    GAP_LENDER(Check.GAP, GapRule.class, sources(GapRule.class),
            List.of("funding.gap.primary-lender-name", "funding.confidence.flag-below"),
            List.of("PASS", "FAIL")),

    GAP_OPTIONAL(Check.GAP, GapRule.class, sources(GapRule.class), List.of(),
            List.of("PASS:not-applicable")),

    /** US-7: a required field below {@code funding.confidence.flag-below} makes its check NEEDS_REVIEW. */
    CONFIDENCE_FLAG(null, null, sources(ConfidencePolicy.class), List.of("funding.confidence.flag-below"),
            List.of("NEEDS_REVIEW", "PASS_BOUNDARY", "PASS:unused-field")),

    /** FR-14 / AS-7.1: a missing or unparseable required field makes its check NEEDS_REVIEW. */
    REQUIRED_FIELD(null, null, sources(Money.class), List.of(),
            List.of("NEEDS_REVIEW:missing", "NEEDS_REVIEW:unparseable"));

    private final Check check;
    private final Class<? extends CheckRule> evaluator;
    private final List<String> sources;
    private final List<String> configKeys;
    private final List<String> requiredSides;

    RuleCatalog(Check check, Class<? extends CheckRule> evaluator, List<String> sources,
                List<String> configKeys, List<String> requiredSides) {
        this.check = check;
        this.evaluator = evaluator;
        this.sources = sources;
        this.configKeys = configKeys;
        this.requiredSides = requiredSides;
    }

    /** The check this rule belongs to, or null for a cross-cutting rule. */
    public Check check() { return check; }
    /** The rule implementation, or null for a cross-cutting rule (it lives in every check's field reading). */
    public Class<? extends CheckRule> evaluator() { return evaluator; }
    /** Fully qualified names of the classes whose source makes up this rule's fingerprint. */
    public List<String> sources() { return sources; }
    public List<String> configKeys() { return configKeys; }
    /** External APIs this rule consumes (P-5.3). None yet: INCOME arrives in slice S3. */
    public Set<ExternalApi> apis() { return Set.of(); }
    public List<String> requiredSides() { return requiredSides; }

    /** Every rule's fingerprint includes the shared field reading (confidence gating, FR-14 parsing). */
    private static List<String> sources(Class<?>... classes) {
        List<String> s = new ArrayList<>();
        for (Class<?> c : classes) {
            s.add(c.getName());
        }
        s.add(Money.class.getName());
        s.add(ConfidencePolicy.class.getName());
        s.add("com.funding.domain.rules.FieldReader");
        s.add("com.funding.domain.rules.RuleSupport");
        return s.stream().distinct().toList();
    }
}
