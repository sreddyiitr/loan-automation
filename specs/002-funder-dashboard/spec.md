# Feature Spec 002: Funder Dashboard (ADK + A2UI)

| | |
|---|---|
| **Status** | Draft for owner review, 2026-11-21. Decisions already made are in §9. |
| **Source** | `auto_funding_tasks.md` §5 (the four tabs), [`capabilities.md`](capabilities.md), spec 001's funder API |
| **Builds on** | Spec 001: the funder API ([`contracts/funder-api.openapi.yaml`](../../contracts/funder-api.openapi.yaml)), its status machine (spec 001 §6) and its seeded scenarios |
| **Scope** | A funder dashboard: an A2UI v1.0 web client, a Python ADK agent service, a custom component catalog, the funder-API additions the UI needs, a local LLM assistant, and a one-command developer setup |
| **Out of scope** | Editing extracted values, uploading documents, messaging dealers, bulk actions, real single sign-on (a local OIDC provider stands in), document storage and the PDF page viewer (a later spec), AWS deployment |
| **Reference** | [`a2ui-notes.md`](a2ui-notes.md) (the protocol and ADK, read 2026-11-20), [`ui_summary.md`](../../ui_summary.md) |

---

## 1. Problem & Goal

Spec 001 funds clean deals with no human involved. What is left for funders is the **exceptions**:
- deals with stipulations;
- deals held because a check couldn't run or the contract is incomplete;
- deals whose payment failed;
- deals that need a human decision.

Today, a funder can only reach these through REST calls.

**Goal:** a dashboard where a funder finds the deals that need them, understands each one quickly,
and acts on it safely. Every action goes through the spec 001 funder API, so the existing guards,
audit trail and workflow signals apply unchanged. The UI is built with **A2UI**, which is also a
learning goal for this project. An LLM assistant helps explain a case, but never acts on its own.

## 2. Actors

| Actor | Role |
|---|---|
| **Funder** | Signs in, works their queue, reviews deals, resolves stips, decides (US-11 sets what each role may do). |
| **Income specialist** | A funder specialised in verifying income ("speciality funder"). Works the income queue: resolves income stips and income-check problems. Funders can't. |
| **Supervisor** | Oversees every queue and assigns and reassigns work. **Can do everything**, including income work. |
| **Dashboard client** | A browser app that renders A2UI v1.0 surfaces with the React renderer plus our custom catalog, and sends the funder's clicks back as A2UI `action` events. |
| **Agent service** | Python with ADK and `a2ui-adk`. It builds surfaces from funder API data (code, no LLM), handles actions by calling the funder API, and hosts the assistant. |
| **Funder API** | Spec 001's Java API, extended in §5. The only component that writes. |
| **LLM** | A model named in configuration. The default is local Gemma (`gemma4:e4b`) through Ollama. It is used only by the assistant (US-8, US-9). |

## 3. Domain Concepts

- **Surface:** an A2UI UI region (the worklist, one tab of a deal, the assistant panel). It is
  identified by a `surfaceId`.
- **Fixed surface:** a surface built by code from API data. The same data always gives the same
  UI. Every screen except the assistant panel and the generated view is fixed.
- **Generated surface:** a surface whose A2UI is written by the LLM (US-9 only). Its JSON is
  validated before the client sees it.
- **Allowed action:** an action the version's status and stips permit, with a reason. It comes
  from the funder API (FR-05), not from logic in the UI.
- **Hold reason:** why a deal needs a human, derived by the API from its status, stips, results and
  payment. One deal can have several:
  - `STIPS_PENDING`
  - `CHECK_ERROR`
  - `RISC_INCOMPLETE`
  - `PAYMENT_FAILED`
  - `PAYMENT_UNKNOWN`
- **Income work:** anything about verifying income:
  - the income stips `STIP_INCOME_VARIANCE_EXCEEDED` and `STIP_PROOF_OF_INCOME`;
  - an INCOME check that is FAIL, NEEDS_REVIEW or ERROR.

  It routes to the income queue, which income specialists (and supervisors) work, not funders (US-11).
- **Role:** `FUNDER`, `INCOME_SPECIALIST` or `SUPERVISOR`. The funder API looks it up from the
  signed-in user's id in its user directory, and enforces it.
- **Assistant answer:** small structured JSON from the LLM, `{summary, suggestions[{action,
  reason}]}`, validated and then rendered by code.

## 4. User Stories & Acceptance Scenarios

