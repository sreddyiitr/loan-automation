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

    // ------------------------------------------------------------------ S5 versioning vocabulary

    private static Fixture fixture(String id) {
        return new SeedGates(SeedPaths.discover(), CONFIG, RuleFingerprints.fromClasspath()).generate().fixtures().stream()
                .filter(x -> x.scenarioId().equals(id)).findFirst().orElseThrow();
    }

    private static Fixture.Document doc(List<Fixture.Document> docs, com.funding.domain.DocumentType type, int version) {
        return docs.stream().filter(d -> d.type() == type && d.documentVersion() == version).findFirst().orElseThrow();
    }

    @Test
    void US_9_historyIsAnEarlierNonLatestVersionWithItsOwnData() {
        Fixture f = fixture("LA-1028");
        assertThat(f.versions()).extracting(Fixture.Version::versionNumber, Fixture.Version::latest, Fixture.Version::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, false, "STIPULATED"),
                        org.assertj.core.groups.Tuple.tuple(2, true, "DRAFT"));
        assertThat(doc(f.history().get(0).documents(), com.funding.domain.DocumentType.BUYERS_ORDER, 1).value("total_vehicle_cash_price"))
                .isEqualTo("31450.01"); // LA-1006's deal: RISC price + tolerance + 0.01
        assertThat(doc(f.latest().documents(), com.funding.domain.DocumentType.BUYERS_ORDER, 1).value("total_vehicle_cash_price"))
                .isEqualTo("31450.00");
    }

    @Test
    void AS_9_4_priorDocumentIsVersionOneAndNotLatest() {
        List<Fixture.Document> docs = fixture("LA-1029").latest().documents();
        Fixture.Document v1 = doc(docs, com.funding.domain.DocumentType.RISC, 1);
        Fixture.Document v2 = doc(docs, com.funding.domain.DocumentType.RISC, 2);
        assertThat(v1.latest()).isFalse();
        assertThat(v2.latest()).isTrue();
        assertThat(v1.value("total_of_payments")).isEqualTo("33802.60"); // baseline 33792.60 + 10.00
        assertThat(v2.value("total_of_payments")).isEqualTo("33792.60");
        assertThat(v1.fileName()).endsWith("-risc-v1.pdf");
        assertThat(v2.fileName()).endsWith("-risc-v2.pdf");
        assertThat(doc(docs, com.funding.domain.DocumentType.BUYERS_ORDER, 1).latest()).isTrue(); // untouched documents stay v1
    }

    @Test
    void AS_1_8_amendmentIsTheNextVersionWithTheScenariosOverrideDropped() {
        Fixture f = fixture("LA-1032");
        assertThat(f.amendments()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(com.funding.domain.DocumentType.BUYERS_ORDER);
            assertThat(a.documentVersion()).isEqualTo(2);
            assertThat(a.value("total_vehicle_cash_price")).isEqualTo("31450.00");
        });
        assertThat(fixture("LA-1002").amendments()).singleElement()
                .satisfies(a -> assertThat(a.value("total_of_payments")).isEqualTo("33792.60"));
    }

    @Test
    void P_2_3_conformanceChecksHistoryAndAmendExpectations() throws IOException {
        Path h = sandbox.scenarios().resolve("LA-1028-resubmission-fixes-the-deal.yaml");
        Files.writeString(h, Files.readString(h).replace("      stips: [STIP_PRICE_MISMATCH]", "      stips: []"));
        Path a = sandbox.scenarios().resolve("LA-1032-doc-fix-triggers-rerun.yaml");
        Files.writeString(a, Files.readString(a).replace("    status: FUNDED", "    status: STIPULATED"));

        assertThat(gate(sandboxGates(), "conformance").failures())
                .anySatisfy(m -> assertThat(m).contains("LA-1028 history v1").contains("STIP_PRICE_MISMATCH"))
                .anySatisfy(m -> assertThat(m).contains("LA-1032 after amend [buyersOrder]").contains("lead to FUNDED"));
    }

    @Test
    void versioningKeysAreValidated() throws IOException {
        Path f = sandbox.scenarios().resolve("LA-1029-new-document-version.yaml");
        Files.writeString(f, Files.readString(f).replace("priorDocuments:\n  risc:", "priorDocuments:\n  paystub:"));
        org.assertj.core.api.Assertions.assertThatThrownBy(this::sandboxGates)
                .hasMessageContaining("priorDocuments.paystub is not one of");
    }
}
