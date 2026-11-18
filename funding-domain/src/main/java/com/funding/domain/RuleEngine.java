package com.funding.domain;

import com.funding.domain.rules.CheckRule;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** {@code evaluate(Snapshot, RulesConfig)}: pure, no I/O. Used by the workflows and the seed gates alike. */
public final class RuleEngine {

    private final Map<Check, CheckRule> rules;

    public RuleEngine() {
        Map<Check, CheckRule> m = new EnumMap<>(Check.class);
        Arrays.stream(RuleCatalog.values()).filter(r -> r.check() != null)
                .forEach(r -> m.computeIfAbsent(r.check(), c -> instantiate(r)));
        this.rules = Collections.unmodifiableMap(m);
    }

    /** Checks that have a rule today. Checks without one aren't evaluated (slice-by-slice delivery). */
    public Set<Check> implementedChecks() {
        return rules.keySet();
    }

    public CheckOutcome evaluate(Check check, Snapshot snapshot, RulesConfig config) {
        CheckRule rule = rules.get(check);
        if (rule == null) {
            throw new IllegalArgumentException("no rule implements " + check);
        }
        return rule.evaluate(snapshot, config);
    }

    public Map<Check, CheckOutcome> evaluate(Snapshot snapshot, RulesConfig config) {
        Map<Check, CheckOutcome> out = new EnumMap<>(Check.class);
        rules.forEach((check, rule) -> out.put(check, rule.evaluate(snapshot, config)));
        return out;
    }

    private static CheckRule instantiate(RuleCatalog r) {
        try {
            return r.evaluator().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate " + r.evaluator(), e);
        }
    }
}
