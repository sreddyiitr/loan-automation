package com.funding.seed;

import com.funding.domain.RulesConfig;
import java.util.Properties;
import java.util.function.Function;

/**
 * The effective configuration the generator resolves intents against: the same {@code funding.*}
 * values the app uses (P-2.1), plus the environment name for per-env mock keys (NFR-08).
 */
public record SeedConfig(RulesConfig rules, Function<String, String> properties, String env) {

    /** Defaults from {@code funding-rules.properties}, overridden by {@code -D} system properties. */
    public static SeedConfig fromDefaultsAndSystem(String env) {
        Properties defaults = RulesConfig.defaultProperties();
        Function<String, String> lookup = k -> System.getProperty(k, defaults.getProperty(k));
        return new SeedConfig(RulesConfig.from(lookup), lookup, env);
    }

    public String property(String key) {
        String v = properties.apply(key);
        if (v == null) {
            throw new IllegalStateException("missing config " + key);
        }
        return v;
    }

    public String primaryLender() {
        return property("funding.gap.primary-lender-name");
    }
}
