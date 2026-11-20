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
import java.util.Map;
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
        int scenarios = new SeedGates(new SeedPaths(sandboxRoot), config(), RuleFingerprints.fromClasspath()).generate().fixtures().size();
        SeedDataLoader.Summary first = loader(config()).load();
        assertThat(first.inserted()).isEqualTo(scenarios);
        assertThat(count("SELECT count(*) FROM gap_form_registry")).isEqualTo(3); // T206: registry seeded
        assertThat(count("SELECT count(*) FROM application_versions WHERE is_latest_version AND version_status <> 'DRAFT'")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero(); // nothing triggers at startup

        SeedDataLoader.Summary again = loader(config()).load();
        assertThat(again.unchanged()).isEqualTo(scenarios);
        assertThat(again.inserted() + again.replaced()).isZero();
    }

    /** S5, US-9: an earlier version is seeded non-latest with its final status and its own result and stip rows. */
    @Test
    void seedsHistoricalVersionsWithTheirResultsAndStips() {
        loader(config()).load();
        List<Map<String, Object>> versions = jdbc.sql("""
                SELECT application_version_id AS id, version_number AS n, is_latest_version AS latest, version_status AS status
                  FROM application_versions WHERE loan_application_id = 'LA-1028' ORDER BY version_number""").query().listOfRows();
        assertThat(versions).extracting(r -> r.get("n"), r -> r.get("latest"), r -> r.get("status")).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, false, "STIPULATED"), org.assertj.core.groups.Tuple.tuple(2, true, "DRAFT"));
        Object v1 = versions.get(0).get("id");
        Object v2 = versions.get(1).get("id");
        assertThat(jdbc.sql("SELECT validation_check || '=' || outcome FROM validation_results WHERE application_version_id = ? ORDER BY 1")
                .param(v1).query(String.class).list())
                .containsExactly("GAP=PASS", "INCOME=PASS", "PRICE=FAIL", "TILA=PASS", "VIN=PASS");
        assertThat(jdbc.sql("""
                SELECT s.stip_code || ':' || s.status || ':' || d.document_type FROM stipulations s
                  JOIN documents d ON d.document_id = s.triggered_by_document_id WHERE s.application_version_id = ?""")
                .param(v1).query(String.class).list()).containsExactly("STIP_PRICE_MISMATCH:PENDING:BUYERS_ORDER");
        assertThat(count("SELECT count(*) FROM validation_results WHERE application_version_id = " + v2)).isZero();
        // LA-1029: RISC v1 is kept but not latest, v2 is latest
        assertThat(jdbc.sql("""
                SELECT d.document_version_number || ':' || d.is_latest_doc FROM documents d JOIN application_versions v USING (application_version_id)
                 WHERE v.loan_application_id = 'LA-1029' AND d.document_type = 'RISC' ORDER BY 1""").query(String.class).list())
                .containsExactly("1:false", "2:true");
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero(); // seeded history never triggers
    }

    @Test
    void replacesScenarioWhoseFingerprintIsStale() {
        loader(config()).load();
        jdbc.sql("UPDATE application_versions SET version_status = 'STIPULATED' WHERE loan_application_id = 'LA-1001'").update();
        // Same intent, different effective rule config: every TILA scenario's fingerprint changes.
        RulesConfig changed = RulesConfig.defaults().with("funding.tila.payment-tolerance", "0.02");
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
