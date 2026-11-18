# Seed Data – Spec 001

Seed data gives every business rule in `spec.md` at least one **positive** and one **negative** case, plus both sides of every threshold. Each scenario fails for **exactly one** intended reason, and every other check passes. LA-1027 is the exception: it is a deliberate multi-failure case.

> **Constitution P-2:** test data is *derived from the rules*. The values below are **generated** from scenario intent and the live `funding.*` config, and the build fails if they go stale. Don't edit generated numbers by hand.

## How seed data stays in sync with the rules

```
test/data/scenarios/*.yaml   (intent: which rule, which side of it, expected outcome)
        │  + funding.* config (thresholds, tolerances, lender, GAP registry)
        │  + funding-domain calculators (amortization, variance, VIN format)
        ▼
SeedGenerator ──▶ concrete fixtures (in memory / target/generated-seed/)
        │
        ├─▶ Conformance gate: run every fixture through the real rule engine; outcome must equal `expect`
        ├─▶ Coverage gate: every RuleCatalog rule has PASS + FAIL scenarios, plus both boundary sides
        ├─▶ Lock gate: test/data/rules.lock fingerprints (rule source hash + effective config) are current
        └─▶ Docs gate: the generated table in this file matches
                 │ all pass
                 ▼
SeedDataLoader (local profile) inserts into Postgres.  Any gate failing → seeding refused, scenario named.
```

### Scenario intent file (example)

```yaml
# test/data/scenarios/LA-1014-income-just-over-threshold.yaml
id: LA-1014
title: Income just over the variance threshold
rules: [INCOME_VARIANCE]            # RuleCatalog ids this scenario exercises
side: FAIL_BOUNDARY                 # PASS | FAIL | PASS_BOUNDARY | FAIL_BOUNDARY | NEEDS_REVIEW | ERROR
given:
  income:
    verified: 100000.00             # mock response for this scenario's token
    stated: { relativeToThreshold: "+0.0001" }   # = verified × (1 + threshold + 0.0001)
expect:
  checks: { INCOME: FAIL }          # all others default to PASS
  status: STIPULATED
  stips: [STIP_INCOME_VARIANCE_EXCEEDED]
```

### Intent vocabulary (resolved by `SeedGenerator` against live config)

| Intent | Resolves to |
|---|---|
| `risc: { amountFinanced, apr, term }` | `monthly_payment`, `total_of_payments`, `finance_charge` computed by the domain `Amortization` (with `final_payment`, if given, per C-9) |
| `totalOfPayments: { offset: "+10.00" }` | Breaks only the sum; the payment stays correct |
| `monthlyPayment: { offset: "+5.00", rebalance: true }` | Breaks only the payment; total and finance charge are recomputed so the sum still balances |
| `cashPrice: { beyondTolerance: smallest }` | Buyer's Order = RISC + (`funding.price.tolerance` + 0.01) |
| `stated: { relativeToThreshold: "0" \| "+0.0001" \| "-0.20" }` | verified × (1 + threshold + delta); a negative delta tests under-stated income |
| `confidence: { field, value: belowFlag \| atFlag }` | `funding.confidence.flag-below` − 0.01, or exactly the cut-off |
| `gapForm: expired \| otherState \| valid` | Picked from the GAP registry fixture relative to `contract_date` |
| `lender: primary \| other` | `funding.gap.primary-lender-name`, or a fixed non-matching name |
| `vin: { mutate: lastChar \| letterO \| lowercase }` | Derived from the baseline VIN |

### Rule catalog (coverage gate; every rule needs every listed side)

