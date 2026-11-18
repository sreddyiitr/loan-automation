# Tasks – Spec 001

Ordered, test-first task list derived from `plan.md` (*Delivery Slices*, owner decision A2). Read `specs/constitution.md` first.

## How to read this list

- **IDs** are `T<slice><nn>` (T101 … T699). They are stable: don't renumber, append.
- **[P]** = can run in parallel with the other [P] tasks of the same group (different files, no dependency).
- **Test-first:** inside each group the **test task comes first** and must be red before the implementation task starts (P-1). Tests are named after their scenario: `AS_x_y_…`.
- **Rule changes** (anything under `funding-domain/**/rules/**`, `RuleCatalog`, `funding.*` rule config) are done together with their `test/data/scenarios/*.yaml` intent, followed by `make seed-regenerate` (P-2). Never hand-edit `rules.lock`, `mocks.lock`, stubs, or the generated table in `seed-data.md`.
- **Slice exit gate:** every slice ends green on `make test-unit`, `make seed-check` and `make test-functional`, and is merged as its own PR (`feat/001-s<n>-…`).
- **Enabled checks.** Children arrive slice by slice. `funding.checks.enabled` (default: all five) lists the children the parent fans out to; the parent runs only the enabled checks that have a rule in `RuleCatalog`, so LA-1001 reaches FUNDED in S1 without narrowing any profile. The property is removed in S6 (T606) once all five exist.

---

## S1 — Walking skeleton

**Proves:** Temporal, emulator SQS, the Lambda handler path and the P-2/P-5 gates, end to end, with the TILA check only.
**Exit:** LA-1001 → FUNDED, LA-1002 → STIPULATED through the real trigger path; AS-1.1–1.7, AS-2.1–2.3, AS-8.1, AS-8.2, AS-8.9 green.

### 1a. Build & local services

- [x] **T101** Maven multi-module skeleton, groupId `com.funding`, Java 21, Spring Boot 3.5.x parent, AWS SDK v2 BOM, Temporal SDK, Testcontainers, WireMock 3.x, JUnit 5, AssertJ. Modules: `funding-domain`, `funding-persistence`, `funding-workflows`, `funding-trigger`, `funding-trigger-lambda`, `funding-seed`, `funding-api`. Surefire (unit) and Failsafe (`*IT`) are split. `funding-domain` and `funding-trigger-lambda` have no Spring dependency (enforced with `maven-enforcer` banned dependencies).
- [x] **T102** [P] `scripts/db-env.sh`: reuse a running `postgres:18` container that publishes a port (`DB_CONTAINER` override), otherwise start `funding-postgres` (`funding.managed=true`, `127.0.0.1:55432:5432`, volume `funding-pgdata`). Export `FUNDING_DB_URL/USER/PASSWORD`. Creates database `funding` if missing.
- [x] **T103** [P] `scripts/emulator-env.sh`, ported from `../homehub/scripts/emulator-env.sh`: pick → port from logs → published port or `funding-emulator-proxy` socat forwarder → creds/CA copy on every source → health + `sts get-caller-identity` = `000000000000` → fallback `funding-emulator`. Export the SDK env with `AWS_REGION=us-west-2`. **Telemetry stays at the emulator default**: drop homehub's telemetry-off flags.
- [x] **T104** [P] `scripts/wiremock-env.sh`: reuse a single running `wiremock/wiremock` container that publishes a port (`WIREMOCK_CONTAINER` override), otherwise start `funding-wiremock` (`127.0.0.1:8089:8080`, `--disable-banner`, journal on). Export `FUNDING_INCOME_BASE_URL`, `FUNDING_DISBURSEMENT_BASE_URL`.
- [x] **T105** `Makefile` + `run-local.sh`: `run`, `down` (stops only `funding.managed=true` containers), `seed-check`, `seed-regenerate`, `seed-submit-all`, `test-unit`, `test-functional`, `test-functional-volume` (stub target until S6). Land `seed-check` as early as possible, because the B6 hook (`scripts/hooks/seed-check-hook.sh`) stays a no-op until the target exists.

### 1b. Persistence