### US-1 Sign in and worklist (P1)
- **AS-1.1** A user signs in through the local OIDC provider's login page by typing a user id; there's no password, and this is local only. The funder API gives that user their role (`FUNDER`, `INCOME_SPECIALIST` or `SUPERVISOR`) from its user directory. Every request is then made under that id and role, with no identity typed by hand. An id that isn't in the directory is refused with 403 `UNKNOWN_USER`.
- **AS-1.2** **My queue** lists the latest versions assigned to the user that need a human:
  - STIPULATED;
  - PENDING_REVIEW held on an ERROR or an incomplete RISC;
  - APPROVED with a failed or unknown payment.

  Each row shows the loan id, dealer, status, **hold reasons**, the open stip codes, days to expiry and whether something is in progress.
- **AS-1.3** **Unassigned** lists the same kinds of deals with no funder. **Expiring soon** lists deals with 5 days or fewer to expiry (the threshold is configurable).
- **AS-1.4** The queue can be filtered by:
  - status;
  - **stip type** (one or more stip codes, e.g. `STIP_PRICE_MISMATCH`);
  - **hold reason** (e.g. `CHECK_ERROR`, `PAYMENT_FAILED`);
  - dealer;
  - days to expiry.

  It can be sorted by days to expiry (the default) or age. The filters combine: e.g. "STIPULATED with a pending `STIP_PROOF_OF_INCOME`, expiring within 5 days".
- **AS-1.5** Opening a row opens that deal. Opening a loan id that doesn't exist shows "not found", not an error page.
- **AS-1.6** Terminal deals (FUNDED, REJECTED, EXPIRED) and superseded versions never appear in a queue, but can be opened by id.
- **AS-1.7** The **income queue** lists every deal with pending income work. It's the income specialist's main view; supervisors see and work it too.

### US-2 Deal overview (P1) (source Tab 1)
- **AS-2.1** The overview shows:
  - the loan id and version number, status, dealer, borrower and co-borrower, stated income, VIN;
  - the assigned funder and submitted date;
  - **days to expiry**;
  - **payment status:** not started, paid with reference and amount, failed, or outcome unknown.
- **AS-2.2** While checks are running, a re-run is in progress or a payment is in flight, the overview says so. Actions that would be refused (409 `DISBURSEMENT_IN_PROGRESS`) are disabled with that reason.
- **AS-2.3** **Version history** lists every application version, newest first. Earlier versions open read-only, marked "superseded by vN".
- **AS-2.4** A funder can **reassign** the deal to another funder from a list, or back to unassigned (`funderId: null`), which puts it in the unassigned queue again. The change shows at once and appears in the audit trail. Who may reassign is AS-11.2.
- **AS-2.5** A link opens the deal's workflow run in the Temporal UI.
- **AS-2.6** **Take** (assign to self) is offered only on an unassigned deal. It asks for confirmation; once confirmed, the overview shows the user as the assignee and no longer offers Take. If someone else took the deal in the meantime, for example from a stale screen, the API refuses with 409 `ALREADY_ASSIGNED`, nothing changes, and the overview shows who has it. Taking a deal you already have changes nothing. Role rules on Take come with AS-11.2.

### US-3 Stipulations (P1) (source Tab 2)
- **AS-3.1** The stip list shows each stip's:
  - code and description;
  - the check that raised it and the triggering document;
  - status, and who resolved it, when and why.

  It distinguishes stips the **system** satisfied after a passing re-run (AS-8.8 in spec 001) from stips a funder resolved.
- **AS-3.2** **Satisfy** and **Waive** need a reason. After success the stip shows its new status, and the deal status refreshes. If the deal funds as a result, the overview shows FUNDED without a manual reload.
- **AS-3.3** For a stip that can't be waived (TILA math, RISC extraction), **Waive is not offered**. The stip explains that a corrected RISC from the dealer is required.
- **AS-3.4** If another funder resolved the stip first (409 `ALREADY_RESOLVED`), the UI says who and when, and refreshes. It never shows a raw error.
- **AS-3.5** **Add stip**: the funder picks a code from the list the API provides, writes a description, and confirms. The stip appears as PENDING, and an APPROVED deal moves to STIPULATED. When adding a stip isn't allowed, the button is disabled with the reason.

