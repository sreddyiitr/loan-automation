package com.funding.seed.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.funding.domain.ExternalApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code test/data/mocks/<api>.catalog.yaml}: bands / fault classes, magnitudes and scenario intents, as intent. */
public record MockCatalog(ExternalApi api, String contract, List<String> magnitudes, Map<String, ClassSpec> classes,
                          Map<String, JsonNode> scenarioIntents) {

    /** {@code expect} is the class's oracle (null for composite classes like SHAPES); {@code raw} holds the rest. */
    public record ClassSpec(String expect, JsonNode raw) {
        public String k() {
            return raw.hasNonNull("k") ? raw.get("k").asText() : null;
        }

        public boolean violatesContract() {
            return raw.path("violatesContract").asBoolean(false);
        }

        public List<String> list(String key) {
            List<String> out = new ArrayList<>();
            raw.path(key).forEach(n -> out.add(n.asText()));
            return out;
        }
    }

    public static MockCatalog load(Path file) {
        try {
            JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
            List<String> magnitudes = new ArrayList<>();
            n.path("magnitudes").forEach(a -> magnitudes.add(a.asText()));
            Map<String, ClassSpec> classes = new LinkedHashMap<>();
            n.path("classes").fields().forEachRemaining(e -> classes.put(e.getKey(),
                    new ClassSpec(e.getValue().hasNonNull("expect") ? e.getValue().get("expect").asText() : null, e.getValue())));
            Map<String, JsonNode> intents = new LinkedHashMap<>();
            n.path("scenarioIntents").fields().forEachRemaining(e -> intents.put(e.getKey(), e.getValue()));
            return new MockCatalog(ExternalApi.valueOf(n.path("api").asText()), n.path("contract").asText(),
                    List.copyOf(magnitudes), classes, intents);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Verified income for an income scenario intent (null for intents that never return one). */
    public String verifiedFor(String intent) {
        JsonNode i = scenarioIntents.get(intent);
        return i == null || !i.hasNonNull("verified") ? null : i.get("verified").asText();
    }
}
