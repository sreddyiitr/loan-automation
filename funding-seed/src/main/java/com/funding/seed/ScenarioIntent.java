package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.domain.Check;
import com.funding.domain.Outcome;
import java.util.List;
import java.util.Map;

/**
 * A scenario's intent (constitution P-2.2): which rules and side it exercises, overrides relative
 * to the baseline, and the expected outcome. Never concrete derived numbers.
 */
public record ScenarioIntent(
        String id,
        String title,
        List<String> rules,
        String side,
        String variant,
        String spec,
        String summary,
        JsonNode given,
        JsonNode externals,
        Expect expect,
        String sourceFile,
        String intentHash) {

    public record Expect(Map<Check, Outcome> checks, String status, List<String> stips) {
        public Outcome check(Check c) {
            return checks.getOrDefault(c, Outcome.PASS);
        }
    }

    /** {@code SIDE} or {@code SIDE:variant}, as listed in {@code RuleCatalog.requiredSides()}. */
    public String sideKey() {
        return variant == null ? side : side + ":" + variant;
    }
}
