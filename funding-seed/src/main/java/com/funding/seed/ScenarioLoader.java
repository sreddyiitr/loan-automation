package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.funding.domain.Check;
import com.funding.domain.Outcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Reads {@code test/data/scenarios/*.yaml}. Unknown top-level keys are an error, so typos can't pass silently. */
public final class ScenarioLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> TOP_LEVEL = Set.of(
            "id", "title", "rules", "side", "variant", "spec", "summary", "given", "externals", "expect",
            "history", "priorDocuments", "amend");
    static final Set<String> DOCUMENT_KEYS = Set.of("risc", "buyersOrder", "gap");
    private static final Set<String> SIDES = Set.of(
            "PASS", "FAIL", "PASS_BOUNDARY", "FAIL_BOUNDARY", "NEEDS_REVIEW", "ERROR");

    private final SeedPaths paths;

    public ScenarioLoader(SeedPaths paths) {
        this.paths = paths;
    }

    public JsonNode baseline() {
        return read(paths.baseline());
    }

    public List<ScenarioIntent> scenarios() {
        try (Stream<Path> files = Files.list(paths.scenarios())) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .map(this::parse)
                    .sorted((a, b) -> a.id().compareTo(b.id()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ScenarioIntent parse(Path file) {
        JsonNode n = read(file);
        String name = file.getFileName().toString();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!TOP_LEVEL.contains(k)) {
                throw new IllegalArgumentException(name + ": unknown key '" + k + "'");
            }
        }
        String id = text(n, "id", name);
        if (!name.startsWith(id + "-") && !name.equals(id + ".yaml")) {
            throw new IllegalArgumentException(name + ": file name must start with the scenario id " + id);
        }
        String side = text(n, "side", name);
        if (!SIDES.contains(side)) {
            throw new IllegalArgumentException(name + ": side must be one of " + SIDES);
        }
        List<String> rules = new ArrayList<>();
        n.path("rules").forEach(r -> rules.add(r.asText()));
        if (rules.isEmpty()) {
            throw new IllegalArgumentException(name + ": rules must list at least one RuleCatalog id");
        }
        ScenarioIntent.Expect expect = expect(n.path("expect"), name + " expect");
        List<ScenarioIntent.History> history = new ArrayList<>();
        int h = 0;
        for (JsonNode v : n.path("history")) {
            h++;
            history.add(new ScenarioIntent.History(v.has("given") ? v.get("given") : MissingNode.getInstance(),
                    expect(v.path("expect"), name + " history[" + h + "] expect")));
        }
        JsonNode prior = n.has("priorDocuments") ? n.get("priorDocuments") : MissingNode.getInstance();
        prior.fieldNames().forEachRemaining(k -> {
            if (!DOCUMENT_KEYS.contains(k)) {
                throw new IllegalArgumentException(name + ": priorDocuments." + k + " is not one of " + DOCUMENT_KEYS);
            }
        });
        ScenarioIntent.Amend amend = null;
        if (n.has("amend")) {
            List<String> docs = new ArrayList<>();
            n.get("amend").path("documents").forEach(d -> docs.add(d.asText()));
            if (docs.isEmpty() || !DOCUMENT_KEYS.containsAll(docs)) {
                throw new IllegalArgumentException(name + ": amend.documents must list some of " + DOCUMENT_KEYS);
            }
            amend = new ScenarioIntent.Amend(List.copyOf(docs), expect(n.get("amend").path("expect"), name + " amend expect"));
        }

        return new ScenarioIntent(
                id,
                text(n, "title", name),
                List.copyOf(rules),
                side,
                n.hasNonNull("variant") ? n.get("variant").asText() : null,
                n.path("spec").asText(""),
                n.path("summary").asText("—"),
                n.has("given") ? n.get("given") : MissingNode.getInstance(),
                n.has("externals") ? n.get("externals") : MissingNode.getInstance(),
                expect,
                List.copyOf(history),
                prior,
                amend,
                name,
                sha256(file));
    }

    private static ScenarioIntent.Expect expect(JsonNode e, String where) {
        Map<Check, Outcome> checks = new EnumMap<>(Check.class);
        e.path("checks").fields().forEachRemaining(f ->
                checks.put(Check.valueOf(f.getKey()), Outcome.valueOf(f.getValue().asText())));
        List<String> stips = new ArrayList<>();
        e.path("stips").forEach(s -> stips.add(s.asText()));
        return new ScenarioIntent.Expect(checks, text(e, "status", where), stips);
    }

    private static String text(JsonNode n, String key, String where) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException(where + ": missing '" + key + "'");
        }
        return v.asText();
    }

    static JsonNode read(Path file) {
        try {
            return YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static String sha256(Path file) {
        try {
            String text = Files.readString(file).replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
