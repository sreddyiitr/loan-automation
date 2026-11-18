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

/** {@code test/data/mocks/<api>.catalog.yaml}: bands / fault classes and dimensions, as intent. */
public record MockCatalog(ExternalApi api, String contract, List<String> amounts, Map<String, ClassSpec> classes) {

    public record ClassSpec(String expect, String k, boolean violatesContract) {}

    public static MockCatalog load(Path file) {
        try {
            JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
            List<String> amounts = new ArrayList<>();
            n.path("amounts").forEach(a -> amounts.add(a.asText()));
            Map<String, ClassSpec> classes = new LinkedHashMap<>();
            n.path("classes").fields().forEachRemaining(e -> classes.put(e.getKey(), new ClassSpec(
                    e.getValue().path("expect").asText(),
                    e.getValue().hasNonNull("k") ? e.getValue().get("k").asText() : null,
                    e.getValue().path("violatesContract").asBoolean(false))));
            return new MockCatalog(ExternalApi.valueOf(n.path("api").asText()), n.path("contract").asText(),
                    List.copyOf(amounts), classes);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