- [x] **T106** Test: `MigrationIT` (Testcontainers `postgres:18`): Flyway applies cleanly; partial unique indexes `idx_unique_active_app_version`, `idx_unique_active_doc_version`, `idx_unique_pending_stip`, `idx_current_result` reject a second active row; status CHECK rejects an unknown status.
- [x] **T107** Test: `OutboxTriggerIT`: `AS_1_1_pendingReviewWritesOneOutboxRow` (DRAFT→PENDING_REVIEW writes one `APPLICATION_VERSION_PENDING_REVIEW` row with `{loan_application_id, application_version_id}`), `AS_1_4_otherTransitionsWriteNoOutboxRow` (incl. STIPULATED→PENDING_REVIEW), `submittedAtStampedOnceAndNeverOverwritten`.
- [x] **T108** `V1__baseline.sql`: every table in `data-model.md` except the document-freeze trigger (S2) and the document-added trigger (S5): `loan_applications`, `application_versions`, `documents`, `document_sections`, `extracted_fields` (no `is_flagged` column, B4), `stipulations`, `validation_results`, `outbox_events` + `enqueue_pending_review`, `stamp_submitted_at`, `gap_form_registry`, `audit_log`, `disbursements`. `seed_scenarios` goes in a `local`-only Flyway location.
- [x] **T109** Repositories on `JdbcClient` (`funding-persistence`): versions, documents + fields (latest-only reads), results, stips, outbox, audit, disbursements. Every write method that can be superseded takes a `versionId` and runs the `is_latest_version` guard **in the same transaction** (AS-9.3 groundwork). The snapshot loader never selects `ssn_itin_encrypted` (FR-13).

### 1c. Domain: TILA rule

- [x] **T110** [P] Test: `MoneyTest`, `AmortizationTest`: half-up to 2 dp, `DECIMAL128`; `P = A·r/(1−(1+r)^−n)`; r = 0 gives `A/n`; baseline 28450.00 / 6.99 / 60 → 563.21; LA-1005 inputs → 566.54.
- [x] **T111** [P] Test: `TilaRuleTest`: `AS_2_1_sumWithinToleranceIsPass`, `AS_2_2_sumMismatchCreatesTilaStip` (expected vs actual in details), `AS_2_3_paymentOffByMoreThanToleranceFails`, `AS_2_3_zeroAprUsesAOverN`, `C_9_finalPaymentChangesExpectedTotal`, tolerance boundary: payment off by exactly `payment-tolerance` passes, by one more cent fails.
- [x] **T112** `funding-domain`: `Money`, `Amortization`, `RulesConfig` (binds `funding.tila.*`; plain record, no Spring), `Check`, `CheckOutcome`, `Snapshot`, `TilaRule` (rule ids `TILA_SUM`, `TILA_PAYMENT`), `RuleCatalog` (with `apis` per rule, empty for TILA), `RuleEngine.evaluate(Snapshot, RulesConfig)`. Rules live under `funding-domain/**/rules/**`.

### 1d. Seed generation & P-2 gates (TILA only)