| Rule id | Sides required | Scenarios |
|---|---|---|
| `TILA_SUM` | PASS, FAIL | LA-1001, LA-1002 |
| `TILA_PAYMENT` | PASS, FAIL, PASS (0% APR), PASS (rounding), PASS (final payment) | LA-1001, LA-1003, LA-1004, LA-1005, LA-1034 |
| `PRICE_PARITY` | PASS, FAIL_BOUNDARY | LA-1001, LA-1006 |
| `VIN_FORMAT` | PASS, FAIL | LA-1001, LA-1010 |
| `VIN_CROSS_DOC` | PASS, FAIL, PASS (normalized) | LA-1001, LA-1007, LA-1009 |
| `VEHICLE_ATTRS` | PASS, FAIL | LA-1001, LA-1008 |
| `VIN_APP_MATCH` | PASS, FAIL | LA-1001, LA-1011 |
| `INCOME_VARIANCE` | PASS, PASS_BOUNDARY, FAIL_BOUNDARY, FAIL (over), FAIL (under) | LA-1001, LA-1013, LA-1014, LA-1012, LA-1015 |
| `INCOME_API` | ERROR, PASS (after retries) | LA-1016, LA-1017 |
| `GAP_FORM` | PASS, FAIL (expired), FAIL (other state) | LA-1001, LA-1018, LA-1019 |
| `GAP_LENDER` | PASS, FAIL | LA-1001, LA-1020 |
| `GAP_OPTIONAL` | PASS (n/a) | LA-1022 |
| `CONFIDENCE_FLAG` | NEEDS_REVIEW, PASS_BOUNDARY, PASS (unused field) | LA-1023, LA-1024, LA-1025 |
| `REQUIRED_FIELD` | NEEDS_REVIEW (missing), NEEDS_REVIEW (unparseable) | LA-1026, LA-1033 |

The catalog is code (`RuleCatalog` enum in `funding-domain`), and this table is generated from it. A new rule must be added to the enum, so the coverage gate can't be bypassed.

### Loading

- **`SeedDataLoader`** runs only under `local` (`funding.seed-data=true`); the `aws` profile forbids it (`StartupGuard`). It runs the four gates first, then inserts. It is idempotent: an application whose ID exists **and** whose stored fingerprint matches is skipped. A stored scenario with a stale fingerprint is deleted and re-seeded, so the local DB never keeps data from old rules.
- **Seeded status:** latest versions are seeded in **`DRAFT`**, so nothing triggers at startup. *Historical* (non-latest) versions, such as LA-1028 v1, are seeded with their final status plus their result and stip rows, and never trigger.
  - `POST /api/v1/dev/applications/{loanApplicationId}/submit` (local only) flips the latest version to `PENDING_REVIEW`. That runs the real path: DB trigger → outbox → SQS → Lambda handler → start API → workflow.
  - `make seed-submit-all` submits every scenario.
- **Tests** use the same generator:
  - Rule unit tests are parameterized over the generated scenarios.
  - Functional tests submit and then assert each scenario's `expect`.
- **Make targets:** `make seed-check` runs all gates without touching the DB. `make seed-regenerate` regenerates, runs the gates, and rewrites `rules.lock` and the generated table in this file **only if every gate passes**.

## Shared baseline (used unless a scenario overrides it)

The intent lives in `test/data/scenarios/_baseline.yaml`. Derived rows (payment, total, finance charge) are computed by the generator, so they are shown here for reading only.

| Item | Value |
|---|---|
| Primary lender (config) | `Summit Auto Finance` |
| Dealer | `Lone Star Motors`, `DLR-100` |
| Vehicle | `4T1C11AK7RU100101`, `2024`, `Toyota`, `Camry` (same on all docs and on `application_versions.vehicle_vin`) |
| RISC: `cash_price` | 31450.00 |
| RISC: `amount_financed` | 28450.00 |
| RISC: `apr` / `term_months` | 6.99 / 60 |
| RISC: `monthly_payment` | 563.21 (amortized: A·r/(1−(1+r)^−n), r = 6.99/1200) |
| RISC: `total_of_payments` | 33792.60 (= 563.21 × 60) |
| RISC: `finance_charge` | 5342.60 (= 33792.60 − 28450.00) |
| RISC: `creditor_name`, `contract_date` | `Summit Auto Finance`, `2026-11-02` |
| Buyer's Order: `total_vehicle_cash_price` | 31450.00 |
| GAP: `form_number`, `state`, `creditor_name` | `GAP-TX-2024-01`, `TX`, `Summit Auto Finance` |
| Stated income / SSN token | 96000.00 / `enc:<scenario id>`, response intent `ok-100k` → verified 100000.00 (4.0% variance) |
| Confidence | 0.97 on every field |
| Documents | RISC 5 pages (Itemization section spans pages 2–3); Buyer's Order 2 pages; GAP 3 pages |

