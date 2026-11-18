package com.funding.domain;

import com.funding.domain.money.Amortization;
import com.funding.domain.money.Money;
import com.funding.domain.rules.CheckRule;
import com.funding.domain.rules.TilaRule;
import java.util.List;
import java.util.Set;

/**
 * Every business rule (constitution P-2.4). The coverage gate requires a scenario for each listed
 * side; the lock gate fingerprints each rule's {@code sources} plus the {@code configKeys} it reads.
 *
 * <p>Sides are {@code PASS | FAIL | PASS_BOUNDARY | FAIL_BOUNDARY | NEEDS_REVIEW | ERROR}, optionally
 * with a variant after a colon ({@code PASS:zero-apr}) when one rule needs several distinct cases.
 */
public enum RuleCatalog {

    TILA_SUM(Check.TILA, TilaRule.class,
            List.of(TilaRule.class, Money.class, Amortization.class, fieldReader()),
            List.of("funding.tila.sum-tolerance"),
            Set.of(),
            List.of("PASS", "FAIL")),

    TILA_PAYMENT(Check.TILA, TilaRule.class,
            List.of(TilaRule.class, Money.class, Amortization.class, fieldReader()),
            List.of("funding.tila.payment-tolerance", "funding.tila.sum-tolerance"),
            Set.of(),
            List.of("PASS", "FAIL", "PASS:zero-apr", "PASS:rounding", "PASS:final-payment"));

    private final Check check;
    private final Class<? extends CheckRule> evaluator;
    private final List<String> sources;
    private final List<String> configKeys;
    private final Set<ExternalApi> apis;
    private final List<String> requiredSides;

    RuleCatalog(Check check, Class<? extends CheckRule> evaluator, List<Object> sources,
                List<String> configKeys, Set<ExternalApi> apis, List<String> requiredSides) {
        this.check = check;
        this.evaluator = evaluator;
        this.sources = sources.stream().map(RuleCatalog::className).toList();
        this.configKeys = configKeys;
        this.apis = apis;
        this.requiredSides = requiredSides;
    }

    public Check check() { return check; }
    public Class<? extends CheckRule> evaluator() { return evaluator; }
    /** Fully qualified names of the classes whose source makes up this rule's fingerprint. */
    public List<String> sources() { return sources; }
    public List<String> configKeys() { return configKeys; }
    public Set<ExternalApi> apis() { return apis; }
    public List<String> requiredSides() { return requiredSides; }

    // FieldReader is package-private; name it as a string.
    private static String fieldReader() {
        return "com.funding.domain.rules.FieldReader";
    }

    private static String className(Object o) {
        return o instanceof Class<?> c ? c.getName() : (String) o;
    }
}
