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
public record RulesConfig(
        BigDecimal tilaSumTolerance,
        BigDecimal tilaPaymentTolerance,
        BigDecimal priceTolerance,
        BigDecimal confidenceFlagBelow,
        String primaryLenderName) {

    public static final String DEFAULTS_RESOURCE = "funding-rules.properties";

    /**
     * Fields added after a workflow history was recorded arrive as null when Temporal replays that
     * history (NFR-07). They are filled from the file defaults: the parent workflow only carries the
     * config to its children, so replay is unaffected. {@link #from} still rejects missing config.
     */
    public RulesConfig {
        Objects.requireNonNull(tilaSumTolerance, "funding.tila.sum-tolerance");
        Objects.requireNonNull(tilaPaymentTolerance, "funding.tila.payment-tolerance");
        priceTolerance = priceTolerance != null ? priceTolerance : new BigDecimal(defaultValue("funding.price.tolerance"));      // added in S2
        confidenceFlagBelow = confidenceFlagBelow != null ? confidenceFlagBelow : new BigDecimal(defaultValue("funding.confidence.flag-below")); // added in S2
        primaryLenderName = primaryLenderName != null ? primaryLenderName : defaultValue("funding.gap.primary-lender-name");     // added in S2
    }

    private static String defaultValue(String key) {
        return Objects.requireNonNull(defaultProperties().getProperty(key), key).trim();
    }

    /** {@code lookup} resolves a property key to its effective value (or null). */
    public static RulesConfig from(Function<String, String> lookup) {
        return new RulesConfig(
                decimal(lookup, "funding.tila.sum-tolerance"),
                decimal(lookup, "funding.tila.payment-tolerance"),
                decimal(lookup, "funding.price.tolerance"),
                decimal(lookup, "funding.confidence.flag-below"),
                text(lookup, "funding.gap.primary-lender-name"));
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

    /** A copy with one value changed (tests and what-if tooling). */
    public RulesConfig with(String key, String value) {
        return from(k -> k.equals(key) ? value : value(k));
    }

    /** Effective value of one key, as the lock gate fingerprints it. */
    public String value(String key) {
        return switch (key) {
            case "funding.tila.sum-tolerance" -> tilaSumTolerance.toPlainString();
            case "funding.tila.payment-tolerance" -> tilaPaymentTolerance.toPlainString();
            case "funding.price.tolerance" -> priceTolerance.toPlainString();
            case "funding.confidence.flag-below" -> confidenceFlagBelow.toPlainString();
            case "funding.gap.primary-lender-name" -> primaryLenderName;
            default -> throw new IllegalArgumentException("unknown rule config key " + key);
        };
    }

    private static BigDecimal decimal(Function<String, String> lookup, String key) {
        return new BigDecimal(text(lookup, key));
    }

    private static String text(Function<String, String> lookup, String key) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing rule config " + key);
        }
        return v.trim();
    }
}