**GAP registry:**

| State | Form | Effective dates |
|---|---|---|
| TX | `GAP-TX-2024-01` | from 2024-01-01, open-ended |
| TX | `GAP-TX-2019-07` | 2019-07-01 to 2023-12-31 (expired) |
| CA | `GAP-CA-2025-03` | from 2025-03-01 |

**Scenario response intents (income API).** Every scenario gets its **own** WireMock stub, matched on `ssnItinEncrypted = enc:<scenario id>` (the mock never sees a real SSN). In the scenario table, the shorthand names `enc:tok-ok`, `enc:tok-low` and so on stand for these intents:

| Shorthand | Intent | Generated response |
|---|---|---|
| `ok` / `100k` | `respond: ok`, verified 100000.00 | 200 |
| `low` | `respond: ok`, verified 90000.00 | 200 |
| `down` | `respond: down` | 503 on every attempt (= `funding.activity.retry.max-attempts`) |
| `flaky` | `respond: { failTimes: maxAttempts − 1, then: ok }` | 503 … then 200. Stays inside the retry budget even if the budget changes. |

Disbursement stubs are matched on the idempotency key `funding-<env>-<versionId>` and `dealershipId`. LA-1030's intent is `respond: down`.

**Runtime control in tests:** the `MockControl` test helper uses WireMock's admin API: it swaps a scenario's stub for another *generated* intent, resets it, and verifies the calls made. The hand-written `/api/v1/dev/mocks/**` endpoints are gone. A test can only switch to an intent the generator produced, never to a hand-typed body.

## Mock API data catalog (WireMock): breadth beyond the scenarios

The single-reason scenarios above prove each rule. The **catalog** adds breadth: many generated responses per API, spread across bands, magnitudes, response shapes and fault classes (constitution P-5). Each catalog entry is checked by an independent oracle: **its band says what the outcome must be.** The rule engine's output is compared against that, so the check isn't the engine checking itself.

### Income Verification (`contracts/income-verification.openapi.yaml`)

Variance `v = (stated − verified) / verified`, with `t = funding.income.variance-threshold` (currently 0.15). For each entry, the generator fixes the stated income and solves for verified income from `v`.

| Band | `v` points (relative to `t`) | Expected |
|---|---|---|
| `EXACT` | 0 | PASS |
| `SMALL_OVER` / `SMALL_UNDER` | ±t/4, ±t/2 | PASS |
| `BOUNDARY_PASS` | t − 0.0001, **t**, −t | PASS |
| `BOUNDARY_FAIL` | t + 0.0001, t + 0.01, −(t + 0.0001) | FAIL |
| `LARGE_OVER` / `LARGE_UNDER` | 2t, 5t / −0.5, −0.9 | FAIL |

| Dimension | Values |
|---|---|
| Stated income magnitudes | 18500.00, 45000.00, 96000.00, 96000.37 (cents and rounding), 250000.00, 1250000.00 |
| Response shapes (from the contract) | single employer; multiple employers (rule uses `totalVerifiedAnnualIncome`); optional fields absent; extra unknown fields (must be ignored) |
| Record not found | `200 { status: NOT_FOUND }` → NEEDS_REVIEW + `STIP_PROOF_OF_INCOME` (C-12) |

