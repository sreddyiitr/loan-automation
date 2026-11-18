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
            "id", "title", "rules", "side", "variant", "spec", "summary", "given", "externals", "expect");
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
        JsonNode e = n.path("expect");
        Map<Check, Outcome> checks = new EnumMap<>(Check.class);
        e.path("checks").fields().forEachRemaining(f ->
                checks.put(Check.valueOf(f.getKey()), Outcome.valueOf(f.getValue().asText())));
        List<String> stips = new ArrayList<>();
        e.path("stips").forEach(s -> stips.add(s.asText()));
        ScenarioIntent.Expect expect = new ScenarioIntent.Expect(checks, text(e, "status", name + " expect"), stips);

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
                name,
                sha256(file));
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
