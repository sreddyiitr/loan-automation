# Feature Spec 001: Auto Loan Funding Validation (Backend)

| | |
|---|---|
| **Status** | Clarified: all §9 items answered by the owner (2026-11-17). `tasks.md` generated. |
| **Source** | `auto_funding_tasks.md` |
| **Scope** | Backend only: data model, event trigger, Temporal orchestration, five validation domains, stipulations, mock external APIs, funder REST API |
| **Out of scope** | ADK A2UI funder dashboard (future spec 002), OCR/extraction itself, real payroll/disbursement integrations, authN/authZ (a header stands in for the actor), AWS deployment/IaC (only the constraints it must respect are recorded in `plan.md`) |

---

## 1. Problem & Goal

Funders manually cross-check dealer funding packages (RISC, Buyer's Order, GAP Agreement): math, price parity, VIN consistency, income, and GAP compliance. This is slow and error-prone, and dealers resubmit deals repeatedly.

**Goal:** when an application version is ready for review, automatically run independent, deterministic validations. Each failure becomes a stipulation (stip). When every check passes and every stip is resolved, the application moves to disbursement. Funders get an API that shows why a deal is blocked.

## 2. Actors

| Actor | Role |
|---|---|
| **Enterprise OCR (external)** | Writes documents, sections and extracted fields with confidence scores, then marks the version `PENDING_REVIEW`. Simulated in this project by a seed/ingestion endpoint. |
| **Trigger Lambda** | Reads the funding SQS queue and calls the start-workflow API for each message. |
| **Funding system (this app)** | Detects `PENDING_REVIEW`, orchestrates validations, writes results and stips, triggers disbursement. |
| **Funder (human)** | Reviews results, waives or satisfies stips, reassigns ownership, approves or rejects. |
| **Income Verification API** (WireMock locally) | `POST /api/v1/external/verify-income` returns verified annual income. Contract: `contracts/income-verification.openapi.yaml`. |
| **Disbursement API** (WireMock locally) | Releases funds to the dealer for an approved version. Contract: `contracts/disbursement.openapi.yaml`. |

## 3. Domain Concepts

- **Loan Application**: a persistent ID that survives resubmissions.
- **Application Version**: one immutable snapshot of deal terms (v1, v2, …). Only one version per application is *latest*.
- **Document Version**: a RISC, Buyer's Order or GAP Agreement attached to an application version. Only one *latest* document per type per application version.
- **Section / Extracted Field**: a multi-page section and its fields, each with a confidence score from 0.00 to 1.00. A field is **flagged** when its confidence is below the configured cut-off `funding.confidence.flag-below` (default 0.90). Flagging is computed by the app from config, not stored.
- **Validation Result**: the outcome of one check against one application version: `PASS`, `FAIL`, `NEEDS_REVIEW` or `ERROR`, with expected-vs-actual details.
- **Stipulation**: a condition that must be `SATISFIED` or `WAIVED` before funding.

## 4. User Stories & Acceptance Scenarios

### US-1 Automatic validation trigger (P1)
*As the funding system, when a version enters `PENDING_REVIEW`, I start validation exactly once for that version.*

- **AS-1.1** Given version V is set to `PENDING_REVIEW`, then a message `{loan_application_id, application_version_id}` is published to the funding queue, and a validation workflow for V starts within 10 s.
- **AS-1.2** Given the same message is delivered twice, then only one workflow runs for V (idempotent on `application_version_id`).
- **AS-1.3** Given the Lambda fails or times out after it calls the API but before SQS deletes the message, then the message is redelivered and the API returns `200 alreadyStarted` (idempotent).
- **AS-1.4** Given a version transitions to any other status, then no message is published.
- **AS-1.5** Given the start-workflow API returns 5xx or times out, then that message alone is reported as a batch item failure and is retried. After 5 receives it moves to the DLQ. Other messages in the batch are acknowledged.
- **AS-1.6** Given a message for a version that is no longer latest or no longer `PENDING_REVIEW`, then the API returns 409 and the Lambda acknowledges the message without starting a workflow.
- **AS-1.7** Given a request without a valid `X-Trigger-Api-Key`, then the API returns 401 and does not start a workflow.
- **AS-1.8** Given a new latest document version is inserted for an application version that is `PENDING_REVIEW` or `STIPULATED`, then an event is published on the same path, and **all** checks re-run against the latest documents (§9 C-10).

### US-2 TILA math verification (P1)
- **AS-2.1** Given RISC `amount_financed + finance_charge = total_of_payments` (within tolerance T_sum, §9 C-2), then the result is PASS.
- **AS-2.2** Given the sum differs by more than T_sum, then the result is FAIL and stip `STIP_TILA_MATH_DISCREPANCY` is created with expected and actual values.
- **AS-2.3** Given the amortized payment `P = A·r / (1 − (1+r)^−n)` (A = amount financed, r = APR/12, n = term in months; r = 0 means `P = A/n`) differs from the RISC `monthly_payment` by more than T_pmt, then the result is FAIL with the same stip code.

### US-3 Cash price parity (P1)
- **AS-3.1** Given RISC `cash_price` equals Buyer's Order `total_vehicle_cash_price` exactly ($0.00 tolerance), then the result is PASS.
- **AS-3.2** Given they differ by any amount, then the result is FAIL and `STIP_PRICE_MISMATCH` is created with both values.

### US-4 VIN & vehicle consistency (P1)
- **AS-4.1** Given `vin`, `vehicle_year`, `vehicle_make` and `vehicle_model` are identical across RISC, Buyer's Order and GAP Agreement, the VIN equals `application_versions.vehicle_vin`, and the VIN is 17 valid characters (no I, O or Q), then the result is PASS.
- **AS-4.2** Given any mismatch or an invalid VIN, then the result is FAIL and `STIP_VIN_MISMATCH` is created, listing the value from each document.
- **AS-4.3** Comparison is case-insensitive and ignores surrounding whitespace for make and model. VIN is compared after upper-casing. No other normalization is applied.

### US-5 External income verification (P1)
- **AS-5.1** Given the income API returns verified income I_v and stated income I_s satisfies `|I_s − I_v| / I_v ≤ t` (t = `funding.income.variance-threshold`, default 0.15), then the result is PASS. If stated income or the SSN/ITIN token is missing, the result is NEEDS_REVIEW (FR-14).
- **AS-5.2** Given the variance exceeds t, then the result is FAIL and `STIP_INCOME_VARIANCE_EXCEEDED` is created with I_s, I_v and the variance percentage.
- **AS-5.3** Given the API times out or returns 5xx, then the call is retried with exponential backoff. If it is still failing after the retry budget, the result is ERROR (no stip) and the version is held for a funder.
- **AS-5.4** The encrypted SSN/ITIN is sent as-is. It never appears in logs, workflow history payloads in clear form, or validation result details.

### US-6 GAP form & lender validation (P1)
- **AS-6.1** Given the GAP `form_number` exists in the state registry for the GAP `state` and is effective on the contract date, then the form sub-check passes. Otherwise `STIP_GAP_FORM_MISMATCH` is created.
- **AS-6.2** Given the GAP `creditor_name` and RISC `creditor_name` both equal the configured primary lender name (case-insensitive, whitespace-trimmed), then the lender sub-check passes. Otherwise `STIP_LENDER_MISMATCH` is created.
- **AS-6.3** Both sub-checks run independently, so one version can receive both stips.
- **AS-6.4** Given the version has no GAP Agreement, then the check is `PASS` with the detail `not_applicable`. GAP is optional.

### US-7 Extraction confidence gating (P1)
- **AS-7.1** Given a field required by a check is missing or flagged (confidence below `funding.confidence.flag-below`, default 0.90), then that check's result is `NEEDS_REVIEW` (not PASS or FAIL). The flagged field keys are recorded, and stip `STIP_EXTRACTION_REVIEW_REQUIRED` is created (see §9 C-4).
- **AS-7.2** Fields that the check does not use are not considered.

### US-8 Outcome & disbursement (P1)
- **AS-8.1** Given all checks PASS, then the version becomes `APPROVED`, the disbursement API is called once (idempotency key = the workflow ID `funding-<env>-<application_version_id>`), and on success the version becomes `FUNDED`. Right before calling, the disbursement activity re-checks in the DB that the version is still latest, still `APPROVED`, and has no PENDING stip. If any of these is false, it doesn't call.
- **AS-8.2** Given any check is FAIL or NEEDS_REVIEW, then the version becomes `STIPULATED` and the workflow waits for funder action.
- **AS-8.3** Given a STIPULATED version whose stips all become SATISFIED or WAIVED, and no check is in ERROR, then the workflow continues with AS-8.1.
- **AS-8.4** Given any check is ERROR, then the version stays `PENDING_REVIEW` and the workflow waits. A funder can request a re-run of the errored checks or approve manually.
- **AS-8.5** Given a funder rejects the version, then it becomes `REJECTED` and the workflow completes without disbursement.
- **AS-8.6 Expiry** (owner decision C-11). A version that hasn't reached `FUNDED` or `REJECTED` within `funding.workflow.expire-after` (default **30 days**) becomes **`EXPIRED`**, which is terminal.
  - The clock starts when the version **first enters `PENDING_REVIEW`** (`submitted_at`). Funder actions, re-runs and document updates don't reset it.
  - Expiry applies to `PENDING_REVIEW` (including ERROR holds), `STIPULATED` and `APPROVED` (for example, waiting on a failed disbursement).
  - An in-flight disbursement call is allowed to finish first: if it succeeds the version is `FUNDED`, otherwise it then expires.
  - On expiry, PENDING stips stay as history, an audit row is written with actor `system:<workflowId>` and reason `EXPIRED_AFTER_P30D`, and the workflow completes.
  - Funder writes on an expired version return 409 `VERSION_EXPIRED`. To continue, the dealer resubmits as a **new application version** (US-9).
- **AS-8.7** A manual approve is allowed only when no stip is PENDING. It is the override for checks in ERROR. With PENDING stips it returns 409 `PENDING_STIPS`.
- **AS-8.8** Given a re-run check now PASSes, then its PENDING stips from earlier runs are set to SATISFIED by actor `system:<workflowId>` with note `auto-resolved by re-run`. Given it FAILs again, the existing PENDING stip is kept (FR-07), not duplicated.
- **AS-8.9** The disbursement amount is the RISC `amount_financed`, paid to `dealership_id`.
- **AS-8.10 RISC must be complete before approval** (owner decision B5). A version can become `APPROVED`, automatically or by manual approve, only if its latest RISC exists, has every required RISC field (§8) present and parseable, has none flagged, and its TILA check is PASS.
  - Otherwise auto-approval doesn't happen, and manual approve returns 409 `RISC_INCOMPLETE`. When every check passes and no stip is PENDING but the RISC is incomplete, the version is held in `PENDING_REVIEW` for a funder, with an audit row whose reason is `RISC_INCOMPLETE` and lists the fields (clarified 2026-11-17, slice S2; awaiting owner confirmation).
  - A stip raised against RISC fields (`STIP_EXTRACTION_REVIEW_REQUIRED` from a RISC field, `STIP_TILA_MATH_DISCREPANCY`) **cannot be WAIVED**; the API returns 409 `RISC_STIP_NOT_WAIVABLE`. It is resolved only when a corrected RISC version re-runs and passes (AS-8.8).

### US-9 Resubmission (P1)
- **AS-9.1** Given a new version V2 is created for an application, then in one transaction V1 gets `is_latest_version = FALSE` and V2 becomes latest.
- **AS-9.2** Given V1's workflow is still running when V2 enters `PENDING_REVIEW`, then V1's workflow is cancelled, its partial results and stips are kept as history, and V1's status is not changed further.
- **AS-9.3** Activities must not write results, stips or status for a version that is no longer latest. They check this inside the same DB transaction as the write.
- **AS-9.4** A new document version within the same application version (for example RISC v2) works the same way: the old doc gets `is_latest_doc = FALSE`. Checks only read latest docs, and every re-run reloads them.
- **AS-9.5** Given a workflow finds its version no longer latest (for example, V2 was created while V1's checks were running), then it stops writing and completes with result `SUPERSEDED`.
- **AS-9.6** Superseding a version that has no running workflow (never submitted, already completed, or seeded as history) is a no-op, not an error.
- **AS-9.7 Documents freeze at approval** (B5). Once a version is `APPROVED`, `FUNDED` or `REJECTED`, inserting a new document version for it is refused at the DB level with SQLSTATE `P0001` `DOCUMENTS_FROZEN`. Any change after approval, a RISC change included, needs a **new application version** (US-9). Only `DRAFT`, `PENDING_REVIEW` and `STIPULATED` versions accept new documents (re-run per AS-1.8).

### US-10 Funder operations API (P2)
- **AS-10.1** Get application overview: application ID, latest version number, dealer, borrower and co-borrower, stated income, VIN, status, assigned funder.
- **AS-10.2** List stips for a version. Update a stip to SATISFIED or WAIVED, with a required reason and actor. `resolved_at` is set. The update is conditional (`WHERE status = 'PENDING'`), so when two funders act at once the second gets 409 `ALREADY_RESOLVED`.
- **AS-10.3** Reassign the funder. Every change is recorded in the audit log.
- **AS-10.4** List validation results with expected-vs-actual details (backs the mismatches tab).
- **AS-10.5** List document versions per type with `processed_at` and per-field confidence and bounding box.
- **AS-10.6** Approve, reject, or re-run errored checks. These become Temporal signals to the version's workflow.
- **AS-10.7** Writes against a non-latest version, or a version in a terminal status (`FUNDED`, `REJECTED`, `EXPIRED`), are refused with 409.
- **AS-10.8** A funder can create a manual stip (for example `STIP_PROOF_OF_INCOME`; the owner confirmed this is done from the UI in spec 002, through this API) with `validation_check = NULL`. This moves an `APPROVED`-but-not-yet-disbursed or `PENDING_REVIEW` version to `STIPULATED`.
- **AS-10.9** Every funder API call is made by an authenticated user: it carries an OIDC bearer token, and the actor is the token's `sub` (spec 002 FR-09, FR-11). A missing or invalid token returns 401. This replaces the `X-Funder-Id` header, which was a stand-in until auth existed. Errors use RFC 9457 `application/problem+json`.

## 5. Functional Requirements

| ID | Requirement |
|---|---|
| FR-01 | Persist the data model in `data-model.md` (source schema plus the additions marked there). |
| FR-02 | A status transition to `PENDING_REVIEW` publishes exactly one queue message per transition (transactional outbox). |
| FR-03 | An SQS-triggered Lambda calls `POST /api/v1/funding-workflows`. The API, hosted in the Temporal worker app, starts one parent workflow per version with workflow ID `funding-<env>-<application_version_id>`. A duplicate start while a run is open returns that run. |
| FR-04 | The parent workflow fans out five independent child workflows (TILA, PRICE, VIN, INCOME, GAP) that run in parallel. One child failing does not affect the others. |
| FR-05 | Each check is deterministic: the same inputs always give the same result. All I/O happens in activities. |
| FR-06 | Each check run persists a `validation_results` row per (version, check, attempt). Exactly one row per (version, check) is `is_current`, and earlier attempts are kept as history. |
| FR-07 | Stip creation is idempotent: there is at most one PENDING stip per (version, stip_code, validation_check). Each check owns its own stips, so a shared code such as `STIP_EXTRACTION_REVIEW_REQUIRED` raised by two checks is two stips, and one check passing on re-run can't resolve the other's. |
| FR-08 | All monetary math uses exact decimal arithmetic. Money is rounded half-up to 2 decimal places. |
| FR-09 | The application status machine is enforced (§6). Illegal transitions are rejected. |
| FR-10 | Every status change, stip change and assignment change writes an audit row (actor, before, after, timestamp). |
| FR-11 | External APIs are mocked **at the HTTP boundary with WireMock**. Every stub is generated from scenario intent plus the per-API mock data catalog, and validated against the API's OpenAPI contract (constitution P-5). The app calls each API only through a configurable base URL (`funding.<api>.base-url`). |
| FR-12 | Every tolerance, threshold, timeout and the primary lender name can be configured. |
| FR-13 | Encrypted SSN/ITIN is excluded from logs and workflow payloads. Activities load it from the DB by version ID. |
| FR-14 | A required field value that can't be parsed (money, APR, term, date, year), or a missing required application input (stated income, SSN/ITIN token), is treated like a missing field, so the result is NEEDS_REVIEW. Money must match `^-?\d+(\.\d{1,2})?$`. |
| FR-15 | Ratios such as income variance are compared with exact decimals and are **not rounded** before comparison, so 15.00% passes and 15.01% fails. Values are rounded only for display (2 dp). |
| FR-16 | Dev-only endpoints (`/api/v1/dev/**`: submit, add version or document) exist only under the `local` profile. Mock control goes through WireMock's admin API, restricted to generated intents. |
| FR-17 | The funder API is documented with OpenAPI (springdoc), because spec 002 (the dashboard) builds on it. |
| FR-18 | When a rule changes, every mock response of the APIs it consumes is regenerated and re-verified. An expected-outcome flip needs explicit acceptance plus a `RULE-CHANGES.md` entry (constitution P-5). |

## 6. Application Version Status Machine

```
DRAFT ──OCR done──▶ PENDING_REVIEW ──all PASS / manual approve (no PENDING stips)──▶ APPROVED ──disbursed──▶ FUNDED
                        │   ▲                                                         │  ▲
     any FAIL /         │   │ (ERROR: stays PENDING_REVIEW, held)                     │  │ manual stip
     NEEDS_REVIEW       ▼   │                                                         ▼  │
                     STIPULATED ──all stips SATISFIED/WAIVED──────────────────────▶ APPROVED
                        ▲   │
     doc update/re-run ─┘   └──────────────── funder reject (from PENDING_REVIEW, STIPULATED or APPROVED) ──▶ REJECTED

     PENDING_REVIEW / STIPULATED / APPROVED ── 30 days after first submission, not FUNDED ──▶ EXPIRED
```

| From | To | Trigger |
|---|---|---|
| DRAFT | PENDING_REVIEW | OCR or dev `submit` (starts the trigger path) |
| PENDING_REVIEW | APPROVED / STIPULATED | Check outcomes (AS-8.1, AS-8.2), or manual approve (AS-8.7) |
| STIPULATED | APPROVED | Last PENDING stip resolved and no check in ERROR (AS-8.3) |
| STIPULATED | PENDING_REVIEW | Re-run started (doc update or funder re-run). It then settles again from the new outcomes. |
| APPROVED | STIPULATED | Manual stip added before disbursement (AS-10.8) |
| APPROVED | FUNDED | Disbursement succeeded |
| PENDING_REVIEW, STIPULATED, APPROVED | REJECTED | Funder reject |
| PENDING_REVIEW, STIPULATED, APPROVED | EXPIRED | `funding.workflow.expire-after` (P30D) elapsed since `submitted_at` (AS-8.6) |

`FUNDED`, `REJECTED` and `EXPIRED` are terminal. Any transition not listed is rejected (FR-09). `FUNDED`, `DRAFT` and `EXPIRED` are new here; the source schema didn't have them, though the UI section lists `FUNDED`. A superseded (non-latest) version keeps its last status forever.

## 7. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR-01 | Total validation for one version takes under 30 s at p95 when external APIs are healthy. |
| NFR-02 | Workflow results survive worker restarts at any point. |
| NFR-03 | Activity retries: initial interval 1 s, backoff ×2, maximum 1 min, 5 attempts by default. Business-rule failures are not retried. |
| NFR-04 | Runs fully locally: Temporal at `localhost:7233`, SQS in AWS Emulator, Postgres 18 in Docker (see `plan.md`). |
| NFR-05 | Logs are structured and correlated by `loan_application_id`, `application_version_id` and workflow ID. |
| NFR-06 | Seed data covers a positive and a negative case for every rule, plus both sides of each threshold. It is **generated from scenario intent using the live rules and config**, and enforced by the conformance, coverage, lock and docs gates (constitution P-2, `seed-data.md`). Stale seed data fails the build and blocks seeding. |
| NFR-07 | Workflows that wait up to 30 days must survive code deployments: changes to workflow code are versioned (`Workflow.getVersion`), and a replay test runs against recorded histories. |
| NFR-08 | Functional tests can share the developer's emulator, database and Temporal without colliding with the developer's own data (isolation per environment name; see `plan.md`). |

## 8. Required Extracted Field Keys (contract with OCR)

| Document | Field keys |
|---|---|
| RISC | `cash_price`, `amount_financed`, `finance_charge`, `total_of_payments`, `apr`, `term_months`, `monthly_payment`, `vin`, `vehicle_year`, `vehicle_make`, `vehicle_model`, `creditor_name`, `contract_date`; optional `final_payment` (C-9) |
| BUYERS_ORDER | `total_vehicle_cash_price`, `vin`, `vehicle_year`, `vehicle_make`, `vehicle_model` |
| GAP_AGREEMENT | `form_number`, `state`, `creditor_name`, `vin`, `vehicle_year`, `vehicle_make`, `vehicle_model` |

`apr` is a percent value (for example `6.99`). Money values are decimal strings without currency symbols. If one key appears in more than one section of a document, the value with the highest confidence wins.

## 9. Clarifications (all answered by the owner, 2026-11-17)

| # | Question | Answer |
|---|---|---|
| C-1 | The source says the *parent workflow polls SQS*. | **Resolved:** a Lambda consumes SQS and calls the start-workflow API. |
| C-2 | What are the TILA tolerances? | **Confirmed:** T_sum = $0.00 (exact), T_pmt = $0.01. |
| C-3 | Is the income variance two-sided, or does only over-stated income count? | **Confirmed:** two-sided, relative to verified income. |
| C-4 | What happens to low-confidence fields? The source only flags them. | **Confirmed:** NEEDS_REVIEW plus new stip `STIP_EXTRACTION_REVIEW_REQUIRED`. |
| C-5 | The source lists `STIP_PROOF_OF_INCOME` in examples, but no rule produces it. | **Confirmed:** funders create it manually **in the UI** (spec 002), backed by the manual-stip API (AS-10.8). |
| C-6 | Is the GAP Agreement mandatory? | **Confirmed:** optional (AS-6.4). |
| C-7 | How is `assigned_funder` set initially? | **Confirmed:** left null and assigned manually. Auto-assignment is out of scope. |
| C-8 | What happens after disbursement fails permanently? | **Confirmed:** the version stays `APPROVED` and waits for a funder retry, until expiry (AS-8.6). |
| C-9 | TILA: RISCs often have a different final payment, so `total_of_payments ≠ monthly_payment × n`. How should that be checked? | **Confirmed:** equal payments, unless `final_payment` is present, in which case total = P × (n−1) + final_payment. |
| C-10 | A new document version arrives while a version is under review or STIPULATED. Should validation re-run automatically? | **Confirmed:** re-run all checks (AS-1.8). After approval, documents are frozen (AS-9.7, B5). |
| C-11 | What happens after the 30-day hold? | **Owner: the application expires after 30 days.** New terminal status `EXPIRED`. The clock runs **from first submission and is never reset** (owner confirmed 2026-11-17), and actions afterwards return 409; continuing needs a new version (AS-8.6). |
| C-12 | The income API finds no payroll record (`NOT_FOUND`). What then? | **Confirmed:** NEEDS_REVIEW + `STIP_PROOF_OF_INCOME`. |
| C-13 | The income API returns 429. How is it handled? | **Confirmed:** retryable, honoring `Retry-After` (capped at the activity's retry maximum interval). |
| C-14 | Which other external APIs do the business rules call? | **Resolved (owner, 2026-11-17):** only Income Verification and Disbursement for now. The GAP state registry stays a local table (`gap_form_registry`). A future API needs a contract, a catalog and a `RuleCatalog` dependency (P-5). |

### Clarifications from slice S4 (confirmed by the owner, 2026-11-18)

| # | Question | Answer (implemented) |
|---|---|---|
| C-15 | AS-8.10 says a RISC-bound stip can't be WAIVED and "is resolved only when a corrected RISC version re-runs and passes". Can a funder mark it SATISFIED instead? | **No.** SATISFIED and WAIVED are both refused with 409 `RISC_STIP_NOT_WAIVABLE`; only a passing re-run on a corrected RISC resolves it (AS-8.8). |
| C-16 | AS-10.8 lets a manual stip move an APPROVED version to STIPULATED, and AS-8.5 allows a reject from APPROVED. What if the disbursement call is already in flight? | Once the disbursement activity has re-checked the version and decided to pay (AS-8.1), nothing can stop the call. While the workflow reports `DISBURSING`, a manual stip or reject returns 409 `DISBURSEMENT_IN_PROGRESS` (try again after it returns). After a failed disbursement (C-8) both are allowed. |
| C-17 | After a failed disbursement (C-8), does any later wake-up retry it? | **No:** only a funder's retry (`POST …/retry-disbursement`, 409 `NOTHING_TO_RETRY` when nothing failed). If a manual stip moves the version out of APPROVED, its next approval gets a fresh attempt. |
| C-18 | Which statuses accept which funder actions? | Approve and re-run: from `PENDING_REVIEW` or `STIPULATED` (`ALREADY_APPROVED`, `NOT_RERUNNABLE`); re-run defaults to the checks in ERROR (`NOTHING_TO_RERUN` when none). Manual stip and reject: any submitted, non-terminal status (`NOT_SUBMITTED` for DRAFT). Writes on a non-latest version: 409 `NOT_LATEST_VERSION`; on `FUNDED`/`REJECTED`: `VERSION_TERMINAL`; on `EXPIRED`: `VERSION_EXPIRED`. |
| C-19 | A version is rejected (or expires) while its checks are still running. Are their results recorded? | **No:** a check finishing after the version closed writes no result and no stip. |

### Clarifications from slice S5 (awaiting owner confirmation, 2026-11-19; C-24 added in the S5 review)

| # | Question | Proposed answer (implemented) |
|---|---|---|
| C-20 | A new document is accepted while the version is under review, but by the time its event arrives the version has moved on (approved, rejected, expired). Does it still re-run? | **No.** The start API answers 409 `NOT_RERUNNABLE` (the Lambda acknowledges it) and nothing re-runs. Only `PENDING_REVIEW` and `STIPULATED` re-run (AS-1.8). |
| C-21 | Who moves a STIPULATED version back to PENDING_REVIEW when a new document arrives (spec §6 "re-run started")? | The start API, in the same transaction as an audit row (`DOCUMENT` / `RERUN_REQUESTED`, then `STATUS_CHANGED` with reason `DOCUMENT_ADDED`), then it signals `documentsUpdated(documentId)`. The event is at-least-once: a redelivery finds the audit row, changes nothing and signals again, and the workflow ignores a document it already re-ran for. |
| C-22 | When is V1's run superseded: when V2 is created, or when V2 is submitted? | **When V2 enters PENDING_REVIEW** (AS-9.2), because that's when the start API runs. Between creation and submission V1's run stays open, but V1 is no longer latest, so every write to it is refused (activities report SUPERSEDED, funder API 409 `NOT_LATEST_VERSION`). The start API supersedes every older version of the application that still has an open run. |
| C-23 | A new document re-runs **all** checks (C-10). Does a check that still fails raise its stip again if a funder had already waived or satisfied it? | **Yes. Confirmed by the owner (2026-11-20):** "Don't waive anything, as you never know what the new version has." A funder's waiver or satisfaction applies to the documents it was given for, so a still-failing check creates a new PENDING stip against the new documents (only a PENDING stip is reused, FR-07). Example: LA-1027 with the VIN stip waived, the income stip satisfied and a corrected RISC for TILA; after the re-run the VIN and income stips are PENDING again, for the funder to decide anew. |
| C-24 | Can a new application version be created once the latest one is being paid or is paid? Each version pays under its own idempotency key (its workflow ID), so a second version could pay the dealer twice. | **No.** Creating a version is refused with 409 `VERSION_FUNDED` once the latest is FUNDED, and with 409 `DISBURSEMENT_STARTED` while its disbursement is REQUESTED (in flight, or retries exhausted with the outcome unknown) or COMPLETED. That holds for an expired or rejected version too. A FAILED disbursement (the API refused it) doesn't block. To resubmit after an unknown outcome, retry the disbursement first (C-8), since the same idempotency key reveals whether it was paid. The check shares the row lock that the disbursement activity takes before paying, so it can't miss a payment that is about to start. |. ** No **