| Fault class | Generated stub | Expected |
|---|---|---|
| `RETRY_THEN_OK` | 503 × k then 200, for k = 1 … maxAttempts − 1 | PASS or FAIL per band |
| `EXHAUSTED` | 503 × maxAttempts | ERROR |
| `TIMEOUT_THEN_OK` | First call delayed `start-to-close + 1s`, then 200 | per band |
| `TIMEOUT_ALWAYS` | Every call delayed past the timeout | ERROR |
| `RATE_LIMITED` | 429 with `Retry-After: 2`, then 200 | per band (C-13) |
| `CONNECTION_RESET` | WireMock `CONNECTION_RESET_BY_PEER`, then 200 | per band |
| `MALFORMED` | 200 with invalid JSON (`violatesContract`) | ERROR, not retried |
| `SCHEMA_INVALID` | 200 without `totalVerifiedAnnualIncome` (`violatesContract`) | ERROR, not retried |
| `CLIENT_ERROR` | 400 / 422 with a problem body | ERROR, not retried |

**Size:** 13 variance points × 6 magnitudes = 78 success stubs. Adding 4 shapes over a 12-entry subset, NOT_FOUND × 6, and 9 fault classes × 2 magnitudes (plus k-values) gives **about 150 income stubs**. `minPerBand` = 5 is met for every band.

### Disbursement (`contracts/disbursement.openapi.yaml`)

| Class | Generated stub | Expected |
|---|---|---|
| `SUCCESS` | 201 `{status: COMPLETED, reference}` | FUNDED |
| `IDEMPOTENT_REPLAY` | Second call with the same idempotency key → 200, same `reference` | FUNDED, **one** `disbursements` row |
| `TIMEOUT_BUT_PROCESSED` | First call delayed past the timeout (the server "processed" it), retry → 200 with the same `reference` | FUNDED, one row, no double payment |
| `RETRY_THEN_OK` | 503 × k then 201 | FUNDED |
| `EXHAUSTED` | 503 × maxAttempts | stays APPROVED (C-8) |
| `KEY_CONFLICT` | 409: key reused with a different amount | APPROVED + ERROR audit row, not retried |
| `DEALER_INVALID` | 422 `{code: DEALER_ACCOUNT_INVALID}` | APPROVED, not retried |
| `MALFORMED` | 201 with invalid JSON | APPROVED, ERROR |

Amounts: 9850.00, 24000.00, 28450.00, 31875.50, 89999.99, 150000.00. **About 50 disbursement stubs.**

### How the catalog is used

| Use | What runs |
|---|---|
| **API matrix tests** (`IncomeVerificationMatrixIT`, `DisbursementMatrixIT`) | One parameterized case per catalog entry. Each goes through the **real** activity HTTP client, Temporal retry policy and rule, and is asserted against the band's expected outcome. |
| **Volume seed** (`funding.seed.volume=true`, local only) | One extra application per catalog entry (ids `LA-5xxx` income, `LA-6xxx` disbursement): baseline documents plus that entry's stub. That is about 200 additional realistic applications for exploratory testing and spec 002 (the dashboard). Generation is deterministic (fixed seed), so the same data comes out on every machine. |
| **Volume functional test** (`make test-functional-volume`) | Submits every volume application and asserts the expected status and stips. Kept separate from `make test-functional` because it takes longer. |

### Re-inspection when a rule changes (constitution P-5.4–5.5)

```
edit rule / config ─▶ rule fingerprint changes ─▶ RuleCatalog: rule → APIs
     ─▶ regenerate scenario stubs + catalog stubs for those APIs
     ─▶ gates: contract · conformance (band oracle) · coverage (bands × magnitudes × faults) · unmatched
     ─▶ compare with mocks.lock
           no expected-outcome flips  → write lock + mock-impact.md
           flips                      → FAIL, unless ACCEPT_OUTCOME_CHANGES=<RULE_ID> + a RULE-CHANGES.md entry
```

## Scenarios

<!-- BEGIN GENERATED: SeedGenerator from test/data/scenarios + funding.* defaults. Do not edit by hand; run `make seed-regenerate`. -->