- [x] **T113** Test: `SeedGatesTest` (runs in `mvn test`): conformance, coverage, lock and docs gates, each with a failing fixture that proves the gate names the scenario, rule, and expected vs actual outcome. Includes `coverageGateFailsForRuleWithoutScenarios`.
- [x] **T114** `funding-seed`: `ScenarioIntent` (Jackson YAML), `SeedGenerator` (resolves `risc`, `totalOfPayments.offset`, `monthlyPayment.offset+rebalance`, `final_payment` through `Amortization`), `SeedGates`, `SeedRegenerateMain`. Build step writes `META-INF/rule-fingerprints.properties` (SHA-256 of each evaluator's source). The lock combines it with the effective config the rule reads.
- [x] **T115** Scenario intents: `test/data/scenarios/_baseline.yaml` and LA-1001, LA-1002, LA-1003, LA-1004, LA-1005, LA-1034. All six land in S1 rather than S2: the coverage gate requires every listed `TILA_PAYMENT` side (0% APR, rounding, final payment) as soon as the rule exists. Then `make seed-regenerate` writes `test/data/rules.lock` and the generated table in `seed-data.md`. The generated numbers must match the baseline table: if they don't, fix the intent or the calculator, never the doc.

### 1e. P-5 groundwork: Disbursement mock

- [x] **T116** [P] `contracts/disbursement.openapi.yaml`: `POST /api/v1/external/disbursements`, `Idempotency-Key` header, `{applicationVersionId, dealershipId, amount}` → 201/200 `{status, reference}`, 409, 422 problem body, 503.
- [x] **T117** Test: `MockGatesTest`: contract gate (each stub's matcher and body validate with `swagger-request-validator`; only `violatesContract: true` fault entries may fail it), catalog coverage (every class, `minPerBand` ≥ `funding.mocks.min-per-band`, every amount), stub metadata `{env}` present, no catch-all stub.
- [x] **T118** `test/data/mocks/disbursement.catalog.yaml` (classes and amounts from `seed-data.md`) and `MockStubGenerator` (scenario `externals:` + catalog, values and delays from `RulesConfig`). `make seed-regenerate` writes `test/data/mocks.lock` and `target/seed-report/mock-impact.md`. The `mocks.lock` stale check lands here; the outcome-flip guard lands in S3 (T311).
- [x] **T119** `MockStubLoader` (`local`): generate → contract gate → `remove-by-metadata {env}` → `/__admin/mappings/import`. `UnmatchedRequestsAssert` test helper (`/__admin/requests/unmatched` must be empty).

### 1f. Trigger path

- [x] **T120** Test: `TriggerProcessorTest` (Lambda module, WireMock as the start API): `AS_1_5_5xxFailsOnlyThatItem`, `AS_1_5_timeoutFailsOnlyThatItem`, `AS_1_6_409IsAcked`, `AS_1_7_401FailsItem`, `malformedBodyIsAckedNotRetried` (400), `AS_1_3_200AlreadyStartedIsAcked`, `recordsAreSentConcurrently`, `remainingTimeCutoffReportsUnsentRecordAsFailure`.
- [x] **T121** `funding-trigger-lambda`: `TriggerProcessor` (HTTP port interface, virtual threads, remaining-time check), `SqsTriggerHandler implements RequestHandler<SQSEvent, SQSBatchResponse>`, `java.net.http` adapter, env config. Shaded jar via `maven-shade-plugin`; no Spring, no Temporal.
- [x] **T122** Test: `StartWorkflowApiTest` (`MockMvc` + Temporal test env): `AS_1_1_startsWorkflow202`, `AS_1_2_duplicateStartReturnsExistingRun`, `AS_1_3_redeliveryReturns200AlreadyStarted`, `AS_1_6_notLatestVersion409`, `AS_1_6_notPendingReview409`, `AS_1_7_missingOrBadApiKey401`, `malformedPayload400`. Workflow ID is `funding-<env>-<versionId>`, `USE_EXISTING` + `ALLOW_DUPLICATE`.
- [x] **T123** `funding-api`: `POST /api/v1/funding-workflows` and `GET …/{applicationVersionId}` (run status + `getState()`), API-key filter, RFC 9457 problem+json, springdoc.
- [x] **T124** `funding-trigger`: `AwsClientConfig` (homehub pattern: endpoint override, per-client CA via `PemTrustManagers.fromPem`, `DISABLE_HOST_PREFIX_INJECTION`), `StartupGuard` (`local` needs a loopback endpoint; `aws` forbids bootstrap and seeding), `EmulatorCalls` (501/503 fail fast, naming service and operation), `InfraBootstrapRunner` (queue `funding-pending-review-<env>` + DLQ, `maxReceiveCount=5`, visibility 60 s), `OutboxRelay` (`@Scheduled` 1 s, `FOR UPDATE SKIP LOCKED`, mark published after SQS accepts, purge published rows older than 7 days), `LocalLambdaRunner` (`local`, always on: long-poll, batch ≤ 10, call the same `SqsTriggerHandler`, delete only items not in `batchItemFailures`).
- [x] **T125** Test: `TriggerPathIT` (Failsafe, emulator from `emulator-env.sh`, `FUNDING_ENV=it-<runId>`): outbox → SQS → runner → handler → API → workflow started within 10 s (`AS_1_1_…`); a duplicate SQS message starts one run (`AS_1_2_…`); API returning 503 sends the message to the DLQ after 5 receives (`AS_1_5_…`). The test deletes its queues afterwards.

### 1g. Workflow (TILA child) & disbursement

- [x] **T126** Test: `FundingWorkflowTest` (`TestWorkflowEnvironment`, mocked activities): `AS_8_1_allPassApprovesAndDisbursesOnce` (idempotency key = workflow ID), `AS_8_1_disburseSkippedWhenVersionNoLongerApproved`, `AS_8_2_failGoesStipulatedAndWaits`, `AS_8_9_amountIsRiscAmountFinancedToDealership`, `childTechnicalFailureBecomesError`, `businessFailIsNotRetried`, `getStateReportsOutcomesAndAttempts`.
- [x] **T127** `funding-workflows`: `FundingWorkflow` (VALIDATE mode, steps 1–3 and 5 of the plan; signal loop stub with `approve`/`reject` for later), `TilaCheckWorkflow` (child ID `<parentId>-TILA-a<attempt>`), activities `loadVersionSnapshot`, `persistResult`, `createStips` (idempotent per FR-07), `applyOutcome` (latest-guarded, one transaction), `disburse` (re-checks latest + APPROVED + no PENDING stip in one transaction, then calls the API), `markFunded`. Task queues `funding-orchestration-<env>`, `funding-validation-<env>`, `funding-external-<env>`. Retry options from `funding.activity.retry.*` (NFR-03). MDC: `loanApplicationId`, `applicationVersionId`, `workflowId` (NFR-05).
- [x] **T128** Test: `ApplyOutcomeIT` + `DisbursementActivityIT` (Testcontainers + in-JVM WireMock with generated stubs): status transitions audited with actor `system:<workflowId>` (FR-10); results row per (version, check, attempt) with one `is_current` (FR-06); disbursement `SUCCESS` and `IDEMPOTENT_REPLAY` write one `disbursements` row; unmatched requests = 0.

### 1h. Seeding & end-to-end

- [x] **T129** Test: `SeedDataLoaderIT`: refuses to seed when a gate fails (message names the scenario and the `make seed-regenerate` hint); idempotent on matching fingerprint; replaces a stale-fingerprint scenario; latest versions are seeded `DRAFT`.
- [x] **T130** `SeedDataLoader` (`local`, `funding.seed-data=true`), `seed_scenarios` table, dev endpoint `POST /api/v1/dev/applications/{id}/submit` (`local` only, FR-16), `make seed-submit-all`.
- [x] **T131** Test: `ScenarioE2EIT` (`make test-functional`; real Temporal, emulator, Postgres, WireMock; `FUNDING_ENV=it-<runId>`, database `funding_it_<runId>` dropped afterwards): parameterized over the generated scenarios enabled in this slice. Submit → assert `expect.status` and `expect.stips`. LA-1001 → FUNDED, LA-1002 → STIPULATED with `STIP_TILA_MATH_DISCREPANCY`. Unmatched WireMock requests = 0.
- [x] **T132** Record a `FundingWorkflow` history for LA-1001 and LA-1002 into `funding-workflows/src/test/resources/histories/` (used by the replay test in S6).


### S1 notes (done 2026-11-17)

What landed differently from the task text, and why:

- **Testcontainers tests run in `make test-unit`.** They need only Docker, not the shared services, so they are `*Test`, not `*IT`: `MigrationTest` and `OutboxTriggerTest` (T106, T107), `RepositoriesTest` (T109), `FundingActivitiesDbTest` (T128, which covers both `ApplyOutcomeIT` and `DisbursementActivityIT`) and `SeedDataLoaderTest` (T129). `*IT` is kept for tests against the developer's emulator, Postgres, WireMock and Temporal (`TriggerPathIT`, `ScenarioE2EIT`).
- **Not-yet-generated scenarios.** The generated table in `seed-data.md` holds only scenarios that have an intent file. The other rows moved to a hand-written "Planned scenarios" table under it, and each slice moves its rows up.
- **TILA payment schedule.** `TILA_PAYMENT` also checks that `total_of_payments` equals the payment schedule (`P × n`, or `P × (n−1) + final_payment` per C-9), with the sum tolerance. This is what LA-1003's `rebalance` and LA-1034 rely on.
- **Config file.** Rule and activity config defaults live in `funding-domain/src/main/resources/funding-rules.properties`, the one file the app (`spring.config.import`) and the seed generator both read (P-2.1). That includes `funding.gap.primary-lender-name` (used by the generator now and by the rule in S2) and the `funding.activity.*` budgets that the mock stubs are derived from (P-5.2). The seed-check hook watches `funding-domain/src/main/**`.
- **Disbursement request.** The disbursement contract carries `loanApplicationId`, so each scenario gets its own stub (matched on `loanApplicationId` plus the `funding-<env>-<n>` idempotency key), and `MockControl` (T313) can swap one scenario's stub.
- **Temporal wiring.** Temporal is wired with the plain SDK (`WorkflowServiceStubs`, `WorkerFactory`), not `temporal-spring-boot-starter`, to keep the env-suffixed task queues and the workflow factories explicit.
- **Replay test.** The replay check from T602 (`FundingWorkflowHistoryTest`) already runs over the T132 histories. S6 widens it.
- **Flyway.** Flyway is pinned to 11.20.3: Boot 3.5.16's 11.7.2 warns that PostgreSQL 18 is untested.
- **Env scripts.** The scripts work when sourced from bash and from zsh (macOS default): no arrays and no reliance on word splitting. Their start-our-own fallback reuses a running managed container and only removes a stopped one.
- **Known gap until S4.** STIPULATED versions keep a workflow open, because expiry (AS-8.6) arrives in S4. Removing the `funding-pgdata` volume restarts SERIAL ids, so a re-seeded version could match an old open run (`alreadyStarted`). Until S4, terminate old `funding-local-*` runs after wiping the volume.

---

## S2 — Document rules

**Proves:** fan-out of independent children; pure rules; config-driven confidence and parse gating.
**Exit:** AS-3.x, AS-4.x, AS-6.x, AS-7.x, AS-8.10 (auto-approval side), AS-9.7; scenarios LA-1006–1011, 1018–1026, 1031, 1033 green.

- [ ] **T201** [P] Test: `PriceParityRuleTest`: `AS_3_1_exactMatchPasses`, `AS_3_2_anyDifferenceFails` (1¢, both values in details).
- [ ] **T202** [P] Test: `VinRuleTest`: `AS_4_1_allMatchAndValidPasses`, `AS_4_2_gapVinDiffersFails` (lists each document's value), `AS_4_2_modelDiffersFails`, `AS_4_1_letterOInVinFails`, `AS_4_1_appVinDiffersFails`, `AS_4_3_makeModelCaseAndSpaceIgnored`, `AS_4_3_vinUpperCasedOnly`, length ≠ 17 fails.
- [ ] **T203** [P] Test: `GapRuleTest`: `AS_6_1_registeredEffectiveFormPasses`, `AS_6_1_expiredFormFails`, `AS_6_1_otherStateFormFails`, `AS_6_2_lenderMismatchFails`, `AS_6_3_bothSubChecksCanFail`, `AS_6_4_noGapIsPassNotApplicable`, effective-date boundaries (first and last effective day).
- [ ] **T204** [P] Test: `ConfidencePolicyTest`, `FieldParsingTest`: `AS_7_1_flaggedRequiredFieldIsNeedsReview` (records the flagged keys), `confidenceAtCutOffIsNotFlagged`, `AS_7_2_unusedFlaggedFieldIgnored`, `FR_14_missingFieldIsNeedsReview`, `FR_14_commaInMoneyIsNeedsReview`, highest-confidence value wins when a key repeats across sections (§8).
- [ ] **T205** Rules + intents **in one change** (P-2): `PriceParityRule`, `VinRule` (`VIN_FORMAT`, `VIN_CROSS_DOC`, `VEHICLE_ATTRS`, `VIN_APP_MATCH`), `GapRule` (`GAP_FORM`, `GAP_LENDER`, `GAP_OPTIONAL`), `ConfidencePolicy` (`CONFIDENCE_FLAG`, `funding.confidence.flag-below`), `RequiredFields` (`REQUIRED_FIELD`), `VinFormat`; their `RuleCatalog` entries; intents LA-1006–1011, 1018–1026, 1031, 1033 (incl. the GAP registry fixture); `make seed-regenerate`; inspect `mock-impact.md` (expect no API impact).
- [ ] **T206** `GapFormRegistry` loaded from `gap_form_registry` (seeded from `test/data/gap-form-registry.yaml`) and passed into the pure rule through the snapshot, so the rule stays I/O-free.
- [ ] **T207** Test: `FundingWorkflowTest` additions: `FR_04_childrenRunInParallel`, `FR_04_oneChildFailingDoesNotAffectOthers`, `AS_8_10_autoApprovalBlockedWhenRiscIncomplete`, `FR_07_sharedStipCodeIsPerCheck` (two checks raising `STIP_EXTRACTION_REVIEW_REQUIRED` produce two stips).
- [ ] **T208** `PriceParityWorkflow`, `VinConsistencyWorkflow`, `GapValidationWorkflow`; parent fan-out with `Async.function` + `Promise.allOf`; RISC-completeness gate in `applyOutcome` (latest RISC present, every required RISC field present, parseable and unflagged, TILA PASS).
- [ ] **T209** Test: `DocumentFreezeIT`: `AS_9_7_insertRefusedWhenApprovedFundedOrRejected` (SQLSTATE `P0001`, `DOCUMENTS_FROZEN`), allowed for DRAFT, PENDING_REVIEW and STIPULATED.
- [ ] **T210** `V2__documents_frozen.sql`: `refuse_frozen_documents` + `trg_documents_frozen`.
- [ ] **T211** `ScenarioE2EIT`: enable PRICE, VIN and GAP in the functional profile; the new scenarios pass. LA-1031 also asserts the multi-page section and per-field bounding boxes are persisted. Record histories for one STIPULATED and one FUNDED run.

---

## S3 — Income + mock catalog

**Proves:** a real external API path with retries and timeouts; P-5 in full.
**Exit:** AS-5.x; LA-1012–1017 and LA-1027 green; `IncomeVerificationMatrixIT` green over about 150 stubs; flip guard proven.

- [ ] **T301** [P] `contracts/income-verification.openapi.yaml`: `POST /api/v1/external/verify-income` `{ssnItinEncrypted, …}` → `{status: VERIFIED|NOT_FOUND, totalVerifiedAnnualIncome, employers[]}`; 400/422 problem body; 429 with `Retry-After`; 503.
- [ ] **T302** [P] Test: `IncomeVarianceRuleTest`: `AS_5_1_withinThresholdPasses`, `AS_5_1_exactly15PercentPasses`, `AS_5_2_15_01PercentFails` (I_s, I_v, variance % in details), `C_3_underStatedIncomeFails`, `AS_5_1_missingStatedIncomeIsNeedsReview`, `AS_5_1_missingTokenIsNeedsReview`, `C_12_notFoundIsNeedsReviewWithProofOfIncomeStip`, `FR_15_ratioNotRoundedBeforeCompare`.
- [ ] **T303** `IncomeVariance` calculator, `IncomeRule` (`INCOME_VARIANCE`, `INCOME_API`, `apis = {INCOME_VERIFICATION}`), `funding.income.variance-threshold`; intents LA-1012–1017 and LA-1027 with `externals:` income intents (`ok-100k`, `low`, `down`, `flaky`); `make seed-regenerate`; inspect `mock-impact.md`.
- [ ] **T304** Test: `IncomeActivityTest` (in-JVM WireMock, generated stubs): `AS_5_3_5xxRetriedWithBackoffThenError`, `AS_5_3_timeoutRetried`, `C_13_429HonorsRetryAfterCappedAtMaxInterval`, `malformedAndSchemaInvalidAreErrorNotRetried`, `clientErrorIsErrorNotRetried`.
- [ ] **T305** `verifyIncome` activity on `funding-external-<env>`: loads the SSN token from the DB by version ID (FR-13), base URL `funding.income.base-url`, start-to-close 15 s, retryable vs non-retryable classification, `Retry-After` handling.
- [ ] **T306** Test: `AS_5_4_ssnNeverInLogsHistoryOrDetails`: captures logs (Logback list appender), the workflow history payloads and `validation_results.details` for an income run and asserts the token value appears in none of them.
- [ ] **T307** `IncomeVerificationWorkflow` child; parent ERROR handling: version stays `PENDING_REVIEW` held (AS-8.4 first half; the funder actions come in S4).
- [ ] **T308** `test/data/mocks/income-verification.catalog.yaml`: bands × magnitudes × shapes, NOT_FOUND, and the nine fault classes from `seed-data.md`; band oracle per entry; generator solves verified income from `v`. `make seed-regenerate` updates `mocks.lock`.
- [ ] **T309** Test: `MockGatesTest` additions: band-oracle conformance (engine output equals the band's expected outcome for every entry), `minPerBand` per band, every magnitude covered.
- [ ] **T310** Test: `MockLockFlipGuardTest`: an outcome flip fails `seed-regenerate`; it passes only with `ACCEPT_OUTCOME_CHANGES=<RULE_ID>` **and** a matching `test/data/RULE-CHANGES.md` entry; a threshold change moves bands without flips.
- [ ] **T311** `MockLockGate` flip guard + `RULE-CHANGES.md` template (date, author, what changed, why the flips are correct).
- [ ] **T312** Test: `IncomeVerificationMatrixIT`: one case per catalog entry through the real HTTP client, Temporal retry policy and rule, asserted against the band; unmatched requests = 0.
- [ ] **T313** `MockControl` test helper: swap a scenario's stub for another **generated** intent, reset, verify calls. It rejects any intent the generator did not produce.
- [ ] **T314** `ScenarioE2EIT`: enable INCOME (all five checks now on); LA-1012–1017 and LA-1027 pass; re-run every earlier scenario with all checks enabled.

---

## S4 — Funder operations

**Proves:** human-in-the-loop flows, signals, the 30-day expiry.
**Exit:** AS-8.3–8.8, AS-8.10, AS-10.1–10.9; every funder-action scenario in `seed-data.md` except the resubmission and document ones (S5).

- [ ] **T401** Test: `StatusMachineTest`: every transition in spec §6 is allowed; every other pair is rejected (FR-09); `FUNDED`, `REJECTED`, `EXPIRED` are terminal.
- [ ] **T402** `VersionStatus` state machine in `funding-domain`, used by `applyOutcome` and every funder write.
- [ ] **T403** Test: `FundingWorkflowSignalsTest`: `AS_8_3_lastStipResolvedContinuesToDisbursement`, `AS_8_4_rerunErroredChecksIncrementsAttempt`, `AS_8_5_rejectCompletesWithoutDisbursement`, `AS_8_7_manualApproveOverridesError`, `AS_8_8_rerunPassAutoSatisfiesStaleStip` (actor `system:<workflowId>`, note `auto-resolved by re-run`), `AS_8_8_rerunFailKeepsExistingStip`, `retryDisbursementAfterExhausted` (C-8), `AS_10_8_manualStipBeforeDisbursementGoesStipulated`.
- [ ] **T404** Test: `FundingWorkflowExpiryTest` (time-skipping): `AS_8_6_expiresAt30DaysInPendingReviewHeld`, `…InStipulated`, `…InApproved`, `AS_8_6_signalsDoNotResetClock`, `AS_8_6_rerunAtDay29StillExpiresAtDay30`, `AS_8_6_inFlightDisbursementFinishesFirst`, audit reason `EXPIRED_AFTER_P30D`, PENDING stips kept.
- [ ] **T405** Workflow signal loop (plan step 4): `stipResolved`, `approve`, `reject`, `rerunChecks`, `retryDisbursement`; `expiresAt = submitted_at + funding.workflow.expire-after` computed once; `expire` activity (guarded by latest + non-terminal, audited); `continueAsNew` with mode `CONTINUE` after 1,000 signals.
- [ ] **T406** Test: `FunderApiTest` (`MockMvc`): `AS_10_1_overview`, `AS_10_2_resolveStipSetsResolvedAt`, `AS_10_2_secondConcurrentResolveIs409AlreadyResolved`, `AS_10_3_reassignWritesAudit`, `AS_10_4_resultsWithExpectedVsActual` (+ `history=true`), `AS_10_5_documentsWithConfidenceAndBoxes`, `AS_10_6_actionsSignalWorkflow`, `AS_10_7_terminalVersionWrites409`, `AS_8_6_expiredVersionWrites409VersionExpired`, `AS_8_7_approveWithPendingStips409`, `AS_8_10_approveRiscIncomplete409`, `AS_8_10_riscStipNotWaivable409`, `AS_10_8_manualStipHasNullCheck`, `AS_10_9_missingFunderHeader400`, problem+json on every error.
- [ ] **T407** Funder API from plan *Funder & Dev API* (overview, versions, stips GET/POST/PATCH, assignee, validation results, documents and fields, approve/reject/rerun/retry-disbursement, audit). Each write: latest + non-terminal check → guarded, audited DB write → signal after commit.
- [ ] **T408** Test: `FunderActionsE2EIT`: the funder-action scenarios from `seed-data.md` (waive, satisfy, partial resolution of LA-1027, LA-1016 switch intent + rerun, manual approve 409 and override, manual stip on LA-1001 with `TIMEOUT_ALWAYS`, LA-1030 retry, expiry with `expire-after=PT20S`, reject LA-1020, terminal-status 409s, RISC stips not waivable, frozen documents on FUNDED and APPROVED). Needs LA-1030's intent (`DLR-FAIL`, disbursement `down`) added in this task with `make seed-regenerate`.
- [ ] **T409** Publish the funder OpenAPI document (springdoc) and check it in as `contracts/funder-api.openapi.yaml` for spec 002 (FR-17). Record histories for a signal-heavy run and an expired run.

---

## S5 — Versioning

**Proves:** resubmission, supersede and document re-runs under concurrency.
**Exit:** US-9, AS-1.8; LA-1028, LA-1029, LA-1032 and the document funder-actions green.

- [ ] **T501** Test: `DocumentAddedTriggerIT`: `AS_1_8_newLatestDocOnPendingReviewOrStipulatedWritesEvent`, no event for DRAFT or for a non-latest insert; `AS_9_4_oldDocLosesLatestFlag`.
- [ ] **T502** `V3__document_added.sql`: `enqueue_document_added` + `trg_document_added`.
- [ ] **T503** Test: `StartWorkflowApiTest` additions: `AS_9_2_v2StartSupersedesRunningV1`, `AS_9_6_supersedeWithNoOpenRunIsNoOp`, `DOCUMENT_VERSION_ADDED_signalsDocumentsUpdated`, `DOCUMENT_VERSION_ADDED_noOpenRun409NoOpenRun`.
- [ ] **T504** Test: `FundingWorkflowVersioningTest`: `AS_9_2_supersedeCancelsChildrenAndKeepsHistory`, `AS_9_5_notLatestCompletesSuperseded`, `AS_1_8_documentsUpdatedReRunsAllChecks`, `AS_9_3_activitiesRefuseWritesForNonLatest` (race: V2 created while V1's checks run).
- [ ] **T505** `supersede` and `documentsUpdated` signals; start API supersede lookup; `SUPERSEDED` completion; dev endpoints `POST /api/v1/dev/applications/{id}/versions` (AS-9.1: one transaction flips latest) and `…/documents`.
- [ ] **T506** Test: `VersionRepositoryIT`: `AS_9_1_newVersionFlipsLatestInOneTransaction`, concurrent creation of two V2s → one wins (unique index).
- [ ] **T507** Intents LA-1028 (historical v1 seeded STIPULATED with its stip), LA-1029 (RISC v1 non-latest), LA-1032 (adds Buyer's Order v2 in the test); `make seed-regenerate`.
- [ ] **T508** `ScenarioE2EIT` + `FunderActionsE2EIT` additions: LA-1028 v2 → FUNDED while v1 stays STIPULATED; LA-1029 reads RISC v2; LA-1032 re-run → FUNDED with the price stip auto-SATISFIED; LA-1002 add RISC v2 → FUNDED; LA-1023 fix by RISC v2; LA-1028 v1 waive → 409 `NOT_LATEST_VERSION`. Record a superseded history.

---

## S6 — Breadth & hardening

**Proves:** scale and change-safety.
**Exit:** NFR-01, NFR-02, NFR-07, NFR-08; `make test-functional-volume` green.

- [ ] **T601** Test: `DisbursementMatrixIT`: one case per disbursement catalog entry (about 50) through the real HTTP client and retry policy: `TIMEOUT_BUT_PROCESSED` and `IDEMPOTENT_REPLAY` leave exactly one row and no double payment; `KEY_CONFLICT`, `DEALER_INVALID`, `MALFORMED` stay APPROVED with an ERROR audit row and are not retried; `EXHAUSTED` stays APPROVED.
- [ ] **T602** `FundingWorkflowReplayTest`: `WorkflowReplayer` over every history in `src/test/resources/histories/` (NFR-07). From here on, any workflow code change is guarded with `Workflow.getVersion` and adds a new recorded history.
- [ ] **T603** Volume seed (`funding.seed.volume=true`, `local` only): one application per catalog entry, `LA-5xxx` income and `LA-6xxx` disbursement, deterministic fixed seed.
- [ ] **T604** `make test-functional-volume`: submits every volume application and asserts status and stips; reports p95 validation time against NFR-01 (< 30 s with healthy APIs).
- [ ] **T605** Test: `WorkerRestartIT` (NFR-02): kill and restart the worker mid-fan-out and while waiting on a signal; the run completes with the same outcome.
- [ ] **T606** Remove `funding.checks.enabled` from the functional profile (all five always on); delete the property if nothing else uses it.
- [ ] **T607** Observability: JSON logs with MDC on every log line in activities and the relay (NFR-05), Temporal SDK metrics through Micrometer. Optional: port homehub `otel-env.sh`.
- [ ] **T608** Emulator re-check runbook: a `make verify-emulator` script that re-runs V-2, V-4 and the RDS probe, so each emulator release can be re-checked (P-3).

---

## Traceability (every acceptance scenario → task)

| Scenario | Task(s) | | Scenario | Task(s) |
|---|---|---|---|---|
| AS-1.1 | T107, T122, T125 | | AS-8.1 | T126, T128, T131 |
| AS-1.2 | T122, T125 | | AS-8.2 | T126, T131 |
| AS-1.3 | T120, T122 | | AS-8.3 | T403, T408 |
| AS-1.4 | T107 | | AS-8.4 | T307, T403, T408 |
| AS-1.5 | T120, T125 | | AS-8.5 | T403, T408 |
| AS-1.6 | T120, T122 | | AS-8.6 | T404, T406, T408 |
| AS-1.7 | T120, T122 | | AS-8.7 | T403, T406, T408 |
| AS-1.8 | T501, T503, T504, T508 | | AS-8.8 | T403, T508 |
| AS-2.1–2.3 | T111, T131 | | AS-8.9 | T126 |
| AS-3.1–3.2 | T201, T211 | | AS-8.10 | T207, T406, T408 |
| AS-4.1–4.3 | T202, T211 | | AS-9.1 | T506 |
| AS-5.1–5.2 | T302, T314 | | AS-9.2 | T503, T504 |
| AS-5.3 | T304, T312 | | AS-9.3 | T504 |
| AS-5.4 | T306 | | AS-9.4 | T501, T508 |
| AS-6.1–6.4 | T203, T211 | | AS-9.5 | T504 |
| AS-7.1–7.2 | T204, T211 | | AS-9.6 | T503 |
| | | | AS-9.7 | T209, T408 |
| | | | AS-10.1–10.9 | T406, T408 |

Seed-data rule catalog coverage: TILA rules → T115; PRICE, VIN, GAP, CONFIDENCE, REQUIRED_FIELD → T205; INCOME → T303.
