package com.funding.seed;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where the seed inputs and outputs live, relative to the repository root. */
public record SeedPaths(Path root) {

    /** {@code -Dfunding.repo.root}, else the nearest ancestor of the working directory that has {@code test/data}. */
    public static SeedPaths discover() {
        String prop = System.getProperty("funding.repo.root");
        if (prop != null && !prop.isBlank()) {
            return new SeedPaths(Path.of(prop).toAbsolutePath().normalize());
        }
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("test/data/scenarios"))) {
                return new SeedPaths(p);
            }
            p = p.getParent();
        }
        throw new IllegalStateException("cannot find the repository root (no test/data/scenarios); set -Dfunding.repo.root");
    }

    public Path scenarios() { return root.resolve("test/data/scenarios"); }
    public Path baseline() { return scenarios().resolve("_baseline.yaml"); }
    public Path gapRegistry() { return root.resolve("test/data/gap-form-registry.yaml"); }
    public Path rulesLock() { return root.resolve("test/data/rules.lock"); }
    public Path mocksLock() { return root.resolve("test/data/mocks.lock"); }
    public Path ruleChanges() { return root.resolve("test/data/RULE-CHANGES.md"); }
    public Path mockCatalogs() { return root.resolve("test/data/mocks"); }
    public Path contracts() { return root.resolve("contracts"); }
    public Path seedDataDoc() { return root.resolve("specs/001-loan-funding-validation/seed-data.md"); }
    public Path report() { return root.resolve("target/seed-report"); }
    public Path generated() { return root.resolve("target/generated-seed"); }
}