| ID | Scenario | Override from baseline | TILA | PRICE | VIN | INCOME | GAP | Expected status | Expected stips | Spec |
|---|---|---|---|---|---|---|---|---|---|---|
| LA-1001 | Happy path | — | PASS | PASS | PASS | PASS | PASS | FUNDED | — | AS-8.1 |
| LA-1002 | TILA sum broken | `total_of_payments` 33802.60 (finance charge unchanged) | FAIL | P | P | P | P | STIPULATED | TILA_MATH_DISCREPANCY | AS-2.2 |
| LA-1003 | TILA payment wrong | `monthly_payment` 568.21; total 34092.60; FC 5642.60 (sum still balances) | FAIL | P | P | P | P | STIPULATED | TILA_MATH_DISCREPANCY | AS-2.3 |
| LA-1004 | 0% APR promo | AF 24000.00, APR 0, 48 mo, pmt 500.00, total 24000.00, FC 0.00; cash price 27000.00 on both | PASS | P | P | P | P | FUNDED | — | AS-2.3 (r = 0) |
| LA-1005 | Rounding edge | AF 31875.50, 8.49%, 72 mo, pmt 566.54, total 40790.88, FC 8915.38; cash price 34875.50 on both | PASS | P | P | P | P | FUNDED | — | FR-08 |
| LA-1006 | Cash price off by 1¢ | BO `total_vehicle_cash_price` 31450.01 (RISC 31450.00; smallest step beyond the tolerance) | P | FAIL | P | P | P | STIPULATED | PRICE_MISMATCH | AS-3.2 |
| LA-1007 | VIN differs on GAP | GAP `vin` 4T1C11AK7RU100102 (RISC and BO 4T1C11AK7RU100101) | P | P | FAIL | P | P | STIPULATED | VIN_MISMATCH | AS-4.2 |
| LA-1008 | Model differs | BO `vehicle_model` `Camry SE` | P | P | FAIL | P | P | STIPULATED | VIN_MISMATCH | AS-4.2 |
| LA-1009 | Case/space normalization | BO make `toyota `, GAP model ` CAMRY`, RISC VIN `4t1c11ak7ru100101` | P | P | PASS | P | P | FUNDED | — | AS-4.3 |
| LA-1010 | Invalid VIN chars | VIN `4T1C11AK7RU1O0101` (letter O) on all docs and the application | P | P | FAIL | P | P | STIPULATED | VIN_MISMATCH | AS-4.1 |
| LA-1011 | App VIN ≠ docs | `application_versions.vehicle_vin` 4T1C11AK7RU100199; docs at baseline | P | P | FAIL | P | P | STIPULATED | VIN_MISMATCH | AS-4.1 |
| LA-1018 | Expired GAP form | GAP `form_number` `GAP-TX-2019-07` (expired before the contract date) | P | P | P | P | FAIL | STIPULATED | GAP_FORM_MISMATCH | AS-6.1 |
| LA-1019 | Form from wrong state | GAP `state` TX, `form_number` `GAP-CA-2025-03` (registered for another state) | P | P | P | P | FAIL | STIPULATED | GAP_FORM_MISMATCH | AS-6.1 |
| LA-1020 | GAP lender mismatch | GAP `creditor_name` `Coastal Credit Union` | P | P | P | P | FAIL | STIPULATED | LENDER_MISMATCH | AS-6.2 |
| LA-1021 | Both GAP sub-checks fail | expired form `GAP-TX-2019-07` **and** lender `Coastal Credit Union` | P | P | P | P | FAIL | STIPULATED | GAP_FORM_MISMATCH + LENDER_MISMATCH | AS-6.3 |
| LA-1022 | No GAP agreement | no GAP document | P | P | P | P | PASS | FUNDED | — | AS-6.4 |
| LA-1023 | Low-confidence cash price | RISC `cash_price` confidence 0.89 (one step below the cut-off) | P | NEEDS_REVIEW | P | P | P | STIPULATED | EXTRACTION_REVIEW_REQUIRED | AS-7.1 |
| LA-1024 | Confidence at the cut-off is not flagged | RISC `apr` confidence 0.90 = `funding.confidence.flag-below` | PASS | P | P | P | P | FUNDED | — | cut-off boundary (B4) |
| LA-1025 | Unused field flagged | RISC extra field `dealer_notes` confidence 0.89 | P | P | P | P | P | FUNDED | — | AS-7.2 |
| LA-1026 | Missing required field | RISC has no `finance_charge` | NEEDS_REVIEW | P | P | P | P | STIPULATED | EXTRACTION_REVIEW_REQUIRED | AS-7.1 |
| LA-1031 | Multi-page section | Baseline, but the RISC Itemization section spans pages 3–4: `cash_price` (page 3) and `amount_financed` (page 4) carry bounding boxes | P | P | P | P | P | FUNDED | — | US-10.5 |
| LA-1033 | Unparseable money | RISC `cash_price` `31,450.00` (thousands separator) | P | NEEDS_REVIEW | P | P | P | STIPULATED | EXTRACTION_REVIEW_REQUIRED | FR-14 |
| LA-1034 | Final payment differs | `monthly_payment` 563.21, `final_payment` 563.36, total 33792.75, FC 5342.75 | PASS | P | P | P | P | FUNDED | — | C-9 |

