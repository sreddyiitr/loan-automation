package com.funding.seed.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.domain.ExternalApi;
import com.funding.seed.GateResult;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Constitution P-5 gates over the generated Disbursement stubs. */
class MockGatesTest {

    private final SeedPaths paths = SeedPaths.discover();
    private final SeedConfig config = SeedConfig.fromDefaultsAndSystem("it-test");
    private Map<ExternalApi, MockCatalog> catalogs;
    private List<MockStub> stubs;
    private MockBudget budget;

    @BeforeEach
    void generate() {
        SeedGates gates = new SeedGates(paths, config, RuleFingerprints.fromClasspath());
        catalogs = MockSet.catalogs(paths);
        budget = MockBudget.from(config);
        stubs = MockSet.generate(config.env(), budget, config.rules().incomeVarianceThreshold(), gates.generate().fixtures(), catalogs);
    }

    @Test
    void P_5_1_everyGeneratedStubValidatesAgainstItsContract() {
        GateResult r = MockGates.contract(stubs, catalogs, paths.contracts());
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void P_5_1_contractGateRejectsBodyThatViolatesTheContract() {
        MockStub ok = byId("DISBURSEMENT/catalog/SUCCESS/28450.00");
        MockStub broken = withInteractions(ok, List.of(new Interaction(201, "application/json",
                "{\"status\":\"DONE\",\"reference\":\"x\"}", 0)), false);
        GateResult r = MockGates.contract(List.of(broken), catalogs, paths.contracts());
        assertThat(r.failures()).singleElement().asString().contains("violates disbursement.openapi.yaml");
    }

    @Test
    void P_5_1_violatesContractOnlyAllowedOnFaultClasses() {
        MockStub ok = byId("DISBURSEMENT/catalog/SUCCESS/28450.00");
        MockStub flagged = withInteractions(ok, List.of(new Interaction(201, "application/json", "{", 0)), true);
        GateResult r = MockGates.contract(List.of(flagged), catalogs, paths.contracts());
        assertThat(r.failures()).anySatisfy(m -> assertThat(m).contains("only allowed on fault classes"));
    }

    @Test
    void P_5_1_malformedStubReallyViolatesTheContract() {
        // If MALFORMED ever validated, the gate would report it; prove the gate checks that.
        MockStub malformed = byId("DISBURSEMENT/catalog/MALFORMED/28450.00");
        MockStub valid = withInteractions(malformed, byId("DISBURSEMENT/catalog/SUCCESS/28450.00").interactions(), true);
        assertThat(MockGates.contract(List.of(valid), catalogs, paths.contracts()).failures())
                .anySatisfy(m -> assertThat(m).contains("every response validates"));
    }

    @Test
    void P_5_6_everyClassHasMinPerBandAndEveryAmount() {
        assertThat(MockGates.coverage(stubs, catalogs, budget).failures()).isEmpty();
        List<MockStub> fewer = new ArrayList<>(stubs);
        fewer.removeIf(s -> s.id().startsWith("DISBURSEMENT/catalog/KEY_CONFLICT/") && !s.id().endsWith("/9850.00"));
        assertThat(MockGates.coverage(fewer, catalogs, budget).failures())
                .anySatisfy(m -> assertThat(m).contains("KEY_CONFLICT: 1 stubs, needs >= " + budget.minPerBand()));
    }

    @Test
    void P_5_2_retryCountsAndDelaysFollowTheBudget() {
        // RETRY_THEN_OK k = 1 .. maxAttempts-1; EXHAUSTED never succeeds; timeout past start-to-close.
        long ks = stubs.stream().filter(s -> s.id().startsWith("DISBURSEMENT/catalog/RETRY_THEN_OK/28450.00/")).count();
        assertThat(ks).isEqualTo(budget.maxAttempts() - 1);
        MockStub k4 = byId("DISBURSEMENT/catalog/RETRY_THEN_OK/28450.00/k" + (budget.maxAttempts() - 1));
        assertThat(k4.interactions()).extracting(Interaction::status).endsWith(503, 201).hasSize(budget.maxAttempts());
        assertThat(byId("DISBURSEMENT/catalog/EXHAUSTED/28450.00").interactions()).extracting(Interaction::status).containsOnly(503);
        assertThat(byId("DISBURSEMENT/catalog/TIMEOUT_BUT_PROCESSED/28450.00").interactions().get(0).delayMillis())
                .isGreaterThan(budget.startToClose().toMillis());
    }

    @Test
    void P_5_7_noCatchAllStubAndEveryMappingCarriesEnv() throws Exception {
        JsonNode root = new ObjectMapper().readTree(new MockStubGenerator(config.env(), budget, config.rules().incomeVarianceThreshold()).toWireMockImport(stubs));
        assertThat(root.path("mappings")).isNotEmpty();
        for (JsonNode m : root.path("mappings")) {
            assertThat(m.path("metadata").path("env").asText()).isEqualTo("it-test");
            assertThat(m.path("request").path("urlPath").asText()).isNotBlank();
            boolean narrowed = m.path("request").has("headers") || m.path("request").has("bodyPatterns");
            assertThat(narrowed).as("catch-all stub %s", m.path("name").asText()).isTrue();
        }
    }

    @Test
    void NFR_08_scenarioStubKeysAreScopedToTheEnv() {
        MockStub s = byId("DISBURSEMENT/scenario/LA-1001");
        assertThat(s.requestMatch().headerMatches().get("Idempotency-Key")).isEqualTo("funding-it-test-[0-9]+");
        assertThat(s.requestMatch().bodyEquals()).containsEntry("loanApplicationId", "LA-1001");
        assertThat(s.exampleRequest().path("amount").asText()).isEqualTo("28450.00"); // AS-8.9 RISC amount_financed
    }

    @Test
    void P_5_bandOracleAgreesWithTheRealRuleForEveryStub() {
        assertThat(MockGates.bandOracle(stubs, config.rules(), budget).failures()).isEmpty();
    }

    @Test
    void P_5_bandOracleCatchesAWrongExpectation() {
        MockStub s = byId("INCOME_VERIFICATION/catalog/BOUNDARY_FAIL/t+0.0001/96000.37");
        MockStub lying = new MockStub(s.id(), s.api(), s.source(), s.band(), s.requestKey(), s.method(), s.path(), s.requestMatch(),
                s.exampleHeaders(), s.exampleRequest(), s.interactions(), "PASS", s.violatesContract(), s.attributes());
        assertThat(MockGates.bandOracle(List.of(lying), config.rules(), budget).failures())
                .singleElement().asString().contains("band expects PASS but the client + rule give FAIL");
    }

    @Test
    void FR_15_boundaryEntriesLandOnTheirSideAfterCentRounding() {
        // Every magnitude, including 96000.37, at exactly t passes and at t + 0.0001 fails.
        for (String mag : List.of("18500.00", "45000.00", "96000.00", "96000.37", "250000.00", "1250000.00")) {
            assertThat(MockGates.incomeOutcome(byId("INCOME_VERIFICATION/catalog/BOUNDARY_PASS/t/" + mag), config.rules(), budget,
                    new com.funding.domain.RuleEngine())).as(mag).isEqualTo("PASS");
            assertThat(MockGates.incomeOutcome(byId("INCOME_VERIFICATION/catalog/BOUNDARY_FAIL/t+0.0001/" + mag), config.rules(), budget,
                    new com.funding.domain.RuleEngine())).as(mag).isEqualTo("FAIL");
        }
    }

    @Test
    void P_5_5_aChangedThresholdMovesTheBandsWithoutFlippingOutcomes() {
        SeedConfig looser = new SeedConfig(config.rules().with("funding.income.variance-threshold", "0.20"), config.properties(), config.env());
        SeedGates g20 = new SeedGates(paths, looser, RuleFingerprints.fromClasspath());
        SeedGates g15 = new SeedGates(paths, config, RuleFingerprints.fromClasspath());
        var was = g15.mockLockEntries(g15.generate());
        var now = g20.mockLockEntries(g20.generate());
        assertThat(MockLockGate.flips(was, now)).isEmpty();
        // ...but the bodies moved: the boundary entry now sits at 20%.
        assertThat(now.get("INCOME_VERIFICATION/catalog/BOUNDARY_PASS/t/96000.00").response())
                .isNotEqualTo(was.get("INCOME_VERIFICATION/catalog/BOUNDARY_PASS/t/96000.00").response());
        MockStub at20 = MockSet.generate("x", budget, new java.math.BigDecimal("0.20"), g20.generate().fixtures(), catalogs).stream()
                .filter(s -> s.id().equals("INCOME_VERIFICATION/catalog/BOUNDARY_PASS/t/96000.00")).findFirst().orElseThrow();
        assertThat(at20.attributes().get("verified")).isEqualTo("80000.00");
    }

    @Test
    void P_5_2_incomeFaultClassesFollowTheBudget() {
        MockStub timeout = byId("INCOME_VERIFICATION/catalog/TIMEOUT_THEN_OK/96000.00");
        assertThat(timeout.interactions().get(0).delayMillis()).isGreaterThan(budget.startToClose().toMillis());
        assertThat(byId("INCOME_VERIFICATION/catalog/RATE_LIMITED/96000.00").interactions().get(0).headers()).containsEntry("Retry-After", "2");
        assertThat(byId("INCOME_VERIFICATION/catalog/CONNECTION_RESET/96000.00").interactions().get(0).fault()).isEqualTo("CONNECTION_RESET_BY_PEER");
        assertThat(byId("INCOME_VERIFICATION/scenario/LA-1017").interactions()).hasSize(budget.maxAttempts());
    }

    @Test
    void AS_5_4_scenarioIncomeStubsMatchOnTheEncryptedTokenOnly() {
        MockStub s = byId("INCOME_VERIFICATION/scenario/LA-1012");
        assertThat(s.requestMatch().bodyEquals()).containsExactly(java.util.Map.entry("ssnItinEncrypted", "enc:it-test:LA-1012"));
    }

    private MockStub byId(String id) {
        return stubs.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no stub " + id));
    }

    private static MockStub withInteractions(MockStub s, List<Interaction> calls, boolean violates) {
        return new MockStub(s.id(), s.api(), s.source(), s.band(), s.requestKey(), s.method(), s.path(), s.requestMatch(),
                s.exampleHeaders(), s.exampleRequest(), calls, s.expected(), violates, s.attributes());
    }
}
