package com.funding.api.seed;

import com.funding.domain.RuleCatalog;
import com.funding.persistence.GapRegistryRepository;
import com.funding.seed.Fixture;
import com.funding.seed.GateResult;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code local} only (constitution P-2.6): runs every seed gate first and refuses to seed if one
 * fails, naming the stale scenario. Idempotent per scenario fingerprint; a stale scenario is
 * deleted and re-seeded, so the local DB never keeps data from old rules. Latest versions are
 * seeded DRAFT, so nothing triggers until {@code /api/v1/dev/applications/{id}/submit}; earlier
 * versions are seeded with their final status and their result and stip rows, and never trigger.
 */
@Component
@Profile("local")
@Order(10)
@ConditionalOnProperty(name = "funding.seed-data", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SeedDataLoader.class);

    private final SeedGates gates;
    private final SeedConfig config;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final boolean volume;

    @org.springframework.beans.factory.annotation.Autowired
    public SeedDataLoader(SeedGates gates, SeedConfig config, JdbcClient jdbc, TransactionTemplate tx,
                          @org.springframework.beans.factory.annotation.Value("${funding.seed.volume:false}") boolean volume) {
        this.gates = gates;
        this.config = config;
        this.jdbc = jdbc;
        this.tx = tx;
        this.volume = volume;
    }

    public SeedDataLoader(SeedGates gates, SeedConfig config, JdbcClient jdbc, TransactionTemplate tx) {
        this(gates, config, jdbc, tx, false);
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    public record Summary(int inserted, int replaced, int unchanged) {}

    public Summary load() {
        List<GateResult> results = gates.verifyAll();
        if (!SeedGates.allPassed(results)) {
            throw new IllegalStateException("refusing to seed: seed gates failed (constitution P-2.6)\n"
                    + SeedGates.format(results)
                    + "Hint: run `make seed-regenerate` after updating the scenario intent.");
        }
        SeedGates.Generated g = gates.generate();
        // T206: the GAP registry is seed-owned locally; replace it so it always matches the rules' fixture.
        if (!g.fixtures().isEmpty()) {
            tx.executeWithoutResult(s -> new GapRegistryRepository(jdbc).replaceAll(g.fixtures().get(0).gapForms()));
        }
        RuleFingerprints fp = RuleFingerprints.fromClasspath();
        List<Fixture> fixtures = new java.util.ArrayList<>(g.fixtures());
        if (volume) { // T603: one application per catalog entry, after the gates passed
            new com.funding.seed.VolumeSet(config, com.funding.seed.SeedPaths.discover()).cases()
                    .forEach(c -> fixtures.add(c.fixture()));
        }
        int inserted = 0, replaced = 0, unchanged = 0;
        for (Fixture f : fixtures) {
            String fingerprint = fingerprint(f, g.baselineHash(), fp);
            Optional<String> stored = jdbc.sql("SELECT fingerprint FROM seed_scenarios WHERE loan_application_id = ?")
                    .param(f.loanApplicationId()).query(String.class).optional();
            if (stored.isPresent() && stored.get().equals(fingerprint)) {
                unchanged++;
                continue;
            }
            boolean exists = stored.isPresent() || jdbc.sql("SELECT count(*) FROM loan_applications WHERE loan_application_id = ?")
                    .param(f.loanApplicationId()).query(Integer.class).single() > 0;
            tx.executeWithoutResult(s -> {
                if (exists) {
                    delete(f.loanApplicationId());
                }
                FixtureRows.insert(jdbc, gates, f); // earlier versions get their own result and stip rows (S5)
                jdbc.sql("INSERT INTO seed_scenarios (loan_application_id, scenario_id, fingerprint) VALUES (?, ?, ?)")
                        .params(f.loanApplicationId(), f.scenarioId(), fingerprint).update();
            });
            if (exists) {
                replaced++;
                LOG.info("re-seeded {} (scenario intent, rules or config changed)", f.scenarioId());
            } else {
                inserted++;
            }
        }
        LOG.info("seed data: {} inserted, {} replaced, {} unchanged", inserted, replaced, unchanged);
        return new Summary(inserted, replaced, unchanged);
    }

    private void delete(String loanApplicationId) {
        jdbc.sql("DELETE FROM disbursements WHERE application_version_id IN (SELECT application_version_id FROM application_versions WHERE loan_application_id = ?)")
                .param(loanApplicationId).update();
        jdbc.sql("DELETE FROM loan_applications WHERE loan_application_id = ?").param(loanApplicationId).update();
    }

    /** Scenario intent + baseline + fingerprints of the rules it exercises + env. */
    String fingerprint(Fixture f, String baselineHash, RuleFingerprints fp) {
        StringBuilder sb = new StringBuilder(f.intent().intentHash()).append('|').append(baselineHash).append('|').append(config.env());
        for (String r : f.intent().rules()) {
            sb.append('|').append(r).append('=').append(fp.fingerprint(RuleCatalog.valueOf(r), config.rules()));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
