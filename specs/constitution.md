# Project Constitution: funding-automation

These principles are non-negotiable. A spec, plan, task or PR that breaks one is wrong, even if its tests pass. Changing a principle needs an explicit decision recorded here, with date and reason.

## P-1 Spec first
Behavior is specified in `specs/<feature>/spec.md` before code exists. Every acceptance scenario (AS-x.y) has at least one test named after it (`AS_2_2_…`).

## P-2 Test data is derived from the rules, never hand-maintained
Business rules change, and test data must change with them in the same commit. The build enforces this:

1. **Single source of rule logic.**
   - Scenario fixtures are **generated** from scenario *intent* files (`test/data/scenarios/*.yaml`) by `SeedGenerator`.
   - The generator uses the **same domain calculators and the same `funding.*` configuration** as the application: amortization, tolerances, thresholds, confidence cut-off, lender name and GAP registry.
   - Nobody types a derived number (payment, total of payments, finance charge, boundary income, off-by-tolerance price) by hand.
2. **Intent, not values.** A scenario states what it is testing, relative to the rule. Examples: "stated income at threshold + 0.01%", "cash price outside tolerance by the smallest unit", "confidence one step below the flag cut-off". When a threshold changes, the generated values move with it.
3. **Conformance gate.**
   - Every generated scenario is run through the **real rule engine** (pure `funding-domain`, no I/O), and the outcome must equal the scenario's declared `expect`.
   - A mismatch fails the build and names the scenario, the rule and the expected and actual outcomes.
   - The fix is to update the scenario intent or the rule. Weakening the assertion is not a fix.
4. **Coverage gate.** Every rule in `RuleCatalog` must have at least one PASS scenario and one FAIL (or NEEDS_REVIEW) scenario. A rule with a threshold or tolerance must also have **both sides of its boundary**. Adding a rule without scenarios fails the build.
5. **Review lock.**
   - `test/data/rules.lock` records, for each rule, a fingerprint: a hash of the rule's source file plus its effective config values, and the scenarios that were last verified against it.
   - Any change to a rule's code or config makes the lock stale, and the build fails until someone runs `make seed-regenerate`. That command regenerates, re-runs the conformance and coverage gates, and rewrites the lock **only if both pass**.
   - The lock diff in the PR shows the reviewer which rules changed and which scenarios were re-verified.
6. **The seeder never loads unverified data.**
   - `SeedDataLoader` generates the fixtures at startup and runs the conformance gate first.
   - If the gate fails, the `local` profile refuses to seed and names the stale scenario. It never seeds data that no longer matches the rules.
7. **Docs follow data.** The scenario table in `seed-data.md` is generated between `<!-- BEGIN GENERATED -->` markers. The build fails if it differs from the generator's output.

## P-3 Local first, real services where they work
Run against real local services: Temporal, Postgres, and AWS Emulator for the services it actually emulates for us. Where the emulator falls short, the gap is verified, recorded with evidence in the plan, and re-checked on each emulator release. Reuse a developer's running containers before starting new ones.

## P-4 Determinism and auditability
Rule evaluation is pure and deterministic. Money uses exact decimals. Every state change records its actor in the audit log.

## P-5 Mock API responses are derived from the rules, and inspected on every rule change
Every external API that a rule depends on is mocked at the HTTP boundary (WireMock). Mock responses follow the same discipline as P-2:

1. **Contract first.**
   - Each API has an OpenAPI contract in `contracts/<api-id>.openapi.yaml`.
   - Every generated stub's request matcher and response body must validate against it.
   - A stub that violates the contract on purpose, to test malformed responses, is marked `violatesContract: true` and must be a fault-class entry.
2. **Generated, never hand-written.**
   - Stubs are generated from scenario intent (P-2) **and** from the per-API **mock data catalog** (`test/data/mocks/<api-id>.catalog.yaml`).
   - The catalog describes response *bands* and *fault classes* relative to the rule's live config: the variance threshold, the retry budget, the activity timeout.
   - Response values and delays are computed; nobody types them by hand.
3. **Rule ↔ API dependency is declared.** Each `RuleCatalog` entry lists the APIs it consumes (`apis = {INCOME_VERIFICATION}`). This mapping decides which mocks must be re-inspected when a rule changes.
4. **Mandatory inspection on rule change.**
   - When a rule's fingerprint changes (P-2.5), every stub of every API that rule consumes is regenerated and re-verified.
   - `make seed-regenerate` writes `target/seed-report/mock-impact.md`, which lists, per changed rule: the affected APIs, the stubs whose body changed, and the stubs whose **expected outcome changed**, with before and after values.
5. **No silent outcome flips.**
   - `test/data/mocks.lock` (committed) records, for every stub: id, band or fault class, request key, response-body hash, expected outcome and the rule fingerprint.
   - If any expected outcome differs from the lock, `make seed-regenerate` **refuses** to update it unless it is run with `ACCEPT_OUTCOME_CHANGES=<RULE_ID>` **and** `test/data/RULE-CHANGES.md` has an entry for that rule: date, author, what changed, why the flips are correct.
   - A changed threshold moves the bands with it, so there are no flips. Only a real change in logic flips outcomes, and that is exactly what must be reviewed by a person.
6. **Breadth, not one or two examples.** Each API's catalog must cover every band and every fault class, with at least `minPerBand` entries per band (default 5), and across the magnitude set. The coverage gate enforces this.
7. **No unmatched calls.**
   - WireMock has no catch-all stub.
   - Every functional test, and `make seed-check` when WireMock is running, asserts that `/__admin/requests/unmatched` is empty.
   - A request that no generated stub answers is a failure, never a silent pass.
