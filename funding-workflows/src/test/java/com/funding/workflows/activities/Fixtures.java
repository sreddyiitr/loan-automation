package com.funding.workflows.activities;

import com.funding.persistence.ApplicationWriter;
import com.funding.seed.Fixture;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStubGenerator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test helper: generated scenario fixtures (P-2) written to a test DB, and generated stubs (P-5). */
final class Fixtures {
    static final String ENV = "test";
    static final SeedConfig CONFIG = SeedConfig.fromDefaultsAndSystem(ENV);
    private static final SeedGates.Generated GENERATED =
            new SeedGates(SeedPaths.discover(), CONFIG, RuleFingerprints.fromClasspath()).generate();

    private Fixtures() {}

    static Fixture scenario(String id) {
        return GENERATED.fixtures().stream().filter(f -> f.scenarioId().equals(id)).findFirst().orElseThrow();
    }

    /** Inserts the scenario and returns its latest version id. */
    static long insert(JdbcClient jdbc, String id) {
        Fixture f = scenario(id);
        List<ApplicationWriter.NewVersion> versions = f.versions().stream().map(v -> new ApplicationWriter.NewVersion(
                v.versionNumber(), v.latest(), v.status(), v.borrowerName(), v.coBorrowerName(), v.ssnItinToken(),
                v.statedIncomeAnnual(), v.vehicleVin(), v.dealershipName(), v.dealershipId(),
                v.documents().stream().map(d -> new ApplicationWriter.NewDocument(d.type().name(), d.documentVersion(),
                        d.latest(), d.fileName(), d.totalPages(), d.sections().stream().map(s -> new ApplicationWriter.NewSection(
                        s.name(), s.startPage(), s.endPage(), s.fields().stream().map(x -> new ApplicationWriter.NewField(
                        x.key(), x.value(), x.confidence(), x.boundingBox(), x.page())).toList())).toList())).toList())).toList();
        if (jdbc.sql("SELECT count(*) FROM gap_form_registry").query(Integer.class).single() == 0) {
            new com.funding.persistence.GapRegistryRepository(jdbc).replaceAll(f.gapForms());
        }
        List<Long> ids = new ApplicationWriter(jdbc).insert(f.loanApplicationId(), versions);
        return ids.get(f.versions().indexOf(f.latest()));
    }

    static String wireMockImport() {
        return new MockStubGenerator(ENV, MockBudget.from(CONFIG))
                .toWireMockImport(MockSet.generate(ENV, MockBudget.from(CONFIG), GENERATED.fixtures(), MockSet.catalogs(SeedPaths.discover())));
    }
}
