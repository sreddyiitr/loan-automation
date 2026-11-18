# Implementation Summary

What has been built so far for spec 001 (auto loan funding validation), and what is still to come.
Status as of **2026-11-17**: slices **S1 (walking skeleton)** and **S2 (document rules)** are done; S3–S6 are planned in
[`specs/001-loan-funding-validation/tasks.md`](specs/001-loan-funding-validation/tasks.md).

| Item | Where |
|---|---|
| Principles | [`specs/constitution.md`](specs/constitution.md) (P-1 … P-5) |
| Requirements | [`spec.md`](specs/001-loan-funding-validation/spec.md) (user stories, acceptance scenarios `AS-x.y`) |
| Design | [`plan.md`](specs/001-loan-funding-validation/plan.md), [`data-model.md`](specs/001-loan-funding-validation/data-model.md), [`seed-data.md`](specs/001-loan-funding-validation/seed-data.md) |
| Task list and progress | [`tasks.md`](specs/001-loan-funding-validation/tasks.md) (S1: 32 of 32, S2: 11 of 11; "S1 notes" and "S2 notes" list deviations) |
| Branches | S1 merged to `main` (PR #2); S2 on `feat/001-s2-document-rules` |

---

## 1. What works end to end

A loan application version goes from `DRAFT` to `FUNDED` or `STIPULATED` through the real path,
with every service running locally:

```
POST /api/v1/dev/applications/{id}/submit        (stands in for OCR finishing)
  └─ version DRAFT → PENDING_REVIEW
       └─ DB trigger writes outbox_events row (same transaction)
            └─ OutboxRelay (every 1 s) → SQS queue funding-pending-review-<env>   [AWS Emulator]
                 └─ LocalLambdaRunner → SqsTriggerHandler (the real Lambda code)
                      └─ POST /api/v1/funding-workflows  (X-Trigger-Api-Key)
                           └─ Temporal: FundingWorkflow funding-<env>-<versionId>
                                ├─ loadVersionSnapshot (latest docs, no SSN)
                                ├─ in parallel: TILA, PRICE, VIN, GAP children → pure rule → result + stips
                                ├─ applyOutcome → APPROVED / STIPULATED / PENDING_REVIEW (audited;
                                │                 APPROVED needs a complete RISC and TILA PASS, AS-8.10)
                                └─ APPROVED → disburse (WireMock, idempotency key = workflow ID) → FUNDED
```

Measured on the developer machine: submit → first validation result in **under 1 s** (AS-1.1
allows 10 s). All **23** generated scenarios reach their expected status and stips; WireMock
reports **0 unmatched requests**. The full table, generated from the intents, is in
[`seed-data.md`](specs/001-loan-funding-validation/seed-data.md#scenarios).

| Area | Scenarios | Result |
|---|---|---|
| Happy path | LA-1001 | FUNDED, one disbursement of 28450.00 to DLR-100 |
| TILA (US-2) | LA-1002, 1003 fail; LA-1004 (0% APR), 1005 (rounding), 1034 (final payment) pass | STIPULATED `STIP_TILA_MATH_DISCREPANCY` / FUNDED |
| Price parity (US-3) | LA-1006: Buyer's Order 1¢ beyond the tolerance | STIPULATED `STIP_PRICE_MISMATCH` |
| VIN and vehicle (US-4) | LA-1007 (VIN on GAP), 1008 (model), 1010 (letter O), 1011 (app VIN) fail; LA-1009 (case/space) passes | STIPULATED `STIP_VIN_MISMATCH` / FUNDED |
| GAP (US-6) | LA-1018 (expired form), 1019 (other state), 1020 (lender), 1021 (both) fail; LA-1022 (no GAP) passes | STIPULATED `STIP_GAP_FORM_MISMATCH` and/or `STIP_LENDER_MISMATCH` / FUNDED |
| Confidence and parsing (US-7, FR-14) | LA-1023 (flagged), 1026 (missing), 1033 (`31,450.00`) need review; LA-1024 (exactly at the cut-off), 1025 (unused field flagged), 1031 (multi-page section) pass | STIPULATED `STIP_EXTRACTION_REVIEW_REQUIRED` / FUNDED |

---

## 2. Features by module

### `funding-domain`: pure rules (no Spring, no Temporal, no I/O)

- **Money math (FR-08, FR-14):** `Money` uses exact decimals (`DECIMAL128`, half-up to 2 dp) and
  rejects money strings that don't match `^-?\d+(\.\d{1,2})?$` (for example `31,450.00`).
- **Amortization (AS-2.3, C-9):** `P = A·r/(1−(1+r)^−n)`, with `A/n` at 0% APR. The schedule total
  is `P × n`, or `P × (n−1) + final_payment`.
- **TILA rule (US-2):** rule ids `TILA_SUM` and `TILA_PAYMENT`. It checks three things:
  - the sum `amount_financed + finance_charge = total_of_payments` (tolerance `0.00`);
  - the amortized payment (tolerance `0.01`);
  - the payment schedule.

  A failure yields FAIL plus `STIP_TILA_MATH_DISCREPANCY`, with expected and actual values. A
  missing or unparseable field yields NEEDS_REVIEW plus `STIP_EXTRACTION_REVIEW_REQUIRED`.
- **Price parity rule (US-3):** `PRICE_PARITY` compares the RISC `cash_price` with the Buyer's
  Order `total_vehicle_cash_price` within `funding.price.tolerance` (0.00). A difference yields
  `STIP_PRICE_MISMATCH` with both values.
- **VIN rule (US-4):** four rule ids.
  - `VIN_FORMAT`: 17 characters, no I/O/Q.
  - `VIN_CROSS_DOC`: the same VIN on every document; the GAP Agreement takes part only when
    present.
  - `VEHICLE_ATTRS`: year, make and model match.
  - `VIN_APP_MATCH`: the documents' VIN equals `application_versions.vehicle_vin`.

  The VIN is compared after upper-casing only; make and model ignore case and surrounding
  whitespace (AS-4.3). A mismatch yields `STIP_VIN_MISMATCH`, listing each document's values.
- **GAP rule (US-6):** two independent sub-checks (AS-6.3) and an optional document.
  - `GAP_FORM`: the form is in the registry for the GAP state and effective on the contract
    date. Failure yields `STIP_GAP_FORM_MISMATCH`.
  - `GAP_LENDER`: both creditor names equal the primary lender. Failure yields
    `STIP_LENDER_MISMATCH`.
  - `GAP_OPTIONAL`: no GAP Agreement is PASS, `not_applicable`.

  The registry travels with the snapshot, so the rule stays I/O-free.
- **Confidence and parsing (US-7, FR-14):** cross-cutting rules `CONFIDENCE_FLAG` and
  `REQUIRED_FIELD`. A required field that is below `funding.confidence.flag-below` (0.90), missing
  or unparseable makes **only the checks that read it** NEEDS_REVIEW (AS-7.2), records the
  flagged keys, and raises `STIP_EXTRACTION_REVIEW_REQUIRED`. A field exactly at the cut-off is
  not flagged.
- **RISC completeness (AS-8.10):** `RiscCompleteness` lists required RISC fields that are missing,
  unparseable or flagged.
- **`RuleCatalog` and `RuleEngine`:** the catalog lists every rule, with its check, its source
  classes, the config keys it reads, the external APIs it uses, and the scenario sides it must
  cover. `RuleEngine` evaluates a `Snapshot` purely.
- **Rule fingerprints:** a build step writes the SHA-256 of each rule's source files to
  `META-INF/rule-fingerprints.properties`.
- **One config file** (`funding-rules.properties`), read by both the app and the seed generator:
  tolerances, the primary lender, activity retry and timeout budgets, and `min-per-band`.

### `funding-seed`: seed data and mocks are derived from the rules (constitution P-2, P-5)

- **Scenario intents:** `test/data/scenarios/*.yaml` state the intent ("offset the total by
  +10.00") and the expected outcome. `SeedGenerator` computes every derived number with the
  domain calculators. Unknown intent keys are rejected.
- **Four P-2 gates**, all run by `make seed-check`, by `mvn test` and at app startup:

  | Gate | Fails when |
  |---|---|
  | Conformance | the real rule engine disagrees with a scenario's `expect` (names scenario, rule, expected vs actual) |
  | Coverage | a `RuleCatalog` side (`PASS`, `FAIL`, `PASS:zero-apr`, …) has no scenario, or a scenario's side contradicts its expectation |
  | Lock | a rule's source or config, the baseline, or a scenario intent changed since `test/data/rules.lock` was written |
  | Docs | the generated scenario table in `seed-data.md` is stale |

- **`make seed-regenerate`** rewrites `rules.lock`, `mocks.lock` and the docs table, but **only
  if the gates pass**. It also writes `target/seed-report/mock-impact.md`.
- **Mock stubs (P-5):**
  - `contracts/disbursement.openapi.yaml` and `test/data/mocks/disbursement.catalog.yaml` hold
    8 classes × 6 amounts, plus retry depths derived from `max-attempts`.
  - `MockStubGenerator` builds **89 WireMock stubs**: 66 from the catalog and 23 for the
    scenarios. Delays and retry counts come from the config.
  - Three P-5 gates:
    - **Contract:** every stub validates against the OpenAPI spec; only fault classes may be
      marked `violatesContract`.
    - **Coverage:** every class has at least `minPerBand` entries and every amount appears.
    - **`mocks.lock`:** the generated stubs match the committed lock.
  - Every mapping carries `metadata.env`, and there is no catch-all stub.

### `funding-persistence`: Postgres 18 schema and repositories

- `V2__documents_frozen.sql` (S2, AS-9.7): inserting a document for an APPROVED, FUNDED, REJECTED
  or EXPIRED version fails with SQLSTATE `P0001` `DOCUMENTS_FROZEN`.
- The GAP registry is loaded into the snapshot, and `GapRegistryRepository` replaces it from
  `test/data/gap-form-registry.yaml` when seeding locally.
- `V1__baseline.sql`: the spec 001 data model (versions, documents, sections, fields, stips,
  results, outbox, audit, GAP registry, disbursements). It doesn't contain the S2
  document-freeze trigger or the S5 document-added trigger. A `local`-only migration adds
  `seed_scenarios`.
- **DB triggers:**
  - The first DRAFT → PENDING_REVIEW change writes exactly one outbox row (FR-02, AS-1.1). No
    other transition writes one (AS-1.4).
  - `submitted_at` is set once and never overwritten (the AS-8.6 expiry clock).
- **Indexes enforcing the model:**
  - one latest version per application;
  - one latest document per type;
  - one PENDING stip per (version, code, check), with `NULLS NOT DISTINCT` for manual stips
    (FR-07);
  - one current result per check (FR-06).
- **Repositories** (`JdbcClient`):
  - Every superseded-sensitive write first runs `lockLatest()` (`FOR UPDATE` plus a latest
    check) in the same transaction (AS-9.3).
  - The snapshot reads latest documents only (AS-9.4), and the highest-confidence value wins
    when a key repeats (§8).
  - The SSN token is never selected; the snapshot only records whether one is present (FR-13).
  - Stip creation and result rows are idempotent under activity retries.
  - Status, stip and disbursement changes are audited (FR-10).

### `funding-trigger-lambda`: the SQS-triggered Lambda (Java 21, no Spring, shaded jar)

- `SqsTriggerHandler` → `TriggerProcessor`. It sends a batch's records **concurrently on virtual
  threads**, and fails a record without sending it if it can't finish in the Lambda's remaining
  time.
- Per-record decision (`ReportBatchItemFailures`): 202/200 → ack, 409 → ack, 400 or bad body →
  ack, 401 → retry, 5xx or timeout → retry.

### `funding-trigger`: AWS plumbing

- `SqsClients`, ported from homehub: endpoint override, and the emulator CA trusted **only** by
  the SDK client.
- `StartupGuard`: `local` needs a loopback endpoint, and `aws` forbids bootstrap, seeding and
  the local runner.
- `EmulatorCalls` fails fast on 501/503.
- `InfraBootstrap`: queue plus DLQ, `maxReceiveCount=5`, visibility timeout 60 s.
- `OutboxRelay`: `FOR UPDATE SKIP LOCKED`, marks a row only after SQS accepts the message, and
  purges after 7 days.
- `LocalLambdaRunner`: stands in for the event source mapping, because the emulator can't run
  java21 Lambdas and its Lambdas can't reach the host (plan V-2, V-4). It long-polls, calls the
  same handler, and deletes only the acknowledged messages.

### `funding-workflows`: Temporal

- **`FundingWorkflow`:**
  - loads the snapshot;
  - fans out the enabled checks that have a rule (TILA, PRICE, VIN and GAP; INCOME arrives in
    S3) **in parallel** as children `<id>-<CHECK>-a<attempt>` (FR-04);
  - turns a child's technical failure into `ERROR` (held in PENDING_REVIEW);
  - runs `applyOutcome`;
  - when APPROVED, disburses and then marks the version FUNDED.

  Otherwise it waits. The `stipResolved` signal wakes it, and it re-applies the outcome
  (AS-8.3). A superseded version completes as `SUPERSEDED` (AS-9.5). The `getState()` query
  returns the step, status, outcomes and attempts.
- **Task queues** per env: orchestration, validation and external.
- **Activity retries** follow NFR-03 (1 s, ×2, 1 min max, 5 attempts). A business FAIL is a
  return value and is never retried.
- **`applyOutcome`:**
  - ERROR → PENDING_REVIEW;
  - any PENDING stip → STIPULATED;
  - otherwise APPROVED, but only with a complete RISC and TILA PASS (AS-8.10). Otherwise the
    version is held in PENDING_REVIEW, audited `RISC_INCOMPLETE`.

  A check that passes now auto-satisfies its own earlier stips (AS-8.8).
- **`disburse`:**
  - re-checks latest, APPROVED and no PENDING stip in one transaction before calling;
  - pays the RISC `amount_financed` to the dealer (AS-8.9);
  - is idempotent: one `disbursements` row, no second payment on retry;
  - 5xx is retried; 4xx and malformed responses are not, and write an ERROR audit row.
- **Recorded histories** (S1: LA-1001, LA-1002; S2: LA-1001, LA-1006) are replayed in
  `make test-unit`, which is groundwork for NFR-07. Replay caught a real break in S2: old
  payloads lacked the new config fields. `RulesConfig` now fills fields added later from the file
  defaults when replaying.

### `funding-api`: the Spring Boot app (one process: API, workers, relay)

- **Start-workflow API (FR-03):**
  - `POST /api/v1/funding-workflows` returns:
    - 202 when it starts a run;
    - 200 `alreadyStarted` while a run is open (duplicate or redelivered message, AS-1.2,
      AS-1.3);
    - 409 `NOT_LATEST_VERSION` or `NOT_PENDING_REVIEW` (AS-1.6);
    - 400 for a malformed request;
    - 401 without the API key (AS-1.7).
  - `GET …/{versionId}` returns the run status and `getState()`.
  - Workflow ID policy: `USE_EXISTING` and `ALLOW_DUPLICATE`.
- **Errors** are RFC 9457 `application/problem+json` with a `reason` code.
- **`local` profile at startup:**
  1. Bootstraps the queues.
  2. `SeedDataLoader` runs every gate and **refuses to seed** on failure, naming the scenario.
     It is idempotent per scenario fingerprint, and latest versions are seeded DRAFT.
  3. `MockStubLoader` runs the contract gate, then replaces **only this env's** stubs on the
     shared WireMock.
  4. Starts the local Lambda runner.
- **Dev endpoint** (`local` only): `POST /api/v1/dev/applications/{id}/submit`.
- **Observability:** JSON logs (logstash encoder) with MDC for `applicationVersionId` and
  `workflowId`, plus springdoc Swagger UI and actuator health.

### Local environment and tooling

- **`scripts/emulator-env.sh`** (ported from homehub):
  - reuses the running emulator;
  - starts a socat forwarder when the emulator's port isn't published;
  - copies the credentials and CA on every source;
  - verifies health and `sts get-caller-identity`;
  - leaves telemetry at the emulator's default.
- **`scripts/db-env.sh`** reuses a running `postgres:18`, otherwise starts `funding-postgres`
  on 55432.
- **`scripts/wiremock-env.sh`** reuses a running WireMock, otherwise starts `funding-wiremock`
  on 8089.
- All three scripts can be sourced from **bash and zsh**. Their "start our own" fallback never
  removes a running container.
- **`Makefile` targets:** `run`, `down` (stops only containers labeled `funding.managed=true`),
  `seed-check`, `seed-regenerate`, `seed-submit-all`, `test-unit`, `test-functional`.
- **Claude Code hook** (owner decision B6): runs `make seed-check` after edits to rules,
  scenarios, mocks, contracts or rule config, and blocks the edit if the gates fail.

---

## 3. Tests

| Suite | Runs against | Tests | Result |
|---|---|---:|---|
| `funding-domain` unit (TILA, price, VIN, GAP, confidence/parsing, RISC completeness, config) | nothing (pure) | 68 | ✅ |
| `funding-persistence` (incl. document freeze) | Testcontainers `postgres:18` | 24 | ✅ |
| `funding-seed` (P-2 and P-5 gates, plus "each gate really fails" tests) | repository data | 17 | ✅ |
| `funding-trigger-lambda` | in-JVM WireMock as the start API | 9 | ✅ |
| `funding-workflows` (incl. parallel fan-out, child isolation, AS-8.10, FR-07) | Temporal `TestWorkflowEnvironment`; Postgres + generated stubs; history replay | 19 | ✅ (1 skipped: the history recorder, runs only on request) |
| `funding-trigger` | nothing | 3 | ✅ |
| `funding-api` unit (start API, seed loader) | MockMvc + Temporal test env + Postgres | 12 | ✅ |
| **Functional** `TriggerPathIT`, `ScenarioE2EIT` | **real** AWS Emulator SQS, Postgres 18, WireMock, Temporal | 27 | ✅ |

**Total: 179 tests** (152 unit, 1 of them skipped, and 27 functional). Test names trace to the
spec (`AS_1_5_…`, `FR_07_…`, `P_2_3_…`).

What the functional tests check:

- **AS-1.1:** a workflow starts within 10 s.
- **AS-1.2:** three deliveries of the same message produce one run, one result per check and
  one stip.
- **AS-1.5:** a 503 from the API leaves the message for redelivery, and it reaches the real
  emulator DLQ after 5 receives, while the other message in the batch is acknowledged.
- **Every generated scenario** (23) reaches its expected status and stips.
- **LA-1031:** the RISC Itemization section spanning pages 3–4 and its per-field bounding boxes
  are persisted.

Each run isolates itself as `FUNDING_ENV=it-<runId>`, with its own database, queues, task
queues, workflow IDs and stubs, and removes them all afterwards (NFR-08).

---

## 4. Acceptance scenarios covered so far

| Area | Covered in S1 and S2 |
|---|---|
| US-1 Trigger | AS-1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7 (AS-1.8 is S5) |
| US-2 TILA | AS-2.1, 2.2, 2.3 |
| US-3 Price parity | AS-3.1, 3.2 |
| US-4 VIN and vehicle | AS-4.1, 4.2, 4.3 |
| US-6 GAP | AS-6.1, 6.2, 6.3, 6.4 |
| US-7 Confidence | AS-7.1, 7.2 |
| US-8 Outcome | AS-8.1, 8.2, 8.9, 8.10 (auto-approval side; manual approve is S4); AS-8.3 and 8.8 implemented, their full tests are S4 |
| US-9 Versioning | AS-9.7; AS-9.3, 9.5 groundwork (latest-version guard, `SUPERSEDED`) |
| FR | FR-02, 03, 04, 05, 06, 07, 08, 10, 11, 12, 13, 14, 16 |
| NFR | NFR-03, 04, 06, 08; NFR-05 partly; NFR-07 groundwork |

---

## 5. Decisions and deviations

These are recorded in full under "S1 notes" and "S2 notes" in `tasks.md`.

S2:

- **Needs your confirmation:** a version whose checks all pass but whose RISC is incomplete is
  held in `PENDING_REVIEW` with audit reason `RISC_INCOMPLETE`. AS-8.10 didn't say which status;
  this is added to `spec.md` as a clarification awaiting owner confirmation.
- Confidence and required-field gating are cross-cutting rules with no single check; the
  coverage gate handles them.
- A VIN with surrounding whitespace is a mismatch (AS-4.3 allows only upper-casing).
- The GAP registry file is part of `rules.lock`, and is seed-owned locally.
- Histories are recorded per slice, and old ones keep replaying.

S1:

- Docker-only Testcontainers tests are named `*Test` so they run in `make test-unit`. `*IT` is
  reserved for tests against the shared local services.
- The generated scenario table in `seed-data.md` holds only scenarios that have an intent file.
  Later scenarios sit in a hand-written "Planned scenarios" table until their slice lands.
- `TILA_PAYMENT` also checks the payment schedule against `total_of_payments` (C-9).
- The disbursement request carries `loanApplicationId`, so each scenario gets its own stub.
- Temporal is wired with the plain SDK rather than `temporal-spring-boot-starter`.
- Flyway is pinned to 11.20.3, because Boot's 11.7.2 warns that PostgreSQL 18 is untested.

---

## 6. Known gaps (next slices)

| Slice | Adds |
|---|---|
| **S3 – Income + mock catalog** | The income API via WireMock (about 150 catalog stubs), retries and timeouts, the SSN-never-logged test, the `mocks.lock` outcome-flip guard, `MockControl` |
| **S4 – Funder operations** | Funder API, approve/reject/re-run/retry signals, manual stips, the status-machine enforcer, **30-day expiry** (until then, STIPULATED workflows stay open in local Temporal) |
| **S5 – Versioning** | Resubmission and supersede, document-version re-runs (AS-1.8), dev add-version/add-document endpoints |
| **S6 – Breadth & hardening** | Disbursement matrix IT, volume seed and test, the full replay suite, worker-restart test, observability |
