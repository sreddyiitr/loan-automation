# Spec 002: funder capabilities (input to the spec)

**Status:** draft for the owner, 2026-11-20. This is the capability list `spec.md` for spec 002 will
turn into user stories and acceptance scenarios (constitution P-1: spec first). The UI is the
**ADK A2UI funder dashboard** that spec 001 left out of scope, and that the source document
(`auto_funding_tasks.md` §5) describes as four tabs.

## What a funder is

A funder (underwriter) reviews a dealer's funding package before the lender pays the dealer. Spec 001
automated the checks: TILA math, price parity, VIN and vehicle, GAP form and lender, income
verification. Clean deals now fund with no human involved. **The funder's job becomes the
exceptions:**
- deals with stipulations (stips);
- deals held because a check couldn't run (ERROR) or the contract is incomplete;
- deals whose payment failed;
- deals that need a human decision: approve, reject, or ask the dealer for more.

## How to read this

Every capability is tied to what spec 001 already built:

| Mark | Meaning |
|---|---|
| ✅ **Ready** | The funder API supports it today and spec 001 tests it. Spec 002 builds UI on it. |
| 🟡 **Small API addition** | Mostly there; the UI needs a field or a read endpoint that doesn't exist yet. |
| 🆕 **New** | Needs new backend work or a decision that spec 001 explicitly left out. |

API paths are in [`contracts/funder-api.openapi.yaml`](../../contracts/funder-api.openapi.yaml)
(checked in, kept in sync by `FunderOpenApiTest`). Every write takes `X-Funder-Id`. Errors are RFC
9457 problem+json with a `reason` the UI can act on.

---

## 1. Find the work (the queue)

The source starts at one application; a funder starts at *"what needs me?"*.

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 1.1 | **My queue:** applications assigned to me that need a human (STIPULATED; PENDING_REVIEW held on ERROR or an incomplete RISC; APPROVED with a failed payment) | 🆕 | No list or search endpoint exists; the API reads one application at a time. Needs `GET /api/v1/applications?status=&assignee=&…` |
| 1.2 | **Unassigned queue:** the same, with no funder yet (C-7: assignment is manual) | 🆕 | Same endpoint, `assignee=none` |
| 1.3 | **Sort and filter** by status, stip type, hold reason (pending stips, check in ERROR, RISC incomplete, payment failed or unknown), dealer, days to expiry, age | 🆕 | Same endpoint, with the expiry date, hold reasons and stip codes in each row |
| 1.4 | **Expiring soon:** versions near the 30-day limit (AS-8.6) | 🆕 | Needs `expiresAt` in the list (it is `submitted_at` + `funding.workflow.expire-after`) |
| 1.5 | **Open by id:** jump to an application by loan id | ✅ | `GET /applications/{id}` (AS-10.1) |

## 2. Understand the application (source Tab 1: Overview)

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 2.1 | Overview: application and version, status, dealer, borrower and co-borrower, stated income, VIN, assigned funder, submitted date | ✅ | `GET /applications/{id}` (AS-10.1) |
| 2.2 | **Version history:** every application version, newest first, with its status; earlier versions are read-only history (superseded, AS-9.2) | ✅ | `GET /applications/{id}/versions` |
| 2.3 | **Reassign** the funder (audited) | ✅ | `PUT /versions/{vid}/assignee` (AS-10.3) |
| 2.4 | **Days left before expiry** (the clock starts at first submission and never resets, C-11) | 🟡 | Add `expiresAt` to the overview |
| 2.5 | **"Being processed" state:** checks running, re-run in progress, payment in flight. The UI disables actions that would get 409 `DISBURSEMENT_IN_PROGRESS` | 🟡 | The workflow knows (`getState().step`), but only the trigger API exposes it, behind its key. Add a `processing` field to the overview |
| 2.6 | **Payment state:** not started, paid (with reference and amount), failed (C-8), outcome unknown | 🟡 | The `disbursements` row exists; no funder read. Add it to the overview |
| 2.7 | Link to the run in the Temporal UI (the workflow ID is deterministic) for engineers and auditors | ✅ | `funding-<env>-<versionId>`; see [`learnings/workflows_summary.md`](../../learnings/workflows_summary.md) |

