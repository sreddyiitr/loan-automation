package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Outcome;
import com.funding.domain.RuleCatalog;
import com.funding.domain.RuleEngine;
import com.funding.domain.StipRequest;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockCatalog;
import com.funding.seed.mock.MockGates;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStub;
import com.funding.seed.mock.MocksLock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The P-2 gates: conformance, coverage, lock, docs. Pure except for reading the committed lock and
 * doc. {@link #verifyAll()} is what {@code make seed-check}, {@code mvn test} and the seed loader run.
 */
public final class SeedGates {

    private final SeedPaths paths;
    private final SeedConfig config;
    private final RuleFingerprints fingerprints;
    private final RuleEngine engine = new RuleEngine();
    private final ScenarioLoader loader;

    public SeedGates(SeedPaths paths, SeedConfig config, RuleFingerprints fingerprints) {
        this.paths = paths;
        this.config = config;
        this.fingerprints = fingerprints;
        this.loader = new ScenarioLoader(paths);
    }

    /** Loaded + generated scenario set. */
    public record Generated(JsonNode baseline, String baselineHash, List<ScenarioIntent> intents, List<Fixture> fixtures) {}

    public Generated generate() {
        JsonNode baseline = loader.baseline();
        List<ScenarioIntent> intents = loader.scenarios();
        SeedGenerator gen = new SeedGenerator(config, GapRegistryFile.load(paths.gapRegistry()));
        List<Fixture> fixtures = intents.stream().map(i -> gen.generate(i, baseline)).toList();
        return new Generated(baseline, ScenarioLoader.sha256(paths.baseline()), intents, fixtures);
    }

    public List<GateResult> verifyAll() {
        Generated g = generate();
        List<GateResult> out = new ArrayList<>(List.of(conformance(g.fixtures()), coverage(g.intents()), lock(g), docs(g.fixtures())));
        out.addAll(mockGates(g));
        return out;
    }

    /** Env-neutral stub set, as {@code mocks.lock} records it. */
    public List<MockStub> lockStubs(Generated g) {
        return MockSet.generate(LOCK_ENV, MockBudget.from(config), g.fixtures(), MockSet.catalogs(paths));
    }

    public static final String LOCK_ENV = "{env}";

    public Map<String, MocksLock.Entry> mockLockEntries(Generated g) {
        return MocksLock.entries(lockStubs(g), config.rules(), fingerprints);
    }

    /** P-5: contract, catalog coverage, lock. */
    public List<GateResult> mockGates(Generated g) {
        Map<com.funding.domain.ExternalApi, MockCatalog> catalogs = MockSet.catalogs(paths);
        MockBudget budget = MockBudget.from(config);
        List<MockStub> stubs = MockSet.generate(config.env(), budget, g.fixtures(), catalogs);
        return List.of(
                MockGates.contract(stubs, catalogs, paths.contracts()),
                MockGates.coverage(stubs, catalogs, budget),
                new GateResult("mock-lock", MocksLock.diff(paths.mocksLock(), mockLockEntries(g))));
    }

    /** P-2.3: every generated scenario, run through the real rule engine, must produce its {@code expect}. */
    public GateResult conformance(List<Fixture> fixtures) {
        List<String> failures = new ArrayList<>();
        for (Fixture f : fixtures) {
            ScenarioIntent i = f.intent();
            Map<Check, CheckOutcome> actual = engine.evaluate(FixtureSnapshots.of(f), config.rules());
            for (Map.Entry<Check, CheckOutcome> e : actual.entrySet()) {
                Outcome want = i.expect().check(e.getKey());
                if (e.getValue().outcome() != want) {
                    failures.add(i.id() + " " + i.rules() + ": " + e.getKey() + " expected " + want
                            + " but the rule engine returned " + e.getValue().outcome() + describe(e.getValue()));
                }
            }
            List<String> wantStips = i.expect().stips().stream().sorted().toList();
            List<String> gotStips = actual.values().stream().flatMap(o -> o.stips().stream())
                    .map(StipRequest::code).sorted().toList();
            if (!wantStips.equals(gotStips)) {
                failures.add(i.id() + " " + i.rules() + ": stips expected " + wantStips + " but were " + gotStips);
            }
            var snapshot = FixtureSnapshots.of(f);
            String status = ExpectedStatus.derive(actual.values(), f.externals().get("disbursement"),
                    com.funding.domain.rules.RiscCompleteness.problems(snapshot, config.rules()));
            if (!status.equals(i.expect().status())) {
                failures.add(i.id() + " " + i.rules() + ": status expected " + i.expect().status() + " but outcomes lead to " + status);
            }
        }
        return new GateResult("conformance", failures);
    }

    /**
     * P-2.4: every {@link RuleCatalog} rule has a scenario for each required side, and the scenario's
     * expected outcome for the rule's check agrees with the side it claims.
     */
    public GateResult coverage(List<ScenarioIntent> intents) {
        List<String> failures = new ArrayList<>();
        Set<String> known = Arrays.stream(RuleCatalog.values()).map(Enum::name).collect(Collectors.toSet());
        for (ScenarioIntent i : intents) {
            i.rules().stream().filter(r -> !known.contains(r))
                    .forEach(r -> failures.add(i.id() + ": names unknown rule " + r));
        }
        for (RuleCatalog rule : RuleCatalog.values()) {
            for (String side : rule.requiredSides()) {
                List<ScenarioIntent> covering = intents.stream()
                        .filter(i -> i.rules().contains(rule.name()) && i.sideKey().equals(side)).toList();
                if (covering.isEmpty()) {
                    failures.add(rule + ": no scenario covers side " + side);
                }
                for (ScenarioIntent i : covering) {
                    Outcome want = expectedFor(side);
                    if (rule.check() != null) {
                        Outcome declared = i.expect().check(rule.check());
                        if (declared != want) {
                            failures.add(i.id() + ": claims " + rule + " " + side + " but expects " + rule.check() + " " + declared);
                        }
                        continue;
                    }
                    // Cross-cutting rule: a PASS side means every check passes; otherwise some check has the side's outcome.
                    boolean ok = want == Outcome.PASS
                            ? Arrays.stream(Check.values()).allMatch(c -> i.expect().check(c) == Outcome.PASS)
                            : Arrays.stream(Check.values()).anyMatch(c -> i.expect().check(c) == want);
                    if (!ok) {
                        failures.add(i.id() + ": claims " + rule + " " + side + " but no check is expected to be " + want);
                    }
                }
            }
        }
        return new GateResult("coverage", failures);
    }

    /** P-2.5: {@code rules.lock} matches the current rule sources, config and scenario intents. */
    public GateResult lock(Generated g) {
        return new GateResult("lock", RulesLock.diff(paths.rulesLock(), renderLock(g)));
    }

    /** P-2.7: the generated table in {@code seed-data.md} matches. */
    public GateResult docs(List<Fixture> fixtures) {
        String doc = read(paths.seedDataDoc());
        String want = ScenarioTable.render(fixtures);
        return new GateResult("docs", ScenarioTable.current(doc).equals(want) ? List.of()
                : List.of("the generated scenario table in seed-data.md is stale: run `make seed-regenerate`"));
    }

    public String renderLock(Generated g) {
        return RulesLock.render(g.intents(), g.baselineHash(), registryHash(), config.rules(), fingerprints);
    }

    private String registryHash() {
        return java.nio.file.Files.exists(paths.gapRegistry()) ? ScenarioLoader.sha256(paths.gapRegistry()) : "none";
    }

    static Outcome expectedFor(String side) {
        String base = side.contains(":") ? side.substring(0, side.indexOf(':')) : side;
        return switch (base) {
            case "PASS", "PASS_BOUNDARY" -> Outcome.PASS;
            case "FAIL", "FAIL_BOUNDARY" -> Outcome.FAIL;
            case "NEEDS_REVIEW" -> Outcome.NEEDS_REVIEW;
            case "ERROR" -> Outcome.ERROR;
            default -> throw new IllegalArgumentException("unknown side " + side);
        };
    }

    private static String describe(CheckOutcome o) {
        String failed = o.comparisons().stream().filter(c -> !c.ok())
                .map(c -> c.name() + " expected " + c.expected() + " actual " + c.actual())
                .collect(Collectors.joining("; "));
        String flagged = o.flaggedFields().isEmpty() ? "" : " flagged " + o.flaggedFields();
        return failed.isEmpty() && flagged.isEmpty() ? "" : " (" + failed + flagged + ")";
    }

    static String read(java.nio.file.Path p) {
        try {
            return Files.readString(p).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean allPassed(Collection<GateResult> results) {
        return results.stream().allMatch(GateResult::passed);
    }

    public static String format(Collection<GateResult> results) {
        StringBuilder sb = new StringBuilder();
        for (GateResult r : results) {
            sb.append(r.passed() ? "  PASS " : "  FAIL ").append(r.gate()).append('\n');
            r.failures().forEach(f -> sb.append("       - ").append(f).append('\n'));
        }
        return sb.toString();
    }
}
