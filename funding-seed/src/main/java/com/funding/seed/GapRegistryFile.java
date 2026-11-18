package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.domain.GapForm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** {@code test/data/gap-form-registry.yaml}: the GAP form registry fixture (US-6). */
public final class GapRegistryFile {
    private GapRegistryFile() {}

    public static List<GapForm> load(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<GapForm> out = new ArrayList<>();
        for (JsonNode f : ScenarioLoader.read(file).path("forms")) {
            out.add(new GapForm(f.path("state").asText(), f.path("form").asText(),
                    LocalDate.parse(f.path("from").asText()),
                    f.hasNonNull("to") ? LocalDate.parse(f.path("to").asText()) : null));
        }
        return List.copyOf(out);
    }
}