## 3. Work the stipulations (source Tab 2: Stips and exception queue)

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 3.1 | List a version's stips: code, description, the check that raised it, the triggering document, status, who resolved it and when, with the note | ✅ | `GET /versions/{vid}/stipulations` (AS-10.2) |
| 3.2 | **Mark SATISFIED** (dealer provided what was asked), with a required reason | ✅ | `PATCH …/stipulations/{sid}`. Two funders at once: the second gets 409 `ALREADY_RESOLVED` |
| 3.3 | **Waive** (accept the exception), with a required reason | ✅ | Same endpoint. RISC-bound stips (TILA, RISC extraction) refuse it with 409 `RISC_STIP_NOT_WAIVABLE`. **The UI hides Waive for them** and says a corrected RISC is needed |
| 3.4 | **Add a manual stip**, e.g. `STIP_PROOF_OF_INCOME` (C-5: the owner confirmed this is a UI action). It moves the version to STIPULATED | ✅ | `POST /versions/{vid}/stipulations` (AS-10.8). 409 `STIP_ALREADY_PENDING`; 409 `DISBURSEMENT_IN_PROGRESS` while paying |
| 3.5 | See stips the **system** cleared: a check that passes on a re-run auto-satisfies its own stip (AS-8.8), and a corrected document re-raises a still-failing one (C-23) | ✅ | `resolved_by = system:<workflowId>` in the stip list and audit |
| 3.6 | **Choose a stip code** from a list, with descriptions | 🟡 | The codes live in the domain (`StipCodes`); expose them as a small read endpoint so the UI doesn't hard-code them |