`P` means PASS, the baseline outcome for that check. Stip codes omit the `STIP_` prefix.

<!-- END GENERATED -->

### Planned scenarios (not generated yet)

These rows are the **intended** scenarios for later slices (`tasks.md`). They are hand-written targets, not generated data: no test reads them. When a scenario's intent file lands in `test/data/scenarios/`, its row moves out of this table and into the generated one above (`make seed-regenerate`), and the generated values replace these.

| ID | Scenario | Override from baseline | TILA | PRICE | VIN | INCOME | GAP | Expected status | Expected stips | Spec |
|---|---|---|---|---|---|---|---|---|---|---|
| LA-1012 | Income over by 33% | stated 120000.00, `enc:tok-low` (90000) | P | P | P | FAIL | P | STIPULATED | INCOME_VARIANCE | AS-5.2 |
| LA-1013 | Income exactly 15.00% | stated 115000.00, `enc:tok-100k` | P | P | P | PASS | P | FUNDED | — | AS-5.1 boundary |
| LA-1014 | Income 15.01% | stated 115010.00, `enc:tok-100k` | P | P | P | FAIL | P | STIPULATED | INCOME_VARIANCE | AS-5.2 boundary |
| LA-1015 | Under-stated income | stated 80000.00, `enc:tok-100k` (−20%, two-sided per C-3) | P | P | P | FAIL | P | STIPULATED | INCOME_VARIANCE | C-3 |
| LA-1016 | Income API down | `enc:tok-down` | P | P | P | ERROR | P | PENDING_REVIEW (held) | — | AS-5.3, AS-8.4 |
| LA-1017 | Income API flaky | `enc:tok-flaky` | P | P | P | PASS | P | FUNDED | — | NFR-03 |
| LA-1027 | Multi-failure | TILA sum +$10, GAP VIN …100102, stated 120000 with `enc:tok-low` | FAIL | P | FAIL | FAIL | P | STIPULATED | TILA + VIN + INCOME (3 stips) | FR-04 |
| LA-1028 | Resubmission fixes the deal | **v1** = LA-1006 data (seeded STIPULATED, stip PENDING); **v2** = baseline (DRAFT, latest) | v2: all PASS | | | | | v1 stays STIPULATED; **v2 FUNDED** | v1's stip kept as history | AS-9.1–9.3 |
| LA-1029 | New document version | One app version. RISC **v1** (`is_latest_doc=false`) has `total_of_payments` +$10; RISC **v2** (latest) at baseline | PASS (reads v2) | P | P | P | P | FUNDED | — | AS-9.4 |
| LA-1030 | Disbursement fails | Baseline; dealer `DLR-FAIL` (mock disbursement always returns 500) | P | P | P | P | P | **APPROVED** (held) | — | C-8 |
| LA-1032 | Doc fix triggers re-run | Starts as LA-1006 (STIPULATED). The test then adds **Buyer's Order v2** with 31450.00 through `POST /api/v1/dev/applications/{id}/documents` | re-run: all PASS | | | | | **FUNDED** | PRICE stip auto-SATISFIED by `system:` (AS-8.8) | AS-1.8, AS-8.8 |


