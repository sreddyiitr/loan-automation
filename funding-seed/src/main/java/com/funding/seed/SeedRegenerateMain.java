package com.funding.seed;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.funding.seed.mock.MockImpactReport;
import com.funding.seed.mock.MocksLock;

/**
 * {@code make seed-check}: {@code SeedRegenerateMain check} runs every gate, writes nothing.
 * {@code make seed-regenerate}: regenerates, runs conformance + coverage, and only if both pass
 * rewrites {@code rules.lock} and the generated table in {@code seed-data.md} (constitution P-2.5).
 */
public final class SeedRegenerateMain {

    private SeedRegenerateMain() {}

    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "check";
        SeedPaths paths = SeedPaths.discover();
        SeedConfig config = SeedConfig.fromDefaultsAndSystem(System.getProperty("funding.env", "local"));
        SeedGates gates = new SeedGates(paths, config, RuleFingerprints.fromClasspath());

        List<GateResult> results;
        if ("regenerate".equals(mode)) {
            results = regenerate(paths, gates);
        } else if ("check".equals(mode)) {
            results = gates.verifyAll();
        } else {
            System.err.println("usage: SeedRegenerateMain check|regenerate");
            System.exit(64);
            return;
        }
        System.out.println("seed gates (" + mode + "):\n" + SeedGates.format(results));
        if (!SeedGates.allPassed(results)) {
            System.err.println("seed gates FAILED. Fix the scenario intent or the rule; never weaken the assertion (P-2.3).");
            System.exit(1);
        }
    }

    static List<GateResult> regenerate(SeedPaths paths, SeedGates gates) throws IOException {
        SeedGates.Generated g = gates.generate();
        List<GateResult> first = List.of(gates.conformance(g.fixtures()), gates.coverage(g.intents()));
        if (!SeedGates.allPassed(first)) {
            System.err.println("not writing rules.lock or seed-data.md: conformance/coverage failed");
            return first;
        }
        List<GateResult> mockFirst = gates.mockGates(g).stream().filter(r -> !r.gate().equals("mock-lock")).toList();
        if (!SeedGates.allPassed(mockFirst)) {
            System.err.println("not writing any lock: mock contract/coverage failed");
            return mockFirst;
        }
        List<String> changedRules = RulesLock.diff(paths.rulesLock(), gates.renderLock(g));
        var wasMocks = MocksLock.read(paths.mocksLock());
        var nowMocks = gates.mockLockEntries(g);
        Files.createDirectories(paths.report());
        Files.writeString(paths.report().resolve("mock-impact.md"), MockImpactReport.render(wasMocks, nowMocks, changedRules));
        Files.writeString(paths.mocksLock(), MocksLock.render(nowMocks));
        Files.writeString(paths.rulesLock(), gates.renderLock(g));
        String doc = SeedGates.read(paths.seedDataDoc());
        Files.writeString(paths.seedDataDoc(), ScenarioTable.splice(doc, ScenarioTable.render(g.fixtures())));
        Files.createDirectories(paths.generated());
        for (Fixture f : g.fixtures()) {
            Files.writeString(paths.generated().resolve(f.scenarioId() + ".json"), SeedGenerator.toJson(f));
        }
        List<GateResult> all = new ArrayList<>(first);
        all.add(gates.lock(g));
        all.add(gates.docs(g.fixtures()));
        all.addAll(gates.mockGates(g));
        System.out.println("wrote test/data/rules.lock, test/data/mocks.lock, seed-data.md table, target/seed-report/mock-impact.md");
        return all;
    }
}
