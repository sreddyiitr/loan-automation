package com.funding.seed.mock;

import com.funding.domain.ExternalApi;
import com.funding.seed.Fixture;
import com.funding.seed.SeedPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Loads every {@code test/data/mocks/*.catalog.yaml} and generates the full stub set for one env. */
public final class MockSet {
    private MockSet() {}

    public static Map<ExternalApi, MockCatalog> catalogs(SeedPaths paths) {
        Map<ExternalApi, MockCatalog> m = new EnumMap<>(ExternalApi.class);
        try (Stream<Path> files = Files.list(paths.mockCatalogs())) {
            files.filter(p -> p.getFileName().toString().endsWith(".catalog.yaml")).sorted()
                    .map(MockCatalog::load).forEach(c -> m.put(c.api(), c));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return m;
    }

    public static List<MockStub> generate(String env, MockBudget budget, java.math.BigDecimal varianceThreshold, List<Fixture> fixtures,
                                          Map<ExternalApi, MockCatalog> catalogs) {
        return new MockStubGenerator(env, budget, varianceThreshold).generate(fixtures, catalogs);
    }
}