## Funder-action scenarios (run against seeded results, used by functional tests)

| Starting point | Action | Expected |
|---|---|---|
| LA-1006 STIPULATED | Waive `STIP_PRICE_MISMATCH` with a reason | Stip WAIVED, `resolved_at` set → workflow continues → FUNDED; audit row written |
| LA-1012 STIPULATED | Mark the income stip SATISFIED | → FUNDED |
| LA-1027 STIPULATED | Resolve 2 of 3 stips | Stays STIPULATED until the 3rd is resolved |
| LA-1016 held (ERROR) | `MockControl.switchIntent(LA-1016, income, ok-100k)`, then `rerunChecks({INCOME})` | INCOME PASS (attempt 2) → FUNDED |
| LA-1006 STIPULATED | Manual approve while the price stip is PENDING | 409 `PENDING_STIPS` (AS-8.7) |
| LA-1016 held (ERROR) | Manual approve (no PENDING stips) | APPROVED → FUNDED (AS-8.7 override) |
| LA-1001 before disbursement (`MockControl.switchIntent(…, disbursement, TIMEOUT_ALWAYS)`) | Add manual `STIP_PROOF_OF_INCOME` | STIPULATED (AS-10.8) |
| LA-1030 APPROVED (held) | `MockControl.switchIntent(LA-1030, disbursement, SUCCESS)`, then `retryDisbursement` | FUNDED |
| LA-1012 STIPULATED, `expire-after` elapsed (functional env sets `funding.workflow.expire-after=PT20S`; workflow test uses time-skipping at P30D) | — | **EXPIRED**; stip stays PENDING as history; audit `EXPIRED_AFTER_…` (AS-8.6) |
| LA-1012 EXPIRED | Satisfy the stip | 409 `VERSION_EXPIRED` |
| LA-1012 EXPIRED | Add a Buyer's Order v2 | Refused: `DOCUMENTS_FROZEN` |
| LA-1030 APPROVED (disbursement down), `expire-after` elapsed | — | **EXPIRED**, no disbursement row COMPLETED |
| LA-1016 held (ERROR): re-run at day 29, expiry at day 30 | `rerunChecks` | The clock is not reset, so it still expires on day 30 if not FUNDED |
| LA-1020 STIPULATED | Reject | REJECTED; no disbursement row |
| LA-1001 FUNDED | Any stip or approve write | 409 (terminal status) |
| LA-1026 STIPULATED (RISC `finance_charge` missing) | Waive the RISC extraction stip | 409 `RISC_STIP_NOT_WAIVABLE` (AS-8.10) |
| LA-1002 STIPULATED (TILA) | Waive the TILA stip | 409 `RISC_STIP_NOT_WAIVABLE` |
| LA-1002 STIPULATED | Add RISC v2 at baseline values | Re-run → TILA PASS, stip auto-SATISFIED → FUNDED |
| LA-1023 STIPULATED (RISC `cash_price` confidence 0.82) | Waive | 409 `RISC_STIP_NOT_WAIVABLE`; fix by adding RISC v2 |
| LA-1001 FUNDED | Add RISC v2 (dev endpoint) | Refused: `DOCUMENTS_FROZEN` (AS-9.7) |
| LA-1030 APPROVED (held) | Add a Buyer's Order v2 | Refused: `DOCUMENTS_FROZEN` |
| LA-1028 v1 | Waive the v1 stip | 409 `NOT_LATEST_VERSION` (AS-10.7) |
| any | Reassign the funder | `assigned_funder` updated, audit row written |
