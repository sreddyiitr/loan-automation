package com.funding.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * The {@code funding.*} values the rules read. Built from the same source by the app (Spring
 * Environment) and the seed generator, so both evaluate with identical config (P-2.1).
 */
public record RulesConfig(BigDecimal tilaSumTolerance, BigDecimal tilaPaymentTolerance) {

    public static final String DEFAULTS_RESOURCE = "funding-rules.properties";

    public RulesConfig {
        Objects.requireNonNull(tilaSumTolerance, "funding.tila.sum-tolerance");
        Objects.requireNonNull(tilaPaymentTolerance, "funding.tila.payment-tolerance");
    }

    /** {@code lookup} resolves a property key to its effective value (or null). */
    public static RulesConfig from(Function<String, String> lookup) {
        return new RulesConfig(
                decimal(lookup, "funding.tila.sum-tolerance"),
                decimal(lookup, "funding.tila.payment-tolerance"));
    }

    public static RulesConfig defaults() {
        return from(defaultProperties()::getProperty);
    }

    public static Properties defaultProperties() {
        Properties p = new Properties();
        try (InputStream in = RulesConfig.class.getClassLoader().getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " not on the classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** Effective value of one key, as the lock gate fingerprints it. */
    public String value(String key) {
        return switch (key) {
            case "funding.tila.sum-tolerance" -> tilaSumTolerance.toPlainString();
            case "funding.tila.payment-tolerance" -> tilaPaymentTolerance.toPlainString();
            default -> throw new IllegalArgumentException("unknown rule config key " + key);
        };
    }

    private static BigDecimal decimal(Function<String, String> lookup, String key) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing rule config " + key);
        }
        return new BigDecimal(v.trim());
    }
}
