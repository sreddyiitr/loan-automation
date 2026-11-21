# Implementation Summary

What has been built for spec 001 (auto loan funding validation), and what is left open.
Status as of **2026-11-20**: all six slices are done: **S1 (walking skeleton)**, **S2 (document rules)**,
**S3 (income + mock catalog)**, **S4 (funder operations)**, **S5 (versioning)** and **S6 (breadth and
hardening)**. Tasks and their deviations are in
[`specs/001-loan-funding-validation/tasks.md`](specs/001-loan-funding-validation/tasks.md).

| Item | Where |
|---|---|
| Principles | [`specs/constitution.md`](specs/constitution.md) (P-1 … P-5) |
| Requirements | [`spec.md`](specs/001-loan-funding-validation/spec.md) (user stories, acceptance scenarios `AS-x.y`) |
| Design | [`plan.md`](specs/001-loan-funding-validation/plan.md), [`data-model.md`](specs/001-loan-funding-validation/data-model.md), [`seed-data.md`](specs/001-loan-funding-validation/seed-data.md) |
| Task list and progress | [`tasks.md`](specs/001-loan-funding-validation/tasks.md) (S1: 32 of 32, S2: 11 of 11, S3: 14 of 14, S4: 9 of 9, S5: 8 of 8, S6: 8 of 8; each slice's notes list deviations) |
| Branches | S1–S5 merged to `main` (PRs #2–#6, plus the Temporal walkthrough in #7); S6 on `feat/001-s6-hardening` |
| Spec 002 | **Spike D0 merged (PR #10).** D1 (walking skeleton) in progress on `feat/002-d1-roles`: **T101–T107 done**: the funder API takes OIDC bearer tokens; the overview has expiry, workflow step and payment; the agent relays the user's token; the browser signs in with OIDC (PKCE) and opens deals by loan id; Take is a confirmed, race-safe write; and `make dashboard` starts it all with one command. See "Spec 002" below and [`ui_summary.md`](ui_summary.md) |

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
                                ├─ in parallel: TILA, PRICE, VIN, GAP, INCOME children → pure rule → result + stips
                                │    (INCOME first calls the Income Verification API on the external queue; WireMock)
                                ├─ applyOutcome → APPROVED / STIPULATED / PENDING_REVIEW (audited;
                                │                 APPROVED needs a complete RISC and TILA PASS, AS-8.10)
                                ├─ APPROVED → disburse (WireMock, idempotency key = workflow ID) → FUNDED
                                └─ otherwise wait for a funder until submitted_at + 30 days → EXPIRED
                                     funder API (X-Funder-Id): resolve/add stips, approve, reject, re-run,
                                     retry disbursement, reassign → guarded, audited DB write → signal
                                     new latest document → outbox → … → documentsUpdated: re-run every check
                                     V2 submitted → supersede V1's run → SUPERSEDED (V1's status kept)
```

Measured on the developer machine: submit → first validation result in **under 1 s** (AS-1.1
allows 10 s). All **35** generated scenarios reach their expected status, check outcomes and stips; WireMock
reports **0 unmatched requests**. The full table, generated from the intents, is in
[`seed-data.md`](specs/001-loan-funding-validation/seed-data.md#scenarios).

| Area | Scenarios | Result |
|---|---|---|
| Happy path | LA-1001 | FUNDED, one disbursement of 28450.00 to DLR-100 |
| TILA (US-2) | LA-1002, 1003 fail; LA-1004 (0% APR), 1005 (rounding), 1034 (final payment) pass | STIPULATED `STIP_TILA_MATH_DISCREPANCY` / FUNDED |
| Price parity (US-3) | LA-1006: Buyer's Order 1¢ beyond the tolerance | STIPULATED `STIP_PRICE_MISMATCH` |
| VIN and vehicle (US-4) | LA-1007 (VIN on GAP), 1008 (model), 1010 (letter O), 1011 (app VIN) fail; LA-1009 (case/space) passes | STIPULATED `STIP_VIN_MISMATCH` / FUNDED |
| GAP (US-6) | LA-1018 (expired form), 1019 (other state), 1020 (lender), 1021 (both) fail; LA-1022 (no GAP) passes | STIPULATED `STIP_GAP_FORM_MISMATCH` and/or `STIP_LENDER_MISMATCH` / FUNDED |
| Income (US-5) | LA-1012 (over by 33%), 1014 (1 bp over), 1015 (under-stated), 1027 (with TILA and VIN) fail; LA-1013 (exactly 15%), 1017 (flaky API) pass; LA-1016 (API down) errors; LA-1035 (no payroll record) needs review | STIPULATED `STIP_INCOME_VARIANCE_EXCEEDED` / FUNDED / PENDING_REVIEW held / STIPULATED `STIP_PROOF_OF_INCOME` |
| Disbursement (C-8) | LA-1030: disbursement API down | APPROVED, held for a funder retry |
| Versioning (US-9, AS-1.8) | LA-1028: v1 (LA-1006's deal) seeded STIPULATED as history, v2 at baseline; LA-1029: RISC v1 superseded by v2; LA-1032: starts as LA-1006, then a Buyer's Order v2 re-runs every check | v2 FUNDED, v1 stays STIPULATED with its stip / FUNDED (reads v2) / STIPULATED, then FUNDED with the price stip auto-SATISFIED |
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
  - `contracts/income-verification.openapi.yaml` and `test/data/mocks/income-verification.catalog.yaml`
    (S3) hold the variance bands × magnitudes, NOT_FOUND, response shapes and nine fault classes.
  - `MockStubGenerator` builds **322 WireMock stubs**: 252 from the two catalogs (66
    disbursement, 186 income) and one per scenario per API (35 + 35). Delays and retry counts
    come from the config.
  - Four P-5 gates (plus the flip guard in `seed-regenerate`):
    - **Contract:** every stub validates against the OpenAPI spec; only fault classes may be
      marked `violatesContract`.
    - **Coverage:** every class has at least `minPerBand` entries and every amount appears.
    - **Band oracle:** each stub is replayed through the app's response classifier under the
      retry budget, then the real rule, and must match the stub's independently declared outcome.
    - **`mocks.lock`:** the generated stubs match the committed lock.
  - Every mapping carries `metadata.env`, and there is no catch-all stub.

### `funding-persistence`: Postgres 18 schema and repositories

- `V2__documents_frozen.sql` (S2, AS-9.7): inserting a document for an APPROVED, FUNDED, REJECTED
  or EXPIRED version fails with SQLSTATE `P0001` `DOCUMENTS_FROZEN`.
- The GAP registry is loaded into the snapshot, and `GapRegistryRepository` replaces it from
  `test/data/gap-form-registry.yaml` when seeding locally.
- `V1__baseline.sql`: the spec 001 data model (versions, documents, sections, fields, stips,
  results, outbox, audit, GAP registry, disbursements). The S2 document-freeze trigger and the
  S5 document-added trigger are separate migrations. A `local`-only migration adds
  `seed_scenarios`.
- `V3__document_added.sql` (S5, AS-1.8): a new latest document on the latest version, while it is
  PENDING_REVIEW or STIPULATED, writes a `DOCUMENT_VERSION_ADDED` outbox row in the same
  transaction. DRAFT and non-latest versions never trigger.
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
  - `ApplicationWriter` (S5): `addVersion`/`addVersionCopying` lock the latest version, take its
    flag and insert version n+1 in one transaction (AS-9.1; of two concurrent calls one wins);
    `addDocumentVersion` makes a new latest document and takes the flag off the old one (AS-9.4).
    The copy of a version copies the SSN token in SQL, never reading it (FR-13).

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

- **Volume seed** (S6, T603): `VolumeSet` builds one application per mock catalog entry: 186 income
  applications `LA-5xxx`, carrying the entry's SSN token and stated income, and 66 disbursement
  applications `LA-6xxx`, carrying the entry's loan id and amount. Each also gets a generated stub
  for the other API. The expected checks, status and stips are derived from the rule engine and the
  response simulator. It's loaded only with `funding.seed.volume=true` (local).

### `funding-workflows`: Temporal

- **`FundingWorkflow`:**
  - loads the snapshot;
  - fans out the enabled checks that have a rule (TILA, PRICE, VIN, GAP and INCOME) **in
    parallel** as children `<id>-<CHECK>-a<attempt>` (FR-04). INCOME sits behind the
    `income-check` version marker, so runs started before S3 replay without it;
  - turns a child's technical failure into `ERROR` (held in PENDING_REVIEW);
  - runs `applyOutcome`;
  - when APPROVED, disburses and then marks the version FUNDED.

  Otherwise it waits for a funder until `submitted_at + funding.workflow.expire-after` (P30D),
  then expires the version (AS-8.6). Funder actions are DB writes made by the API; the signals
  (`stipResolved`, `approve`, `reject`, `rerunChecks`, `retryDisbursement`) are wake-ups after which
  it re-runs requested checks (next attempt, fresh snapshot) and re-settles (AS-8.3, 8.4, 8.5, 8.7).
  After 1,000 signals it continues as new, carrying outcomes, attempts and the deadline. A superseded version completes as `SUPERSEDED` (AS-9.5). The `getState()` query
  returns the step, status, outcomes and attempts.

  S5: `documentsUpdated(documentId)` re-runs **every** check against the latest documents (AS-1.8,
  C-10), once per document; `supersede()` cancels the running children and completes
  `SUPERSEDED` without touching the status, keeping results and stips as history (AS-9.2). The
  children run in a cancellation scope; with no supersede the commands are the same as before,
  so earlier histories still replay without a version marker. A re-run requested while checks
  are running runs before the status is settled (`rerun-before-settling` marker), so a version is
  never approved on documents that were replaced meanwhile.
- **Status machine** (FR-09): `VersionStatus` holds spec §6; `VersionRepository.setStatus` is the
  only status writer and rejects any other transition (not retried).
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
- **`verifyIncome`** (external queue) loads the SSN token by version ID (FR-13) and sends it only
  to the Income API. Timeouts, connection resets, 5xx and 429 are retried (429 honours
  `Retry-After`, capped at the retry policy's maximum interval, C-13); malformed, schema-invalid
  and other 4xx responses are not. NOT_FOUND is an answer (C-12), not a failure. The response
  classifier is shared with the seed gates, so mocks are judged exactly as the app judges them.
- **Recorded histories** (S1: LA-1001, LA-1002; S2: LA-1001, LA-1006, and LA-1006 with INCOME
  enabled as production ran it; S3: LA-1001, LA-1006; S4: a signal-heavy run and an expired run;
  S5: a document re-run that funds, a superseded run, a document during the first validation;
  the recorder never overwrites a file)
  are replayed in
  `make test-unit`, which is groundwork for NFR-07. Replay caught a real break in S2: old
  payloads lacked the new config fields. `RulesConfig` now fills fields added later from the file
  defaults when replaying. In S3 it caught a second one: a pre-S3 run would have scheduled an
  INCOME child on replay; the `income-check` version marker fixes that.

- **Log correlation** (S6, NFR-05): `MdcInterceptor`, a worker interceptor, puts the parent
  `workflowId`, `applicationVersionId` and (once the activity has read the version)
  `loanApplicationId` on every activity log line, and always clears them. Before S6 the
  activities set them and never removed them, so reused threads mislabelled later lines.
- **Replay suite** (S6, NFR-07): `FundingWorkflowReplayTest` replays every recorded history, and
  checks that each `Workflow.getVersion` change has histories from both sides of it.

### `funding-api`: the Spring Boot app (one process: API, workers, relay)

- **Funder API** (US-10, `FunderController`/`FunderService`; contract checked in as
  `contracts/funder-api.openapi.yaml` for spec 002): overview, version history, stips (list,
  manual create, SATISFIED/WAIVED with a reason and a conditional update), reassign, results with
  expected vs actual (`history=true`), documents and fields with confidence and boxes, approve,
  reject, re-run, retry disbursement, audit trail. Every write needs `X-Funder-Id`, locks the
  version, refuses non-latest or terminal versions with 409 (`VERSION_EXPIRED` for expired ones),
  writes and audits in one transaction, then signals the workflow. RISC-bound stips can't be
  resolved by hand (409 `RISC_STIP_NOT_WAIVABLE`).

- **Start-workflow API (FR-03):**
  - `POST /api/v1/funding-workflows` returns:
    - 202 when it starts a run;
    - 200 `alreadyStarted` while a run is open (duplicate or redelivered message, AS-1.2,
      AS-1.3);
    - 409 `NOT_LATEST_VERSION` or `NOT_PENDING_REVIEW` (AS-1.6);
    - before starting a version, `supersede` to the open run of every older version of the
      application (AS-9.2; none open is a no-op, AS-9.6);
    - for `DOCUMENT_VERSION_ADDED`: 200 `documentsUpdated` after moving a STIPULATED version back
      to PENDING_REVIEW (audited once per document, C-21) and signalling the run; 409
      `NO_OPEN_RUN`, or `NOT_RERUNNABLE` once the version is no longer under review (C-20);
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
- **Dev endpoints** (`local` only), under `POST /api/v1/dev/applications/{id}/`:
  - `submit`: the latest DRAFT -> PENDING_REVIEW (runs the real trigger path).
  - `versions` (S5, AS-9.1): the next version as a DRAFT copy of the latest (application fields,
    assignee and latest documents), with the body's documents replacing those of the same type;
    409 `VERSION_FUNDED` / `DISBURSEMENT_STARTED` once the latest is paid or being paid (C-24).
  - `documents` (S5, AS-9.4): a new latest version of one document; 409 `DOCUMENTS_FROZEN` once
    the version is APPROVED or final (AS-9.7).
- **Seeding** (S5): an earlier version (LA-1028 v1) is seeded non-latest with its status and with
  result and stip rows computed by the conformance gate's `outcomes()`.
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
- **`scripts/oidc-env.sh`** (spec 002) reuses a running `mock-oauth2-server` that publishes a port,
  refuses one that doesn't (printing the fix), otherwise starts `funding-oidc` on 8090. It pulls
  the image only if it isn't local, and exports `OIDC_ISSUER_URI`.
- All four scripts can be sourced from **bash and zsh**. Their "start our own" fallback never
  removes a running container.
- **`Makefile` targets:** `run`, `down` (stops and removes every project container: labeled `funding.managed=true` or named `funding-*`; volumes kept),
  `seed-check`, `seed-regenerate`, `seed-submit-all`, `test-unit`, `test-functional`; spec 002
  adds `dashboard`, `dashboard-preflight`, `test-dashboard` and `test-dashboard-e2e`.
- **`scripts/dashboard-preflight.sh`** (spec 002, AS-10.2) names each missing prerequisite with
  its install command and exits 1. Ollama with `gemma4:e4b` is only a warning (AS-10.4).
- **`scripts/dashboard-run.sh`** (`make dashboard`, AS-10.1/10.3): preflight, then reuse or start
  the funding API (`run-local.sh`), the agent and the web app, and submit the seeded DRAFTs. It
  reuses a part only if it answers as ours. `Ctrl+C` stops the full process tree of what it
  started, listed before any kill (the first version killed only the port's listener, and the
  Maven `spring-boot:run` wrapper kept the API up on 8080).
- **Claude Code hook** (owner decision B6): runs `make seed-check` after edits to rules,
  scenarios, mocks, contracts or rule config, and blocks the edit if the gates fail.

---

### Spec 002: funder dashboard (`dashboard/`)

- **`dashboard/agent`** (Python 3.12, `uv`; the `funder_agent` package since T104): an A2A
  server (`a2a-sdk` 0.3.26) whose own `AgentExecutor` routes in code, with no LLM and no ADK.
  - It relays the browser's bearer token to the funder API and never creates one (FR-11).
  - A text request (`open LA-1006`) returns the `deal_<versionId>_overview` surface: deal facts,
    days to expiry, payment status, an in-progress note, a Temporal UI link, and the version
    history.
  - `deal.take` assigns the deal to the token's user and returns a data-model update from a re-read.
  - With no token, or when the funder API refuses, it returns a `notice` surface with a plain
    message from `messages.yaml` (FR-06).
- **`dashboard/web`** (React 19, Vite, `@a2ui/react` 0.15.0): renders v1.0 surfaces with our
  catalog, which extends the Basic Catalog with a custom `DataTable`. It talks plain A2A JSON-RPC
  through a Vite proxy (`/agent` → port 8091), sends the user's token (held in memory by
  `src/auth.ts`), and shows the actions it sent, for the test.
- **Decisions:** routing in our own `AgentExecutor`, not ADK's `before_agent_callback`; one
  catalog per surface; versions pinned. Recorded in `specs/002-funder-dashboard/plan.md`, with the
  details and pitfalls in `a2ui-notes.md` §12d.

## 3. Tests

| Suite | Runs against | Tests | Result |
|---|---|---:|---|
| `funding-domain` unit (TILA, price, VIN, GAP, income, API classifier, confidence/parsing, RISC completeness, status machine, config) | nothing (pure) | 136 | ✅ |
| `funding-persistence` (incl. document freeze, guarded status writes, document-added trigger, new version in one transaction and the concurrent-V2 race) | Testcontainers `postgres:18` | 38 | ✅ |
| `funding-seed` (P-2 and P-5 gates incl. band oracle and flip guard, plus "each gate really fails" tests, the S5 history / prior-document / amend vocabulary, and the S6 volume set) | repository data | 37 | ✅ |
| `funding-trigger-lambda` | in-JVM WireMock as the start API | 9 | ✅ |
| `funding-workflows` (incl. parallel fan-out, child isolation, AS-8.10, FR-07, income retries/timeouts/429, SSN never leaks, funder signals, 30-day expiry, continueAsNew, supersede, document re-runs, the AS-9.3 write race, replay suite, MDC interceptor) | Temporal `TestWorkflowEnvironment` (time-skipping); Postgres + generated stubs; history replay | 64 | ✅ (1 skipped: the history recorder, runs only on request) |
| `funding-trigger` | nothing | 3 | ✅ |
| `funding-api` unit (start API incl. supersede and document events, seed loader incl. history, funder API, bearer-token security (`FunderAuthTest`), spec 002 overview fields, take and unassign, dev endpoints, OpenAPI contract) | MockMvc + Temporal test env + Postgres | 55 | ✅ |
| **Functional** `TriggerPathIT`, `ScenarioE2EIT`, `IncomeVerificationMatrixIT`, `MockControlIT`, `FunderActionsE2EIT`, `VersioningE2EIT`, `DisbursementMatrixIT`, `WorkerRestartIT`, `ObservabilityIT` | **real** AWS Emulator SQS, Postgres 18, WireMock, Temporal, mock OIDC provider | 68 | ✅ |
| **Volume** `FundingVolumeIT` (`make test-functional-volume`) | the same, 252 applications at once | 1 | ✅ healthy-API p95 12.2 s |

| **Spec 002 agent** `dashboard/agent/tests` (pytest): every surface through `a2ui-core` strict validation (NFR-05), overview text, token relay, reason messages, routing | nothing (a fake funder API) | 46 | ✅ |
| **Spec 002 browser** `dashboard/web/e2e/spike.spec.ts` (Playwright), each signing in on the real login page: overview; Take with confirmation; someone else took it first; unknown loan; unknown user; signed out | the agent, the running app (`make run`, LA-1006 submitted), the OIDC provider with its config, Chromium | 6 | ✅ |

**Total: 411 tests** (342 unit, 1 of them skipped, 68 functional and 1 volume). Spec 002 adds 15 of them (5 `FunderAuthTest`, 9 overview, take and unassign tests in `FunderApiTest`, 1 functional `AS_10_9_realTokensFromTheMockProvider`) plus 46 agent tests and 6 Playwright tests. Test names trace to the
spec (`AS_1_5_…`, `FR_07_…`, `P_2_3_…`).

What the functional tests check:

- **AS-1.1:** a workflow starts within 10 s.
- **AS-1.2:** three deliveries of the same message produce one run, one result per check and
  one stip.
- **AS-1.5:** a 503 from the API leaves the message for redelivery, and it reaches the real
  emulator DLQ after 5 receives, while the other message in the batch is acknowledged.
- **Every generated scenario** (35) reaches its expected check outcomes, status and stips.
- **Every Income Verification catalog stub** (186: variance bands × magnitudes, NOT_FOUND,
  response shapes, nine fault classes) goes through the real HTTP client, the production retry
  policy and the INCOME rule, and matches its band's independent expected outcome.
- **Funder actions** (13 cases from `seed-data.md`): waive and satisfy to FUNDED, partial
  resolution, switch the income intent and re-run, manual approve over ERROR, manual stip before
  disbursement, retry after a down disbursement, two expiries (stip kept, audit, 409
  `VERSION_EXPIRED`), reject, terminal 409s, RISC stips not waivable, reassign.
- **Versioning** (`VersioningE2EIT`): a new Buyer's Order or RISC re-runs every check and funds
  (LA-1032, LA-1002, LA-1023, stips auto-SATISFIED by `system:`); LA-1028 v2 funds while v1
  stays STIPULATED history and refuses writes; V2 created and submitted while V1 waits
  supersedes V1's run; documents are frozen on APPROVED, FUNDED and EXPIRED versions.
- **Every disbursement catalog entry** (66, `DisbursementMatrixIT`) through the real parent's payment
  path: paid classes fund exactly once (a timed-out call is retried with the same key and replayed);
  refused ones are not retried and stay APPROVED with an ERROR audit row; EXHAUSTED retries to the
  limit and stays APPROVED.
- **Worker restart** (`WorkerRestartIT`, NFR-02): killed mid fan-out and while waiting for a funder,
  the runs finish the same way, with one result per check and one payment.
- **Observability** (`ObservabilityIT`): activity log lines carry the workflow, version and loan ids;
  Temporal SDK meters are in `/actuator/metrics`.
- **Volume** (`FundingVolumeIT`, NFR-01): 252 applications, one per catalog entry, submitted at once;
  all settle as derived in about 100 s; with healthy APIs p50 7.3 s and p95 12.2 s.
- **`MockControl`** swaps a scenario to another generated intent, restores it and counts calls,
  and rejects intents the generator doesn't produce.
- **LA-1031:** the RISC Itemization section spanning pages 3–4 and its per-field bounding boxes
  are persisted.

Each run isolates itself as `FUNDING_ENV=it-<runId>`, with its own database, queues, task
queues, workflow IDs and stubs, and removes them all afterwards (NFR-08).

---

## 4. Acceptance scenarios covered so far

| Area | Covered in S1–S6 |
|---|---|
| US-1 Trigger | AS-1.1–1.8 |
| US-2 TILA | AS-2.1, 2.2, 2.3 |
| US-3 Price parity | AS-3.1, 3.2 |
| US-4 VIN and vehicle | AS-4.1, 4.2, 4.3 |
| US-5 Income | AS-5.1, 5.2, 5.3, 5.4; C-3, C-12, C-13 |
| US-6 GAP | AS-6.1, 6.2, 6.3, 6.4 |
| US-7 Confidence | AS-7.1, 7.2 |
| US-8 Outcome | AS-8.1–8.10 |
| US-10 Funder API | AS-10.1–10.9 |
| US-9 Versioning | AS-9.1–9.7 |
| FR | FR-02 to FR-17 |
| NFR | NFR-01 (volume p95 12.2 s with healthy APIs), NFR-02 (worker restart), NFR-03, 04, 06, 07 (replay suite), 08; NFR-05 for activities and the relay |

---

## 5. Decisions and deviations

These are recorded in full under each slice's notes in `tasks.md`.

Spec 002, D1 (T101–T102):

- **The funder API is an OAuth2 Resource Server.** Spring Security checks every funder API call,
  reads included: the signature from the issuer's JWKS, `iss`, audience `funding-api`, `exp` and
  `nbf`. The actor is the token's `sub`.
- **Roles come from `funding.auth.users`** (the `UserDirectory`), not from the token, because the
  mock provider sends no role claim (D-13). An unlisted user gets 403 `UNKNOWN_USER`; no valid
  token gets 401 `UNAUTHORIZED`. Roles aren't enforced on actions yet; that's D2.
- **`X-Funder-Id` is gone**, with no compatibility rule. Spec 001's tests use tokens with no
  assertion changed: `FunderApiTest` signs its user in as SUPERVISOR (who may do everything, D-11),
  and the functional tests get `client_credentials` tokens from the shared provider. The dev
  endpoints keep their optional `X-Funder-Id`, which only labels the audit row.
- **Browser sign-in (T105):** OIDC Authorization Code with PKCE via `oidc-client-ts` 3.5.0, with
  the token in memory. By default the mock provider gives browser-login tokens `aud=default`, so
  it runs with `scripts/oidc-config.json` (tokens for the dashboard's client carry
  `aud=funding-api`), and `oidc-env.sh` refuses a provider without it. The API's audience check
  stays strict.
- **Take (T106):** `POST /versions/{vid}/take` assigns to the caller only if nobody has it,
  under the row lock (409 `ALREADY_ASSIGNED`), so two stale screens can't both win.
  `PUT …/assignee` also unassigns with `null` (AS-2.4). In the dashboard, Take sits behind a
  `Modal` confirmation. After it, the agent rebuilds the surface, because the controls changed
  and that's what closes the Modal.
- **Overview fields (T103):** `expiresAt` is `submittedAt` + `funding.workflow.expire-after`, the
  workflow's own deadline, computed from the DB so a worklist can use it too. `inProgress` and
  `workflowStep` come from the workflow query. `payment.state` is NOT_STARTED, IN_FLIGHT, PAID,
  FAILED or UNKNOWN.
- **`make run` on a reused emulator:** `InfraBootstrap` now looks a queue up before creating it.
  The emulator refused `CreateQueue` for a queue that already had a redrive policy, so every second
  `make run` on the same emulator failed.

S6:

- Disbursement catalog stubs now match the app's own idempotency key (its workflow ID) plus a
  per-entry loan id and amount. Before S6 the real client could never reach them; only the gates'
  simulator used them.
- The volume run times "submission to the first status the workflow sets". ERROR holds keep
  PENDING_REVIEW, so they are checked but not timed.
- `funding.checks.enabled` is gone. The enabled set stays in `VersionView` because it is part of
  recorded histories.
- `make verify-emulator` exists but **hasn't been run**: it creates and removes resources in the
  shared emulator, so the first run is yours.

S5:

- **C-23 confirmed by the owner (2026-11-20):** a new document re-runs every check, and a check
  that still fails raises its stip again even if a funder had waived or satisfied it ("you never
  know what the new version has").
- **Needs your confirmation:** C-20 to C-22 and C-24 in `spec.md`. C-24: no new version once the
  latest is paid or being paid.
- Review fixes: a re-run requested during a validation now runs before the status is settled
  (it could approve and pay on replaced documents; `rerun-before-settling` marker), and a new
  version is refused while the latest one's payment started or completed (double-payment risk).
- `documentsUpdated` carries the document ID so an at-least-once event re-runs only once.
- Supersede happens when V2 is submitted, not when it is created (C-22).
- Seed intents gained `history:`, `priorDocuments:` and `amend:`; the conformance gate checks
  each history version and each amendment against its own `expect`.
- The functional tests use the intents' generated corrected documents (`amend:`), never typed ones.

S4:

- **Confirmed by the owner (2026-11-18):** C-15 to C-19 in `spec.md` (RISC stips can't be SATISFIED by hand
  either; manual stip and reject wait while a disbursement is in flight; only a funder retry
  re-pays; which statuses accept which actions; no results after a version closed).
- The API writes, the workflow follows: signals are wake-ups after a guarded, audited DB write.
- S4's loop is behind the `funder-signals` version marker; runs from before S4 don't expire.
- The self-review fixed lost signals (a signal arriving while the run was busy) and made
  continueAsNew wait for unhandled signals.
- The functional expiry test back-dates `submitted_at` instead of setting `expire-after=PT20S`.
- The build uses only the jakarta `swagger-core` (the javax copy broke `/v3/api-docs`).

S3:

- INCOME is guarded by the `income-check` `Workflow.getVersion` marker (replay of pre-S3 runs).
- A failing variance is shown with enough decimals never to read as equal to the threshold
  (display only; the comparison is exact, FR-15).
- Activity tests regenerate stubs from their own short budgets; the matrix IT uses production's.
- Spring JDBC logs bound parameters at TRACE: `StatementCreatorUtils` must never run at TRACE
  outside tests, or SSN tokens reach the logs on writes.
- The flip guard matches a `RULE-CHANGES.md` entry by rule id and date; reviewers check the
  entry is new.
- `db-env.sh` no longer reuses Testcontainers' throwaway `postgres:18`.

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

## 6. Known gaps

Spec 001's slices are all done. What is still open:

| Gap | Notes |
|---|---|
| First `make verify-emulator` run | Written and syntax-checked; needs the owner's go-ahead (it creates and deletes a Lambda and an RDS instance in the shared emulator). |
| Confirm C-20 to C-22 and C-24 | Recorded in `spec.md`; C-23 is confirmed. |
| NFR-05 beyond activities and the relay | Logs from workflow code use the SDK's own `WorkflowId` key; HTTP request logs in the start and funder APIs aren't correlated yet. |
| Spec 002 | The funder dashboard (ADK + A2UI): specified and planned in [`specs/002-funder-dashboard/`](specs/002-funder-dashboard/) (`spec.md`, `plan.md`, `tasks.md`). Built so far: D1's walking skeleton (T101–T107): OIDC sign-in end to end, the overview, Take, and `make dashboard`. Not yet: T108 (Playwright AS ids), worklist, tabs, role enforcement (D2), assistant. Overview in [`ui_summary.md`](ui_summary.md). |
| Dashboard gaps | The token is in memory only, so a reload means signing in again (no silent renew yet). `npm audit` reports 4 high and 1 moderate findings, all in the A2UI packages pinned at D0 and in `zod`. The web app has no error boundary yet (one bad prop blanks the page, `a2ui-notes.md` §12d). The agent's catalog is defined in Python (`catalog.py`), not yet as the plan's `dashboard/catalog/funder_catalog.json`. |
