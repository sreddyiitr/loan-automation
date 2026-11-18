package com.funding.api.seed;

import com.funding.seed.GateResult;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockGates;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStub;
import com.funding.seed.mock.MockStubGenerator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code local} only (plan "WireMock"): generates this env's stubs (P-5.2), runs the contract gate,
 * then replaces <b>only this env's</b> stubs on the shared WireMock (metadata {@code env}), never
 * another project's.
 */
@Component
@Profile("local")
@Order(20)
@ConditionalOnProperty(name = "funding.mocks.load", havingValue = "true")
public class MockStubLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MockStubLoader.class);

    private final SeedGates gates;
    private final SeedConfig config;
    private final String wiremockUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public MockStubLoader(SeedGates gates, SeedConfig config, @Value("${funding.disbursement.base-url}") String wiremockUrl) {
        this.gates = gates;
        this.config = config;
        this.wiremockUrl = wiremockUrl.replaceAll("/+$", "");
    }

    @Override
    public void run(ApplicationArguments args) throws IOException, InterruptedException {
        load();
    }

    public int load() throws IOException, InterruptedException {
        SeedPaths paths = SeedPaths.discover();
        var catalogs = MockSet.catalogs(paths);
        MockBudget budget = MockBudget.from(config);
        List<MockStub> stubs = MockSet.generate(config.env(), budget, gates.generate().fixtures(), catalogs);
        GateResult contract = MockGates.contract(stubs, catalogs, paths.contracts());
        if (!contract.passed()) {
            throw new IllegalStateException("refusing to load WireMock stubs: contract gate failed\n" + String.join("\n", contract.failures()));
        }
        post("/__admin/mappings/remove-by-metadata",
                "{\"matchesJsonPath\":{\"expression\":\"$.env\",\"equalTo\":\"" + config.env() + "\"}}");
        post("/__admin/mappings/import", new MockStubGenerator(config.env(), budget).toWireMockImport(stubs));
        LOG.info("loaded {} generated stubs for env {} into {}", stubs.size(), config.env(), wiremockUrl);
        return stubs.size();
    }

    private void post(String path, String body) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(wiremockUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("WireMock " + path + " returned " + r.statusCode() + ": " + r.body());
        }
    }
}
