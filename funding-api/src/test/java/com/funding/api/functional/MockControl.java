package com.funding.api.functional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.domain.ExternalApi;
import com.funding.seed.Fixture;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedPaths;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockCatalog;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStub;
import com.funding.seed.mock.MockStubGenerator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * T313: switches one scenario's external API to another <b>generated</b> intent on the shared
 * WireMock, restores it, and counts its calls. It never builds a response itself: every stub comes
 * from {@link MockStubGenerator#scenarioStub}, which rejects an intent the catalog doesn't define
 * (P-5.1). Only this env's mappings for that one scenario are touched.
 */
final class MockControl {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String env;
    private final String wiremockUrl;
    private final MockStubGenerator generator;
    private final Map<ExternalApi, MockCatalog> catalogs;
    private final List<Fixture> fixtures = new java.util.concurrent.CopyOnWriteArrayList<>();

    MockControl(String env, String wiremockUrl, SeedConfig config, List<Fixture> fixtures) {
        this.env = env;
        this.wiremockUrl = wiremockUrl.replaceAll("/+$", "");
        this.generator = new MockStubGenerator(env, MockBudget.from(config), config.rules().incomeVarianceThreshold());
        this.catalogs = MockSet.catalogs(SeedPaths.discover());
        this.fixtures.addAll(fixtures);
    }

    /**
     * A copy of a generated scenario under {@code <scenarioId>-<suffix>} (its own application ID and
     * SSN token), so a test can act on it without competing for the seeded one. Its stubs come from
     * the same generator, for the copy (P-5.1); write its rows with {@code FixtureRows}.
     */
    Fixture cloneScenario(String scenarioId, String suffix) throws IOException, InterruptedException {
        Fixture f = fixture(scenarioId);
        String id = scenarioId + "-" + suffix;
        Fixture copy = f.withId(id, v -> new Fixture.Version(v.versionNumber(), v.latest(), v.status(),
                v.borrowerName(), v.coBorrowerName(), "enc:" + env + ":" + id, v.statedIncomeAnnual(), v.vehicleVin(),
                v.dealershipName(), v.dealershipId(), v.documents()));
        fixtures.add(copy);
        for (ExternalApi api : ExternalApi.values()) {
            String intent = copy.externals().get(MockStubGenerator.externalsKey(api));
            if (intent != null) {
                swap(id, api, intent);
            }
        }
        return copy;
    }

    /** Replaces the scenario's stub for {@code api} with the generated stub for {@code intent}; returns it. */
    MockStub swap(String scenarioId, ExternalApi api, String intent) throws IOException, InterruptedException {
        MockStub stub = generator.scenarioStub(fixture(scenarioId), api, intent, catalogs); // throws for a non-generated intent
        replace(stub);
        return stub;
    }

    /** Restores the scenario's own intent from its {@code externals:} (the stub the loader put there). */
    MockStub reset(String scenarioId, ExternalApi api) throws IOException, InterruptedException {
        Fixture f = fixture(scenarioId);
        return swap(scenarioId, api, f.externals().get(MockStubGenerator.externalsKey(api)));
    }

    /** Requests the scenario's stub for {@code api} has received (matched on the stub's own request pattern). */
    int calls(String scenarioId, ExternalApi api) throws IOException, InterruptedException {
        MockStub stub = generator.scenarioStub(fixture(scenarioId), api, fixture(scenarioId).externals().get(MockStubGenerator.externalsKey(api)), catalogs);
        JsonNode r = JSON.readTree(send("POST", "/__admin/requests/count", JSON.writeValueAsString(MockStubGenerator.request(stub))));
        return r.path("count").asInt();
    }

    private void replace(MockStub stub) throws IOException, InterruptedException {
        for (JsonNode m : JSON.readTree(send("GET", "/__admin/mappings?limit=100000", null)).path("mappings")) {
            JsonNode meta = m.path("metadata");
            if (env.equals(meta.path("env").asText()) && stub.id().equals(meta.path("stubId").asText())) {
                send("DELETE", "/__admin/mappings/" + m.path("id").asText(), null);
            }
        }
        // WireMock drops a scenario once its last mapping is removed, so a multi-call stub starts again at "Started".
        send("POST", "/__admin/mappings/import", generator.toWireMockImport(List.of(stub)));
    }

    Fixture fixture(String scenarioId) {
        return fixtures.stream().filter(f -> f.scenarioId().equals(scenarioId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no generated scenario " + scenarioId));
    }

    private String send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(wiremockUrl + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("WireMock " + method + " " + path + " returned " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }
}