## 4. See why a check failed (source Tab 3: Mismatches and reconciliation)

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 4.1 | Per check: outcome (PASS, FAIL, NEEDS_REVIEW, ERROR) with **expected vs actual** for every comparison, e.g. `amount_financed + finance_charge = total_of_payments`: 33792.60 vs 33802.60 | ✅ | `GET /versions/{vid}/validation-results` (AS-10.4) |
| 4.2 | **Attempt history:** every re-run of a check, not just the current result | ✅ | `…/validation-results?history=true` (FR-06) |
| 4.3 | **Side-by-side document table** (e.g. cash price on the RISC vs the Buyer's Order; VIN on RISC vs GAP) | 🟡 | The comparison carries the documents only inside its name (`vin (RISC vs GAP_AGREEMENT)`). Add structured `documents: {left, right}` (and field keys) to each comparison so the UI can lay out columns without parsing text |
| 4.4 | **Confidence highlighting:** fields below the cut-off (0.90, `funding.confidence.flag-below`) in yellow or red, and the fields that put a check into NEEDS_REVIEW | 🟡 | Confidence per field and `flaggedFields` per result exist. Expose the cut-off (a config read) so the UI doesn't hard-code 0.90 |
| 4.5 | Why a check is **ERROR** (e.g. income API down after 5 attempts) and what to do: re-run it | ✅ | The result's error text plus the re-run action (6.3) |

## 5. Read the documents (source Tab 4: Version history and viewer)

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 5.1 | Document versions per type (RISC v1, v2…), which one is latest, when each was processed | ✅ | `GET /versions/{vid}/documents` (AS-10.5) |
| 5.2 | Extracted fields per document: section, value, confidence, page, bounding box | ✅ | `GET …/documents/{did}/fields` (AS-10.5) |
| 5.3 | **Compare two versions** of a document (RISC v1 vs v2): what changed | ✅ | Two field reads; the UI diffs them (no new API) |
| 5.4 | **PDF page viewer with bounding-box overlays** on each field | 🆕 | Spec 001 stores only `file_name`; no page images or PDFs are stored or served. Needs document storage (S3 locally via the emulator?) and an endpoint for a page image. OCR stays out of scope |
| 5.5 | When each document version arrived from the dealer portal | ✅ | `processed_at`, plus the audit trail (a new document that triggered a re-run, C-21) |

## 6. Decide (actions on the version)

All are guarded writes: latest version, not terminal (409 `NOT_LATEST_VERSION`, `VERSION_TERMINAL`,
`VERSION_EXPIRED`), audited, then a signal to the workflow. The result is asynchronous, so the UI
shows "submitted" and refreshes the status (2.5).

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 6.1 | **Approve** manually, e.g. over an ERROR hold (AS-8.7) | ✅ | `POST /versions/{vid}/approve`. 409 `PENDING_STIPS`, `RISC_INCOMPLETE` (AS-8.10), `ALREADY_APPROVED`. After approval the system pays |
| 6.2 | **Reject**, with a required reason | ✅ | `POST /versions/{vid}/reject` (AS-8.5). 409 `DISBURSEMENT_IN_PROGRESS` while paying |
| 6.3 | **Re-run checks**: by default the ones in ERROR, or the ones the funder picks (attempt + 1, fresh documents) | ✅ | `POST /versions/{vid}/rerun` (AS-8.4). 409 `NOTHING_TO_RERUN`, `NOT_RERUNNABLE` |
| 6.4 | **Retry a failed payment** (C-8; only a funder's retry re-pays) | ✅ | `POST /versions/{vid}/retry-disbursement`. 409 `NOTHING_TO_RETRY` |
| 6.5 | Show **only the actions the status allows** (spec §6 state machine), with the reason others are off | 🟡 | The rules are in `VersionStatus` and the 409 reasons; a small `allowedActions` list in the overview keeps the UI from re-implementing them |

## 7. Accountability (audit)

| # | Capability | Mark | Backed by / gap |
|---|---|---|---|
| 7.1 | **Audit trail** of the version: every status change, stip change, reassignment, re-run, document event, payment, with actor, before and after, and time | ✅ | `GET /versions/{vid}/audit` (FR-10) |
| 7.2 | Every write names the funder | ✅ (stand-in) | Today `X-Funder-Id` is a header anyone can set (AS-10.9). Real identity is 8.1 |

## 8. Platform for the dashboard

| # | Capability | Mark | Notes |
|---|---|---|---|
| 8.1 | **Sign-in and identity:** the funder's id comes from the login, not a header | 🆕 | Spec 001 left authN/authZ out. The owner decides the identity provider |
| 8.2 | **Roles:** funder, income specialist and supervisor (owner, 2026-11-21), enforced by the API (spec.md US-11) | 🆕 | Today any funder can do everything; needs role checks in the funder API |
| 8.3 | **Fresh data:** statuses change asynchronously (checks, payments, expiry) | 🟡 | Polling the overview works today; push (events) is a later option |
| 8.4 | Never show the SSN | ✅ | No funder endpoint returns it; the snapshot only knows a token exists (FR-13) |

## 9. What an A2UI agent could add

With ADK, the dashboard is driven by an agent that composes the UI from declarative A2UI components,
which the client renders with its own trusted widgets. That makes some assistant capabilities
natural. They are proposals; each needs the owner's yes. **The agent acts only through the same
funder API, and every write needs the funder's explicit confirmation.**

| # | Capability | Notes |
|---|---|---|
| 9.1 | **Case summary:** "why is this deal stuck?" in plain words, from results, stips and audit | Read-only |
| 9.2 | **Suggested next step**, e.g. "INCOME is ERROR after 5 attempts; re-run it?" or "the price is off by 1¢; waive?" | The funder confirms; the agent never decides |
| 9.3 | **Draft a manual stip** description for the dealer | The funder edits and confirms |
| 9.4 | **Explain a mismatch** using the comparisons and the field confidence | Read-only |
| 9.5 | **Queue triage:** what is urgent today (expiring, failed payments) | Needs 1.1–1.4 |

## 10. Not available, by design or out of scope

- **Editing extracted values.** A correction is a new document version from the dealer, never an edit (AS-9.4, B5). Documents freeze at approval (AS-9.7).
- **Uploading documents from the dashboard.** In spec 001 only the local dev endpoint does this; OCR and the dealer portal are out of scope.
- **Messaging the dealer** (sending stips out). No dealer integration exists.
- **Bulk actions** (approve or reassign many). Possible later on top of 1.1.
- **Creating a new application version.** That comes from the dealer (a resubmission, US-9), and is refused once a payment has started (C-24).

---

## Summary: what spec 002 has to build

- **UI only (✅, 24 capabilities):** overview, version history, reassignment, stips (satisfy,
  waive, add), mismatches with expected vs actual and attempt history, document versions and
  fields, approve, reject, re-run, retry payment, audit.
- **Small API additions (🟡):**
  - `expiresAt`, `processing`, payment state and `allowedActions` on the overview;
  - a stip-code list;
  - structured document attribution on comparisons;
  - the confidence cut-off.
- **New backend work or decisions (🆕):**
  - the worklist and search endpoint (1.1–1.4);
  - document storage and page images for the PDF viewer (5.4);
  - sign-in, identity and roles (8.1–8.2);
  - which agent capabilities to include (§9).

## Decisions for the owner before `spec.md`

1. **Agent scope (§9):** a plain dashboard first, or the assistant capabilities from day one?
2. **PDF viewer (5.4):** in spec 002 (needs document storage), or field-level views first with the viewer later?
3. **Identity (8.1/8.2):** which login provider; are there roles beyond "funder"?
4. **Worklist (1.1–1.4):** which filters and sort orders matter most to funders day to day?
5. **ADK runtime:** ADK in Python or Java (the backend is Java 21), and how the A2UI client is hosted (web app framework).
