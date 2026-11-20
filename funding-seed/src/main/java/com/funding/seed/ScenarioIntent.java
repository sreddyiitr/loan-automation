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
        List<History> history,
        JsonNode priorDocuments,
        Amend amend,
        String sourceFile,
        String intentHash) {

    /**
     * S5, US-9: an earlier application version of the same application, seeded non-latest with its
     * final status and its result and stip rows (never triggers). {@code given} is merged over the
     * baseline on its own (not over the scenario's {@code given}).
     */
    public record History(JsonNode given, Expect expect) {}

    /**
     * S5, AS-1.8: corrected documents a test adds later through the dev endpoint. Each named document
     * ({@code risc}, {@code buyersOrder}, {@code gap}) is regenerated with the scenario's own overrides
     * for that document dropped (baseline values); {@code expect} is the outcome after the re-run.
     */
    public record Amend(List<String> documents, Expect expect) {}

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
