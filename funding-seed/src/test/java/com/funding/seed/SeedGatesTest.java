package com.funding.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.RulesConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Constitution P-2: the real repository's scenario data passes every gate (so {@code mvn test} fails
 * on stale data), and each gate actually fails, naming what is wrong, on a broken fixture.
 */
class SeedGatesTest {

    private static final SeedConfig CONFIG = SeedConfig.fromDefaultsAndSystem("local");

    @TempDir
    Path tmp;
    private SeedPaths sandbox;

    @Test
    void P_2_repositoryScenariosPassAllGates() {
        List<GateResult> results = new SeedGates(SeedPaths.discover(), CONFIG, RuleFingerprints.fromClasspath()).verifyAll();
        assertThat(SeedGates.allPassed(results)).as(SeedGates.format(results)).isTrue();
    }

    @BeforeEach
    void copyRepoIntoSandbox() throws IOException {
        SeedPaths repo = SeedPaths.discover();
        sandbox = new SeedPaths(tmp);
        Files.createDirectories(sandbox.scenarios());
        Files.createDirectories(sandbox.seedDataDoc().getParent());
        try (var files = Files.list(repo.scenarios())) {
            for (Path f : files.toList()) {
                Files.copy(f, sandbox.scenarios().resolve(f.getFileName()));
            }
        }
        Files.copy(repo.seedDataDoc(), sandbox.seedDataDoc());
        Files.copy(repo.rulesLock(), sandbox.rulesLock());
        Files.copy(repo.mocksLock(), sandbox.mocksLock());
        Files.copy(repo.gapRegistry(), sandbox.gapRegistry());
        for (Path dir : List.of(repo.mockCatalogs(), repo.contracts())) {
            Path target = tmp.resolve(repo.root().relativize(dir));
            Files.createDirectories(target);
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.copy(f, target.resolve(f.getFileName()));
                }
            }
        }
    }

    private List<GateResult> sandboxGates() {
        return new SeedGates(sandbox, CONFIG, RuleFingerprints.fromClasspath()).verifyAll();
    }

    private static GateResult gate(List<GateResult> results, String name) {
        return results.stream().filter(r -> r.gate().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void P_2_3_conformanceNamesScenarioRuleExpectedAndActual() throws IOException {
        // Declare the broken-sum scenario as PASS: the real engine says FAIL.
        Path f = sandbox.scenarios().resolve("LA-1002-tila-sum-broken.yaml");
        Files.writeString(f, Files.readString(f).replace("checks: { TILA: FAIL }", "checks: { TILA: PASS }"));

        GateResult conformance = gate(sandboxGates(), "conformance");
        assertThat(conformance.passed()).isFalse();
        assertThat(conformance.failures()).anySatisfy(msg -> assertThat(msg)
                .contains("LA-1002").contains("TILA_SUM").contains("expected PASS")
                .contains("returned FAIL").contains("33802.60"));
    }

    @Test
    void P_2_4_coverageGateFailsForRuleWithoutScenarios() throws IOException {
        Files.delete(sandbox.scenarios().resolve("LA-1004-zero-apr-promo.yaml"));

        GateResult coverage = gate(sandboxGates(), "coverage");
        assertThat(coverage.failures()).contains("TILA_PAYMENT: no scenario covers side PASS:zero-apr");
    }

    @Test
    void P_2_4_coverageGateRejectsSideThatContradictsExpectation() throws IOException {
        Path f = sandbox.scenarios().resolve("LA-1002-tila-sum-broken.yaml");
        Files.writeString(f, Files.readString(f).replace("side: FAIL", "side: PASS"));

        assertThat(gate(sandboxGates(), "coverage").failures())
                .anySatisfy(msg -> assertThat(msg).contains("LA-1002").contains("claims TILA_SUM PASS"));
    }

    @Test
    void P_2_5_lockGateFailsWhenRuleConfigChanges() {
        RulesConfig changed = RulesConfig.defaults().with("funding.tila.payment-tolerance", "0.02");
        SeedConfig cfg = new SeedConfig(changed, CONFIG.properties(), "local");
        List<GateResult> results = new SeedGates(sandbox, cfg, RuleFingerprints.fromClasspath()).verifyAll();

        assertThat(gate(results, "lock").failures()).anySatisfy(msg -> assertThat(msg)
                .startsWith("TILA_PAYMENT: rule source or config changed").contains("make seed-regenerate"));
        // TILA_SUM doesn't read payment-tolerance, so its fingerprint is unchanged.
        assertThat(gate(results, "lock").failures()).noneMatch(msg -> msg.startsWith("TILA_SUM"));
    }

    @Test
    void P_2_5_lockGateFailsWhenScenarioIntentChanges() throws IOException {
        Path f = sandbox.scenarios().resolve("LA-1002-tila-sum-broken.yaml");
        Files.writeString(f, Files.readString(f).replace("+10.00", "+20.00"));

        assertThat(gate(sandboxGates(), "lock").failures())
                .anySatisfy(msg -> assertThat(msg).startsWith("TILA_SUM: scenario intents changed"));
    }

    @Test
    void P_2_7_docsGateFailsWhenTableIsStale() throws IOException {
        Path doc = sandbox.seedDataDoc();
        Files.writeString(doc, Files.readString(doc).replace("| LA-1001 | Happy path |", "| LA-1001 | Happy path (edited) |"));

        assertThat(gate(sandboxGates(), "docs").passed()).isFalse();
    }

    @Test
    void P_2_2_unknownIntentKeyFailsGeneration() throws IOException {
        Path f = sandbox.scenarios().resolve("LA-1002-tila-sum-broken.yaml");
        Files.writeString(f, Files.readString(f).replace("totalOfPayments:", "totalOfPayment:"));

        org.assertj.core.api.Assertions.assertThatThrownBy(this::sandboxGates)
                .hasMessageContaining("unknown intent key 'risc.totalOfPayment'");
    }

    @Test
    void P_2_1_derivedNumbersComeFromTheCalculator() {
        SeedGates.Generated g = new SeedGates(SeedPaths.discover(), CONFIG, RuleFingerprints.fromClasspath()).generate();
        Fixture.Document risc = g.fixtures().stream().filter(x -> x.scenarioId().equals("LA-1001")).findFirst()
                .orElseThrow().latest().latestDocuments().get(0);
        assertThat(risc.value("monthly_payment")).isEqualTo("563.21");
        assertThat(risc.value("total_of_payments")).isEqualTo("33792.60");
        assertThat(risc.value("finance_charge")).isEqualTo("5342.60");
    }
}