### US-4 Check results and mismatches (P1) (source Tab 3)
- **AS-4.1** Each check (TILA, PRICE, VIN, GAP, INCOME) shows its outcome. A failing or review-needed check lists every comparison as **expected vs actual**, with the mismatching rows highlighted.
- **AS-4.2** A comparison between two documents shows them **side by side**, one column per document (e.g. the RISC cash price vs the Buyer's Order price; the VIN on the RISC vs the GAP).
- **AS-4.3** A field below the confidence cut-off (0.90, read from the API) is marked as low-confidence. The fields that put a check into NEEDS_REVIEW are named.
- **AS-4.4** A check in ERROR shows its error (e.g. income API unavailable after 5 attempts) and offers **re-run** if it's allowed.
- **AS-4.5** **History** shows every attempt of a check (attempt 1, 2, …), not only the current one.

### US-5 Documents (P2) (source Tab 4, without the page viewer)
- **AS-5.1** The documents view lists each document type with its versions, which one is latest, and when each arrived.
- **AS-5.2** A document version shows its extracted fields by section, with value, confidence, page and bounding box.
- **AS-5.3** Two versions of a document can be **compared**: changed, added and removed fields are highlighted.

### US-6 Decisions (P1)
- **AS-6.1** Only the actions the deal allows are offered (approve, reject, re-run, retry payment, add stip). Others are hidden, or disabled with the reason from the API (FR-05).
- **AS-6.2** **Approve**, **reject** (with a required reason) and **retry payment** ask for confirmation. After success the UI shows "submitted" and then the resulting status as the workflow settles.
- **AS-6.3** **Re-run** lets the funder choose checks. It defaults to the ones in ERROR. The new attempt appears in the history (AS-4.5).
- **AS-6.4** Every refusal from the API (409 or 400 problem+json) is shown as a plain sentence based on its `reason`, never as a raw error. Examples: "a stip is still pending", "this deal expired", "a newer version exists".

### US-7 Audit (P2)
- **AS-7.1** The audit view lists every change to the deal: status, stips, assignment, re-runs, documents, payments. Each entry shows the actor, time, and before and after values, newest first.
- **AS-7.2** Changes made by the system (`system:<workflowId>`) are labelled as automatic.

### US-8 Assistant (P3)
- **AS-8.1** On a deal, **"Summarize"** shows a short plain-language explanation of why the deal isn't funded, based only on the deal's data. On a finished deal (FUNDED, REJECTED, EXPIRED) it says how the deal ended. The summary is read-only: it offers no actions.
- **AS-8.2** **"Suggest next step"** shows at most two suggestions, each with a reason and drawn only from the deal's allowed actions. A suggestion that isn't allowed is dropped by code. Choosing a suggestion **opens the normal action with its confirmation**; the assistant never performs an action.
- **AS-8.3** **"Draft stip text"** proposes a description for a manual stip. The funder edits it before adding the stip.
- **AS-8.4** When no model is available, the assistant shows "assistant unavailable" and everything else works. An answer that fails validation after one retry shows "couldn't produce an answer"; nothing invalid is shown.
- **AS-8.5** The model never receives SSNs (the API never returns them) or any data beyond the deal being viewed.

### US-9 Generated view (P3, learning)
- **AS-9.1** On a deal, the funder can ask for a view in words, e.g. "show the income check as a card". The LLM writes the A2UI surface. It is validated against the catalog schemas before it's shown.
- **AS-9.2** A generated view is read-only: it can show data, but its buttons can only open the normal actions (AS-6.2).
- **AS-9.3** A view that fails validation after one retry is not shown. The funder sees "couldn't build that view".

### US-10 Developer setup (P1)
- **AS-10.1** On a laptop with spec 001's prerequisites, **one command** starts the funding API, the agent service and the web app, reusing Temporal, Postgres, the emulator and WireMock as spec 001 does.
- **AS-10.2** A pre-flight check names each missing prerequisite (Python 3.12 with `uv`, Node 22, and optionally Ollama), with its install command.
- **AS-10.3** On first launch the seeded scenarios are in the dashboard. A funder can open LA-1006 and waive its price stip with no setup.
- **AS-10.4** Without Ollama, everything except the assistant and the generated view works (AS-8.4).
- **AS-10.5** The tests need no running model. LLM responses are recorded and replayed.

### US-11 Roles and permissions (P1)
- **AS-11.1** Every user can open and read any deal. Roles limit only **writes** and **queues**.
- **AS-11.2** What each role may do (the API enforces it, the UI only reflects it):

  | Action | Funder | Income specialist | Supervisor |
  |---|---|---|---|
  | Queues | My queue, unassigned | Income queue, my queue | All queues, any user's queue |
  | Satisfy or waive a stip that isn't income work, on a deal assigned to them | ✅ | ✅ | ✅ (any deal) |
  | Satisfy or waive an **income stip** | ❌ (send it to the income queue) | ✅ | ✅ |
  | Re-run the **INCOME** check | ❌ | ✅ | ✅ |
  | Re-run any other check | ✅ (assigned) | ✅ (assigned) | ✅ |
  | Add a stip (including `STIP_PROOF_OF_INCOME`) | ✅ (assigned) | ✅ (assigned) | ✅ |
  | Approve | ✅ (assigned), not while income work is pending | ✅ (assigned), including over an INCOME check in ERROR | ✅ (any deal, including over income work) |
  | Reject; retry payment | ✅ (assigned) | ✅ (assigned) | ✅ |
  | Take an unassigned deal (assign to self) | ✅ | ✅ (income work only) | ✅ |
  | **Send to the income queue** (hand a deal with income work to income specialists) | ✅ | n/a | ✅ |
  | Reassign a deal to another user (including among income specialists) | ❌ | ❌ | ✅ |

- **AS-11.3** A write the role doesn't allow is refused by the API with **403** and a reason (`ROLE_NOT_ALLOWED`, `INCOME_WORK_NEEDS_SPECIALIST`, `NOT_ASSIGNED_TO_YOU`). The UI never offers it (AS-6.1), and shows the reason if it's attempted anyway, e.g. from a stale screen.
- **AS-11.4** The audit trail records the actor's **role** with each change.
- **AS-11.5** A deal sent to the income queue keeps its assigned funder. It goes back to that funder's queue once no income work is pending.
- **AS-11.6** **Funders don't do income work.** A funder trying to resolve an income stip, re-run INCOME, or approve while income work is pending gets 403 `INCOME_WORK_NEEDS_SPECIALIST`. Income specialists and supervisors may.

## 5. Functional Requirements

| ID | Requirement |
|---|---|
| FR-01 | The UI is rendered from **A2UI v1.0** messages (`version: "1.0"`). Component IDs follow UAX #31: underscores, no hyphens. No `theme`: styling belongs to the client. |
| FR-02 | **Fixed surfaces** (worklist; deal overview, stips, results, documents, audit) are built by agent code from funder API data, never by the LLM. |
| FR-03 | **Every write** (satisfy, waive, add stip, reassign, approve, reject, re-run, retry payment) is a funder click that goes to agent code, which calls the funder API with the signed-in funder's id. The LLM can't trigger a write. |
| FR-04 | The **custom catalog** has its own `catalogId` and the components the basic catalog lacks: `DataTable`, `StatusBadge` and `ConfidenceTag`. They're implemented in the React renderer. |
| FR-05 | **Funder API additions** (Java, spec 001's style, added to `contracts/funder-api.openapi.yaml`): a. A worklist endpoint, `GET /api/v1/applications`, filtered by status, assignee (`me`, `none`, an id), queue (`income`), stip code, hold reason, dealer and days to expiry (FR-12), and sortable. b. On the overview: `expiresAt`, an in-progress indicator, payment status, and `allowedActions` (each with an allowed flag and a reason code). c. A stip-code list with descriptions. d. Structured document attribution on each comparison (which document each side came from). e. The confidence cut-off. |
| FR-06 | Each funder API `reason` (409 and 400 problem+json) maps to a plain message in one place. A new reason without a message is shown with a generic fallback and is logged. |
| FR-07 | The **model is configuration**: a LiteLLM model string (default `ollama_chat/gemma4:e4b`) plus declared capabilities: context window, structured-output support, thinking control, and cost per token. A feature may name its own model. No provider name appears in code. |
| FR-08 | Every LLM output is validated in code against its schema (and, for US-9, the catalog schemas), with one retry, whatever the model's own structured-output support. |
| FR-09 | **Sign-in is standard OIDC** (Authorization Code with PKCE in the browser). Locally the provider is `navikt/mock-oauth2-server` (`scripts/oidc-env.sh`), which signs tokens with its own keys (RS256). The funder API trusts only tokens from the configured issuer (`issuer-uri`) for the `funding-api` audience. Real single sign-on later changes configuration (issuer, and where roles come from), not code or surfaces. |
| FR-10 | Fresh data: after an action, and while the in-progress indicator is set, the agent refreshes the affected surfaces (`updateDataModel`) until the deal settles. |
| FR-11 | **Roles are enforced by the funder API** (Java), not only by the UI: every write checks the actor's role and the deal's assignment against AS-11.2. Every funder API call needs a valid bearer token (401 without one). The actor is the token's `sub`; the role comes from the API's user directory for that `sub` (`funding.auth.users`), never from a header the client can set. The agent relays the user's own token and never creates one. |
| FR-12 | The worklist endpoint (FR-05a) also filters by stip code and hold reason, and returns the hold reasons per row; hold reasons are derived by the API, not stored. |

## 6. What the funder sees per status

The status machine is spec 001's (§6). Allowed actions come from the API (FR-05b); this table is
the expected result, for reference:

| Status | Typical actions offered |
|---|---|
| PENDING_REVIEW (held) | re-run, approve (if no PENDING stips and the RISC is complete), add stip, reject, reassign |
| STIPULATED | satisfy or waive stips, add stip, re-run, reject, reassign |
| APPROVED | add stip and reject (not while a payment is in flight); retry payment (after a failed payment) |
| FUNDED, REJECTED, EXPIRED | none (read-only) |

## 7. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR-01 | Locally, a deal's overview renders in under 1 s after opening (fixed surfaces, no LLM). |
| NFR-02 | The assistant's summary answers within **15 s** on the default local model (thinking off), including a cold start; it shows "Summarizing…" meanwhile. Fixed surfaces never wait for the assistant. Speed isn't tuned further (owner, 2026-11-21: was 5 s at p95). |
| NFR-03 | **$0 by default:** the default configuration uses only the local model; no paid service is needed to build, test or use the dashboard. |
| NFR-04 | Accessibility: every interactive component has an `@accessibility` label; status and confidence are never shown by color alone. |
| NFR-05 | Every fixed surface is checked against the A2UI v1.0 JSON schemas and our catalog in automated tests. |
| NFR-06 | Funder API data reaches only the funder's browser and the configured model; with the default model, nothing leaves the laptop. |
| NFR-07 | Versions are pinned (`uv.lock`, `package-lock.json`): A2UI, ADK, the renderer and the models. |

## 8. Tests (how each story is proven)

- **Agent unit tests** (no services): surface builders produce schema-valid A2UI for recorded API data; action handlers call the right endpoint with the funder's id; reason → message mapping.
- **Java tests** (spec 001's style) for each FR-05 addition, with the contract kept in sync by `FunderOpenApiTest`.
- **Browser tests** against the real stack, one per AS where the browser matters: worklist → deal → waive → FUNDED (AS-3.2), non-waivable stip (AS-3.3), concurrent resolve (AS-3.4), refusals shown as messages (AS-6.4).
- **Assistant tests** replay recorded model responses; the A2UI eval harness runs offline (structure only) against the configured model when asked.

## 9. Decisions (owner, 2026-11-20/21)

| # | Decision |
|---|---|
| D-1 | Fixed screens are built by code; the LLM is only an assistant. Writes go through the funder API after a click. |
| D-2 | Agent in **Python** with ADK and `a2ui-adk` (the ADK integration is Python-only); the Java backend stays. |
| D-3 | **React** renderer, unless the spike finds its v1.0 support incomplete (then Lit or Angular). Transport: A2A, as `a2ui-adk` supports. |
| D-4 | Plain dashboard first; the assistant (US-8) and generated view (US-9) come last. **Exception:** "Summarize" (AS-8.1, with AS-8.4 and AS-8.5) comes right after the walking skeleton, as slice D1b, to prove the model path early. It's read-only, so it needs no permissions (owner, 2026-11-21). |
| D-5 | Field views first; the PDF page viewer waits for a document-storage decision. |
| D-6 | Local OIDC first (a mock provider in Docker); real single sign-on later, as configuration (FR-09). Replaces the earlier HMAC dev-token design (owner, 2026-11-21). |
| D-7 | **No paid LLM.** Local Gemma (`gemma4:e4b`) through Ollama is the default, and **the model stays swappable through configuration** (FR-07). |
| D-8 | Every PR updates `ui_summary.md`, `implementation_summary.md` and `README.md`. |
| D-9 | Worklist filters include **stip type** and **hold reason** as well as status, dealer and days to expiry. |
| D-10 | Three roles: **funder**, **income specialist** ("speciality funder", for verifying income) and **supervisor**, enforced by the funder API, with the permissions in AS-11.2 (confirmed 2026-11-21). A funder sends income work to the income queue and can't approve while it's pending. |
| D-11 | **Income work is done by income specialists or supervisors, never funders.** Supervisors can do everything (owner, 2026-11-21). |
| D-12 | "Expiring soon" means 5 days or fewer to expiry (configurable). |
| D-13 | The identity provider says **who** the user is (`sub`); the funder API decides **what they may do** (role from its user directory, permissions from AS-11.2). The local mock provider sends no role claim, so roles never depend on provider config. `X-Funder-Id` is removed, with no compatibility rule (owner, 2026-11-21). |
| D-14 | The assistant runs as an **ADK `LlmAgent`** through LiteLLM, chosen for learning (owner, 2026-11-21). Our own `AgentExecutor` still routes; ADK runs only for assistant requests. Answers are checked by our code, not by ADK's `output_schema` (local Gemma refuses forced JSON, a2ui-notes §12b). |

**Open (owner):** none.
