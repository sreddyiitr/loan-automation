package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.ExternalApi;
import com.funding.domain.RuleEngine;
import com.funding.domain.Snapshot;
import com.funding.domain.StipRequest;
import com.funding.domain.income.IncomeVerification;
import com.funding.domain.rules.RiscCompleteness;
import com.funding.seed.mock.ClientSimulator;
import com.funding.seed.mock.Interaction;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockCatalog;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStub;
import com.funding.seed.mock.MockStubGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * T603: the volume seed, one application per mock catalog entry (deterministic: catalog order, no
 * randomness). Every value is derived, never typed (P-2, P-5):
 * <ul>
 *   <li><b>LA-5xxx</b>, one per Income Verification catalog entry: the baseline deal with the entry's
 *       stated income and SSN token, so the catalog stub answers its income call; a generated
 *       {@code SUCCESS} disbursement stub pays it.</li>
 *   <li><b>LA-6xxx</b>, one per Disbursement catalog entry (the entry's own loan id): the baseline
 *       deal, a generated {@code ok-100k} income stub, and the catalog stub answers its payment.</li>
 * </ul>
 * The expected checks, status and stips come from the rule engine and the response simulator (the
 * band oracle's machinery), not from the catalog text.
 */
public final class VolumeSet {

    /**
     * One volume application. {@code catalogStub} is the entry under test; {@code companionStub} is the
     * generated stub for the other API. {@code healthy}: both APIs answer at once with a usable
     * response (no fault, delay, retry or 5xx), the NFR-01 population.
     */
    public record Case(Fixture fixture, MockStub catalogStub, MockStub companionStub, Map<Check, CheckOutcome> outcomes,
                       String expectedStatus, List<String> expectedStips, boolean healthy) {}

    public static final int INCOME_FIRST_LOAN = 5001;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SeedConfig config;
    private final SeedPaths paths;
    private final RuleEngine engine = new RuleEngine();

    public VolumeSet(SeedConfig config, SeedPaths paths) {
        this.config = config;
        this.paths = paths;
    }

    public List<Case> cases() {
        Map<ExternalApi, MockCatalog> catalogs = MockSet.catalogs(paths);
        MockBudget budget = MockBudget.from(config);
        JsonNode baseline = new ScenarioLoader(paths).baseline();
        SeedGenerator generator = new SeedGenerator(config, GapRegistryFile.load(paths.gapRegistry()),
                catalogs.get(ExternalApi.INCOME_VERIFICATION));
        MockStubGenerator stubs = new MockStubGenerator(config.env(), budget, config.rules().incomeVarianceThreshold());
        List<MockStub> catalog = MockSet.generate(config.env(), budget, config.rules().incomeVarianceThreshold(), List.of(), catalogs)
                .stream().filter(s -> "catalog".equals(s.source())).toList();

        List<Case> out = new ArrayList<>();
        int n = INCOME_FIRST_LOAN;
        for (MockStub s : catalog.stream().filter(s -> s.api() == ExternalApi.INCOME_VERIFICATION)
                .sorted(Comparator.comparing(MockStub::id)).toList()) {
            String id = "LA-" + (n++);
            ObjectNode given = JSON.createObjectNode();
            given.putObject("application").put("statedIncomeAnnual", s.attributes().get("statedIncome"));
            Fixture generated = generator.generate(intent(id, s, given, List.of("INCOME_VARIANCE")), baseline);
            String token = s.attributes().get("token"); // the catalog stub answers this token
            Fixture f = generated.withId(id, v -> new Fixture.Version(v.versionNumber(), v.latest(), v.status(), v.borrowerName(),
                    v.coBorrowerName(), token, v.statedIncomeAnnual(), v.vehicleVin(), v.dealershipName(), v.dealershipId(), v.documents()));
            MockStub pay = stubs.scenarioStub(f, ExternalApi.DISBURSEMENT, "SUCCESS", catalogs);
            Snapshot snap = FixtureSnapshots.of(f);
            Map<Check, CheckOutcome> outcomes = new EnumMap<>(engine.evaluate(snap, config.rules()));
            IncomeVerification v = ClientSimulator.income(s.interactions(), budget);
            outcomes.put(Check.INCOME, v == null
                    ? CheckOutcome.error(Check.INCOME, "income API: retries exhausted or response not usable")
                    : engine.evaluate(Check.INCOME, snap.withIncome(v), config.rules()));
            String status = ExpectedStatus.derive(outcomes.values(), "SUCCESS", RiscCompleteness.problems(snap, config.rules()));
            out.add(new Case(f, s, pay, outcomes, status, stips(outcomes), healthy(s)));
        }
        for (MockStub s : catalog.stream().filter(s -> s.api() == ExternalApi.DISBURSEMENT).toList()) {
            String id = s.attributes().get("loanApplicationId"); // LA-6xxx, the catalog stub matches it
            // The stub also matches the amount: the deal finances the entry's magnitude (TILA values are derived from it).
            ObjectNode given = JSON.createObjectNode();
            given.putObject("risc").put("amountFinanced", s.attributes().get("magnitude"));
            Fixture f = generator.generate(intent(id, s, given, List.of()), baseline);
            MockStub income = stubs.scenarioStub(f, ExternalApi.INCOME_VERIFICATION, "ok-100k", catalogs);
            Snapshot snap = FixtureSnapshots.of(f);
            Map<Check, CheckOutcome> outcomes = new EnumMap<>(engine.evaluate(snap, config.rules()));
            IncomeVerification v = ClientSimulator.income(income.interactions(), budget);
            outcomes.put(Check.INCOME, engine.evaluate(Check.INCOME, snap.withIncome(v), config.rules()));
            boolean allPass = outcomes.values().stream().allMatch(o -> o.outcome() == com.funding.domain.Outcome.PASS);
            if (!allPass || !RiscCompleteness.problems(snap, config.rules()).isEmpty()) {
                throw new IllegalStateException(id + ": the baseline deal must pass every check, so only the disbursement varies");
            }
            // The catalog's expectation is the band oracle's (ClientSimulator.disbursement under the retry budget, checked by
            // the mock-band-oracle gate): FUNDED when the payment goes through, APPROVED (held for a funder) otherwise.
            out.add(new Case(f, s, income, outcomes, s.expected(), List.of(), healthy(s) && "SUCCESS".equals(s.band())));
        }
        return List.copyOf(out);
    }

    /** Every stub the volume applications need beyond the normal set: the generated companion stubs. */
    public static List<MockStub> companionStubs(List<Case> cases) {
        return cases.stream().map(Case::companionStub).toList();
    }

    private static List<String> stips(Map<Check, CheckOutcome> outcomes) {
        return outcomes.values().stream().flatMap(o -> o.stips().stream()).map(StipRequest::code).sorted().toList();
    }

    /** One immediate, usable answer: no fault, delay, retry, rate limit or server error. */
    private static boolean healthy(MockStub s) {
        if (s.interactions().size() != 1) {
            return false;
        }
        Interaction i = s.interactions().get(0);
        return i.fault() == null && i.delayMillis() == 0 && i.status() != 429 && i.status() < 500 && !s.violatesContract();
    }

    /**
     * A synthetic intent for a volume application. Its hash covers the catalog entry (id, expected
     * outcome and responses), so the seed loader re-seeds it when the catalog or the rules change.
     */
    private static ScenarioIntent intent(String id, MockStub s, JsonNode given, List<String> rules) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (s.id() + "|" + s.expected() + "|" + s.interactions() + "|" + s.attributes()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ScenarioIntent.Expect none = new ScenarioIntent.Expect(Map.of(), "computed", List.of());
        return new ScenarioIntent(id, "Volume: " + s.id(), rules, "PASS", null, "T603", s.id(), given, MissingNode.getInstance(),
                none, List.of(), MissingNode.getInstance(), null, "volume:" + s.id(), hash);
    }
}
