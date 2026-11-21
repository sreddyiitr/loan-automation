# Following one loan through Temporal

This is the story of one application, **version 242**, going from "submitted" to "dealer paid" in
under a second, and of how the Temporal UI lets anyone (a funder, an auditor, an engineer on call)
replay exactly what happened: when each step ran, what it was given, what it decided, and why.

Version 242 is a clean deal from a functional-test run (`FUNDING_ENV=it-2ea7a8`): every check
passes, so the system approves it and pays the dealer with no human involved. The screenshots are
from the Temporal web UI that ships with the dev server (`make temporal`, http://localhost:8233).

---

## 1. One application, six workflows

![Workflows for an app](screenshots/Workflows%20for%20an%20App.png)

Filtering the workflow list by **`WorkflowId starts with "funding-it-2ea7a8-242"`** pulls up
everything that ever ran for this application version, and nothing else. That works because the
IDs are deterministic (plan "Workflow ID"):

| Workflow ID | What it is |
|---|---|
| `funding-it-2ea7a8-242` | the parent `FundingWorkflow` for application version 242 |
| `…-242-TILA-a1` | the TILA math check, attempt 1 |
| `…-242-PRICE-a1` | price parity between the contract and the Buyer's Order |
| `…-242-VIN-a1` | VIN, year, make and model agree everywhere |
| `…-242-GAP-a1` | the GAP form and lender |
| `…-242-INCOME-a1` | income verification against the external Income API |

Already, without opening anything, you can read the story:

- **All six COMPLETED**, with zero failures.
- **The checks ran in parallel.** All five children started within 40 ms of each other
  (11:34:33.53 to 33.57) and finished between 33.66 and 33.74.
- **The whole deal took under a second.** The parent started at 11:34:33.33 and ended at 34.14.
- **The `-a1` suffix is the attempt number.** If a funder re-ran a check, or a corrected document
  arrived, the re-run would appear here as `-INCOME-a2`, next to the first attempt and not
  overwriting it. The history of every attempt stays visible.

---

## 2. The timeline: what happened, in what order, and how long it took

![Timeline of workflows for an app](screenshots/Timeline%20of%20workflows%20for%20an%20app.png)

Opening the parent workflow shows the whole run on one screen.

**The header answers "what was this?"** `FundingWorkflow`, started 11:34:33.33, ended 11:34:34.14,
**812 ms** in total. It ran on the `funding-orchestration-it-2ea7a8` task queue, with 56 events in
its history and 5 child relationships.

**Input and Result answer "what was asked, and what was the outcome?"**

- **Input:** `{"applicationVersionId":242,"mode":"VALIDATE", …}` (validate this version).
- **Result:** `{"result":"FUNDED"}`.

**The timeline, read from the bottom up, is the business process itself:**

1. **`Version` markers** (three of them). These record which version of the workflow code this run
   used (`funder-signals`, `rerun-before-settling`, `income-check`). They are what lets us change
   the workflow safely while older runs are still in flight (NFR-07). An auditor can ignore them,
   but they also prove which rules this run followed.
2. **`LoadVersionSnapshot`.** Read the application and its *latest* documents from Postgres, once.
   Every check works from this same snapshot.
3. **Five child workflows side by side:** `TilaCheckWorkflow`, `PriceParityWorkflow`,
   `VinConsistencyWorkflow`, `IncomeVerificationWorkflow` and `GapValidationWorkflow`, running in
   parallel (FR-04). The bars show their relative lengths at a glance. Income is the longest,
   because it calls an external API; the others are pure calculations.
4. **`ApplyOutcome`** starts only after the last child has finished. It turns five results into
   one status: everything passed and the contract is complete, so the version is **APPROVED**.
5. **`Disburse`** pays the dealer the RISC `amount_financed` through the Disbursement API. Its
   idempotency key is the workflow ID, so a retry can never pay twice.
6. **`MarkFunded`** sets the status to **FUNDED**.

Nobody had to stitch log lines together to get this picture. The order, the parallelism and the
duration of each step are drawn for you, and every bar is clickable.

---

## 3. Drilling into one decision: why did TILA pass?

![Auditability of workflow](screenshots/Auditability%20of%20workflow.png)

Clicking `TilaCheckWorkflow` (or following the link in the list) opens the TILA check for the same
application. The **Parent Workflow** field links back to `funding-it-2ea7a8-242`, so you can always
navigate up and down the tree.

- **Input:** `{"check":"TILA","attempt":1,"snapshot":{"applicationVersionId":242,…}}`. These are the
  exact contract values this check saw. If the dealer later sends a corrected contract, the re-run
  is a new child (`-a2`) with its own input, and this one is kept as it was.
- **Result:** `{"check":"TILA","outcome":"PASS","comparisons":[…]}`.

Expanding the **`RecordOutcome`** activity shows the evidence, field by field:

| Comparison | Expected | Actual | OK |
|---|---|---|---|
| `amount_financed + finance_charge = total_of_payments` | 33792.60 | 33792.60 | ✅ |
| amortized `monthly_payment` | 563.21 | 563.21 | ✅ |
| payment schedule = `total_of_payments` | 33792.60 | 33792.60 | ✅ |

The same panel also records *how* the step was run:
- **Event 5**, scheduled at 11:34:33.63 on `funding-validation-it-2ea7a8`.
- A 10-second timeout.
- The retry policy: start at 1 s, ×2 backoff, at most 60 s between tries, at most 5 attempts
  (NFR-03).

It took 8 ms.

The same numbers are written to the database (`validation_results.details`), which is what the
funder API serves. The Temporal history is an independent record of the same decision, captured as
it happened.

---

## 4. What this gives an auditor

For any application version, the Temporal UI answers these questions without reading code or logs:

| Question | Where to look |
|---|---|
| Was it validated, and when? | The workflow list: status, start and end time |
| Which checks ran, and were they independent? | Parent timeline: the five parallel children |
| What data did each check see? | Each child's **Input** (the snapshot) |
| Why did a check pass or fail? | Each child's **Result**: every comparison with expected and actual values |
| Why was the dealer paid, how much, and when? | `ApplyOutcome` → `Disburse` → `MarkFunded` on the parent timeline |
| Did anything fail or get retried? | **Failures** count, the attempt numbers in IDs, retry events in **Event History** |
| Who did what? | The funder's actions arrive as **signals** with the actor (`approve(funder-7)`, `reject(…, reason)`) in Event History |
| Was it re-checked after a correction? | New children with `-a2`; a `documentsUpdated` signal carrying the document ID |
| Was it superseded by a resubmission? | Result `{"result":"SUPERSEDED"}` and a `supersede` signal |

For a deal that does **not** fund cleanly, the same three screens tell a longer story. The
timeline shows the run settling to STIPULATED and then **waiting**, with a timer for the 30-day
expiry. Each funder action or corrected document then appears as a signal, followed by any re-run
children and a new `ApplyOutcome`, until the deal is funded, rejected or expires.

### What it doesn't show, by design

- **No SSN.** The snapshot only says *whether* an SSN token exists. The token itself is loaded
  inside the income activity and sent only to the Income API; it never appears in any workflow
  input, result or history (FR-13, checked by `SsnNeverLeaksTest` against real histories).
- **Not the system of record.** Workflow histories are kept for the namespace's retention period.
  `make temporal` keeps them in `~/.temporal/funding.db` (plain `start-dev` only in memory). The permanent business audit trail is
  Postgres: `audit_log`, `validation_results` with every attempt, and `stipulations`, served by
  `GET /api/v1/versions/{id}/audit`. Temporal is the second, execution-level view of the same
  events: easy to browse, and exact about timing and inputs.

---

## Try it yourself

1. `make temporal`, then `make run` (see the [README](README.md)).
2. `make seed-submit-all`.
3. Open http://localhost:8233, choose the `default` namespace, and filter by
   `WorkflowId starts with "funding-local-<versionId>"`. A clean deal (LA-1001) looks like the
   screenshots above. Try a stipulated one (LA-1006) to see a run that waits for a funder, then
   waive its stip through the funder API and watch the signal and the payment appear.
