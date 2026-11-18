package com.funding.seed;

import com.funding.domain.RuleCatalog;
import com.funding.domain.RulesConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Rule fingerprint = SHA-256 of (rule source hash written at build time + effective config the rule
 * reads). Any change to either makes {@code rules.lock} stale (constitution P-2.5).
 */
public final class RuleFingerprints {
    public static final String RESOURCE = "META-INF/rule-fingerprints.properties";

    private final Properties sourceHashes;

    public RuleFingerprints(Properties sourceHashes) {
        this.sourceHashes = sourceHashes;
    }

    public static RuleFingerprints fromClasspath() {
        Properties p = new Properties();
        try (InputStream in = RuleCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " missing: build funding-domain with Maven first");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new RuleFingerprints(p);
    }

    public String sourceHash(RuleCatalog rule) {
        String h = sourceHashes.getProperty(rule.name());
        if (h == null) {
            throw new IllegalStateException(RESOURCE + " has no entry for " + rule + ": rebuild funding-domain");
        }
        return h;
    }

    public Map<String, String> config(RuleCatalog rule, RulesConfig config) {
        Map<String, String> m = new LinkedHashMap<>();
        rule.configKeys().stream().sorted().forEach(k -> m.put(k, config.value(k)));
        return m;
    }

    public String fingerprint(RuleCatalog rule, RulesConfig config) {
        StringBuilder sb = new StringBuilder(sourceHash(rule));
        config(rule, config).forEach((k, v) -> sb.append('\n').append(k).append('=').append(v));
        return sha256(sb.toString());
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
