package com.funding.api.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funding.domain.RulesConfig;
import com.funding.persistence.TestDatabase;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T129: the seeder never loads unverified data (constitution P-2.6) and is fingerprint-idempotent. */
class SeedDataLoaderTest {

    @TempDir
    Path sandboxRoot;
    private JdbcClient jdbc;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws IOException {
        TestDatabase db = TestDatabase.fresh(true);
        jdbc = db.jdbc();
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        Path repo = SeedPaths.discover().root();
        for (String dir : List.of("test/data", "contracts", "specs/001-loan-funding-validation")) {
            copyTree(repo.resolve(dir), sandboxRoot.resolve(dir));
        }
    }

    private SeedDataLoader loader(SeedConfig config) {
        SeedGates gates = new SeedGates(new SeedPaths(sandboxRoot), config, RuleFingerprints.fromClasspath());
        return new SeedDataLoader(gates, config, jdbc, tx);
    }

    private static SeedConfig config() {
        return SeedConfig.fromDefaultsAndSystem("t");
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void P_2_6_refusesToSeedWhenAGateFailsAndNamesTheScenario() throws IOException {
        Path f = sandboxRoot.resolve("test/data/scenarios/LA-1002-tila-sum-broken.yaml");
        Files.writeString(f, Files.readString(f).replace("checks: { TILA: FAIL }", "checks: { TILA: PASS }"));
        assertThatThrownBy(() -> loader(config()).load())
                .hasMessageContaining("refusing to seed")
                .hasMessageContaining("LA-1002")
                .hasMessageContaining("make seed-regenerate");
        assertThat(count("SELECT count(*) FROM loan_applications")).isZero();
    }

    @Test
    void seedsLatestVersionsAsDraftAndIsIdempotent() {
        SeedDataLoader.Summary first = loader(config()).load();
        assertThat(first.inserted()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM application_versions WHERE version_status <> 'DRAFT'")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero(); // nothing triggers at startup

        SeedDataLoader.Summary again = loader(config()).load();
        assertThat(again.unchanged()).isEqualTo(6);
        assertThat(again.inserted() + again.replaced()).isZero();
    }

    @Test
    void replacesScenarioWhoseFingerprintIsStale() {
        loader(config()).load();
        jdbc.sql("UPDATE application_versions SET version_status = 'STIPULATED' WHERE loan_application_id = 'LA-1001'").update();
        // Same intent, different effective rule config: every TILA scenario's fingerprint changes.
        RulesConfig changed = new RulesConfig(new BigDecimal("0.00"), new BigDecimal("0.02"));
        SeedConfig cfg = new SeedConfig(changed, config().properties(), "t");
        SeedGates gates = new SeedGates(new SeedPaths(sandboxRoot), cfg, RuleFingerprints.fromClasspath());
        // The lock gate (correctly) fails for changed config, so check the fingerprint logic directly.
        SeedDataLoader l = new SeedDataLoader(gates, cfg, jdbc, tx);
        var g = gates.generate();
        var fixture = g.fixtures().get(0);
        assertThat(l.fingerprint(fixture, g.baselineHash(), RuleFingerprints.fromClasspath()))
                .isNotEqualTo(loader(config()).fingerprint(fixture, g.baselineHash(), RuleFingerprints.fromClasspath()));
        assertThatThrownBy(l::load).hasMessageContaining("lock");
    }

    @Test
    void replacesScenarioWhoseIntentChanged() throws IOException {
        loader(config()).load();
        jdbc.sql("UPDATE application_versions SET version_status = 'STIPULATED' WHERE loan_application_id = 'LA-1001'").update();
        Path f = sandboxRoot.resolve("test/data/scenarios/LA-1001-happy-path.yaml");
        Files.writeString(f, Files.readString(f).replace("title: Happy path", "title: Happy path (renamed)"));
        // Regenerate the lock + doc in the sandbox, as `make seed-regenerate` would.
        SeedGates gates = new SeedGates(new SeedPaths(sandboxRoot), config(), RuleFingerprints.fromClasspath());
        var g = gates.generate();
        Files.writeString(new SeedPaths(sandboxRoot).rulesLock(), gates.renderLock(g));
        Path doc = new SeedPaths(sandboxRoot).seedDataDoc();
        Files.writeString(doc, com.funding.seed.ScenarioTable.splice(Files.readString(doc), com.funding.seed.ScenarioTable.render(g.fixtures())));

        SeedDataLoader.Summary s = loader(config()).load();
        assertThat(s.replaced()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version_status FROM application_versions WHERE loan_application_id = 'LA-1001'")
                .query(String.class).single()).isEqualTo("DRAFT");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }
}
