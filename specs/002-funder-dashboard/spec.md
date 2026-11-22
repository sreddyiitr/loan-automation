# Feature Spec 002: Funder Dashboard (ADK + A2UI)

| | |
|---|---|
| **Status** | Draft for owner review, 2026-11-21. Decisions already made are in §9. |
| **Source** | `auto_funding_tasks.md` §5 (the four tabs), [`capabilities.md`](capabilities.md), spec 001's funder API |
| **Builds on** | Spec 001: the funder API ([`contracts/funder-api.openapi.yaml`](../../contracts/funder-api.openapi.yaml)), its status machine (spec 001 §6) and its seeded scenarios |
| **Scope** | A funder dashboard: an A2UI v1.0 web client, a Python ADK agent service, a custom component catalog, the funder-API additions the UI needs, a local LLM assistant, and a one-command developer setup |
| **Out of scope** | Editing extracted values, uploading documents, messaging dealers, bulk actions, real single sign-on (a local OIDC provider stands in), document storage and the PDF page viewer (a later spec), AWS deployment |
| **Reference** | [`a2ui-notes.md`](a2ui-notes.md) (the protocol and ADK, read 2026-11-20), [`learnings/ui_summary.md`](../../learnings/ui_summary.md) |

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
| **Funder** | Anyone who works deals: one of the five specialists, the catch-all funder, or the supervisor. Signs in, works their queue, reviews deals, resolves stips, decides (US-11 sets what each may do). |
| **Specialist** | A funder who works one **kind of work** (D-15): GAP, income, RISC, documents or payment. The **income specialist** also works deals that need more than one kind. |
| **Catch-all funder** | Works only what no specialist owns (the `GENERAL` kind, D-17): e.g. a manual stip that names no check. |
| **Supervisor** | Oversees every queue and assigns and reassigns work. **Can do everything**. Gets the assistant's suggestions for assigning unassigned deals (US-12). |
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
- **Kind of work:** what a deal needs a human for, derived by the API from its pending stips,
  current results, holds and payment (D-15):

  | Kind | Comes from |
  |---|---|
  | `GAP` | a pending stip of the GAP check (`STIP_GAP_FORM_MISMATCH`, `STIP_LENDER_MISMATCH`); a review of a GAP agreement field; the GAP check in ERROR |
  | `INCOME` | a pending stip of the INCOME check (`STIP_INCOME_VARIANCE_EXCEEDED`, `STIP_PROOF_OF_INCOME`); the INCOME check in NEEDS_REVIEW or ERROR |
  | `RISC` | a pending stip of the TILA check (`STIP_TILA_MATH_DISCREPANCY`); a review of a RISC field; the TILA check in ERROR; an incomplete RISC |
  | `DOCUMENTS` | a pending stip of the PRICE or VIN check (`STIP_PRICE_MISMATCH`, `STIP_VIN_MISMATCH`); a review of a Buyer's Order field; the PRICE or VIN check in ERROR |
  | `PAYMENT` | an APPROVED deal whose payment failed or whose outcome is unknown |
  | `GENERAL` | anything no row above owns: a manual stip that names no check, or a stip code or hold this table doesn't map (so new work never lands with nobody) |

  A field review (`STIP_EXTRACTION_REVIEW_REQUIRED`) counts by the document the field is on. A
  manual stip counts toward the kind of the check it names; one that names no check is `GENERAL`.
- **Routing:** who may work a deal, from its kinds. **One kind** → that kind's specialist (for
  `GENERAL`, the catch-all funder). **Two or more kinds**, `GENERAL` included → the income
  specialist, who may then work every stip on it. **No kind** (nothing needs a human) → nobody but
  the supervisor. The supervisor may work any deal. Routing is
  recomputed whenever stips, results or payment change; it's derived, not stored.
- **Role:** `GAP_SPECIALIST`, `INCOME_SPECIALIST`, `RISC_SPECIALIST`, `DOCUMENTS_SPECIALIST`,
  `PAYMENT_SPECIALIST`, `GENERAL_FUNDER` (the catch-all) or `SUPERVISOR`. The funder API looks it up from the signed-in user's id in
  its user directory, and enforces it.
- **Assistant answer:** small structured JSON from the LLM, `{summary, suggestions[{action,
  reason}]}`, validated and then rendered by code.

## 4. User Stories & Acceptance Scenarios

### US-1 Sign in and worklist (P1)
- **AS-1.1** A user signs in through the local OIDC provider's login page by typing a user id; there's no password, and this is local only. The funder API gives that user their role (one of the five specialists, the catch-all funder, or `SUPERVISOR`) from its user directory. Every request is then made under that id and role, with no identity typed by hand. An id that isn't in the directory is refused with 403 `UNKNOWN_USER`.
- **AS-1.2** **My queue** lists the latest versions assigned to the user that need a human:
  - STIPULATED;
  - PENDING_REVIEW held on an ERROR or an incomplete RISC;
  - APPROVED with a failed or unknown payment.

  Each row shows the loan id, dealer, status, **hold reasons**, the open stip codes, days to expiry and whether something is in progress.
- **AS-1.3** **Unassigned** lists the same kinds of deals with no funder: for a specialist, those routed to them; for the supervisor, all of them. **Expiring soon** lists deals with 5 days or fewer to expiry (the threshold is configurable).
- **AS-1.4** The queue can be filtered by:
  - status;
  - **stip type** (one or more stip codes, e.g. `STIP_PRICE_MISMATCH`);
  - **hold reason** (e.g. `CHECK_ERROR`, `PAYMENT_FAILED`);
  - dealer;
  - days to expiry.

  It can be sorted by days to expiry (the default) or age. The filters combine: e.g. "STIPULATED with a pending `STIP_PROOF_OF_INCOME`, expiring within 5 days".
- **AS-1.5** Opening a row opens that deal. Opening a loan id that doesn't exist shows "not found", not an error page.
- **AS-1.6** Terminal deals (FUNDED, REJECTED, EXPIRED) and superseded versions never appear in a queue, but can be opened by id.
- **AS-1.7** Each row shows the deal's **kinds of work** and who it's routed to. A specialist's **specialty queue** lists every deal routed to them, assigned or not; the supervisor sees every specialty's queue.

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
- **AS-2.7** The overview has a **What's been done** section, so a funder sees what the system and other people have already handled:
  - **Checks:** each of TILA, PRICE, VIN, GAP and INCOME with its current outcome in words (passed, failed, needs review, error, or not run yet), its attempt and when it finished;
  - **Activity:** the deal's milestones, newest first, each in plain words with who did it (a person, the system, or the dealer's submission): submitted, checks finished, stips raised and resolved (with the note), assignments, re-runs, approval, payment and funding. Back-to-back assignment changes show as one line ("taken by funder-2, after 3 changes"). At most 8 lines; older ones are in the full audit trail (US-7).

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

  "Routed to them" is the deal's routing (§3): its one kind's specialist (the catch-all funder for
  `GENERAL`), or the income specialist when it has two or more kinds. The catch-all funder has the
  specialist's column.

  | Action | Specialist | Supervisor |
  |---|---|---|
  | Queues | My queue; their specialty queue (routed to them, assigned or not) | All queues, any user's queue |
  | Take an unassigned deal (assign to self) | ✅ if it's routed to them | ✅ any |
  | Satisfy or waive a stip, add a stip, re-run a check, approve, reject | ✅ if it's routed to them **and** assigned to them | ✅ any deal |
  | Retry a payment | ✅ the payment specialist, on a deal assigned to them | ✅ |
  | Reassign a deal to another user | ❌ | ✅ |
  | Assignment suggestions (US-12) | ❌ | ✅ |

  The status rules still apply on top (spec 001 §6), e.g. no approve while a stip is pending.
- **AS-11.3** A write the role doesn't allow is refused by the API with **403** and a reason (`ROLE_NOT_ALLOWED`, `NOT_YOUR_SPECIALTY`, `NOT_ASSIGNED_TO_YOU`). The UI never offers it (AS-6.1), and shows the reason if it's attempted anyway, e.g. from a stale screen.
- **AS-11.4** The audit trail records the actor's **role** with each change.
- **AS-11.5** **A deal keeps its assignee when its routing changes.** Example: an income specialist works a deal with a GAP stip and an income stip, and clears the income stip; the deal is now routed to the GAP specialist. The assignee can still read it but no longer act (`NOT_YOUR_SPECIALTY`), and the deal appears in the supervisor's **needs reassignment** list until the supervisor reassigns it.
- **AS-11.6** **Specialists stay in their lane.** A specialist acting on a deal routed to someone else gets 403 `NOT_YOUR_SPECIALTY`: e.g. the GAP specialist can't waive a price stip, and on a deal with two or more kinds only the income specialist (and the supervisor) may act.
- **AS-11.7** Locally the user directory has `gap-1`, `income-1`, `risc-1`, `docs-1`, `payment-1` (one per specialty), `general-1` (the catch-all) and `supervisor-1`. Seeded deals cover every specialist kind and the two-or-more-kinds case; tests cover `GENERAL` (a manual stip with no check).

### US-12 Assignment suggestions for the supervisor (P2, after D2)
- **AS-12.1** On the unassigned list, the supervisor clicks **Suggest assignments**. The assistant proposes an assignee for each unassigned deal, most urgent first, as many as fit the model's window, each with a one-line reason (e.g. "LA-1012 → income-1: income variance; income-1 has the fewest open deals").
- **AS-12.2** **Code decides who may work a deal; the model only chooses among them.** Eligibility is the deal's routing (§3, AS-11.2). A suggestion naming anyone else is dropped by code, like AS-8.2.
- **AS-12.3** Within eligibility the model weighs days to expiry, each user's open deals, and who worked an earlier version of the deal, and says which mattered.
- **AS-12.4** **Every suggestion needs the supervisor's click.** Each has its own **Assign** button with a confirmation, and confirming is the normal audited reassign (AS-2.4). There's no "apply all" (bulk actions stay out of scope).
- **AS-12.5** With no model available, code's own plan is shown instead, marked as such: most urgent first, each deal to the eligible user with the fewest open deals.
- **AS-12.6** Only the supervisor sees it; the API refuses anyone else (`ROLE_NOT_ALLOWED`). The model gets only loan ids, kinds of work, days to expiry and earlier assignees, and users' ids, specialties and open-deal counts: no names, amounts or documents.
- **AS-12.7** It runs as an **ADK agent with read-only tools** (unassigned deals with their kinds and routing; users with their specialty and workload; who may work a deal), each calling the funder API with the supervisor's token. The agent has no write tool.

## 5. Functional Requirements

| ID | Requirement |
|---|---|
| FR-01 | The UI is rendered from **A2UI v1.0** messages (`version: "1.0"`). Component IDs follow UAX #31: underscores, no hyphens. No `theme`: styling belongs to the client. |
| FR-02 | **Fixed surfaces** (worklist; deal overview, stips, results, documents, audit) are built by agent code from funder API data, never by the LLM. |
| FR-03 | **Every write** (satisfy, waive, add stip, reassign, approve, reject, re-run, retry payment) is a funder click that goes to agent code, which calls the funder API with the signed-in funder's id. The LLM can't trigger a write. |
| FR-04 | The **custom catalog** has its own `catalogId` and the components the basic catalog lacks: `DataTable`, `StatusBadge` and `ConfidenceTag`. They're implemented in the React renderer. |
| FR-05 | **Funder API additions** (Java, spec 001's style, added to `contracts/funder-api.openapi.yaml`): a. A worklist endpoint, `GET /api/v1/applications`, filtered by status, assignee (`me`, `none`, an id), queue (`specialty`, `needs-reassignment`), work kind, stip code, hold reason, dealer and days to expiry (FR-12), and sortable. b. On the overview: `expiresAt`, an in-progress indicator, payment status, and `allowedActions` (each with an allowed flag and a reason code). c. A stip-code list with descriptions. d. Structured document attribution on each comparison (which document each side came from). e. The confidence cut-off. |
| FR-06 | Each funder API `reason` (409 and 400 problem+json) maps to a plain message in one place. A new reason without a message is shown with a generic fallback and is logged. |
| FR-07 | The **model is configuration**: a LiteLLM model string (default `ollama_chat/gemma4:e4b`) plus declared capabilities: context window, structured-output support, thinking control, and cost per token. A feature may name its own model. No provider name appears in code. |
| FR-08 | Every LLM output is validated in code against its schema (and, for US-9, the catalog schemas), with one retry, whatever the model's own structured-output support. |
| FR-09 | **Sign-in is standard OIDC** (Authorization Code with PKCE in the browser). Locally the provider is `navikt/mock-oauth2-server` (`scripts/oidc-env.sh`), which signs tokens with its own keys (RS256). The funder API trusts only tokens from the configured issuer (`issuer-uri`) for the `funding-api` audience. Real single sign-on later changes configuration (issuer, and where roles come from), not code or surfaces. |
| FR-10 | Fresh data: after an action, and while the in-progress indicator is set, the agent refreshes the affected surfaces (`updateDataModel`) until the deal settles. |
| FR-11 | **Roles are enforced by the funder API** (Java), not only by the UI: every write checks the actor's role and the deal's assignment against AS-11.2. Every funder API call needs a valid bearer token (401 without one). The actor is the token's `sub`; the role comes from the API's user directory for that `sub` (`funding.auth.users`), never from a header the client can set. The agent relays the user's own token and never creates one. |
| FR-12 | The worklist endpoint (FR-05a) also filters by stip code and hold reason, and returns the hold reasons, **kinds of work** and **routing** per row; all three are derived by the API, not stored. |
| FR-13 | For US-12, the funder API adds `GET /api/v1/users` (supervisor only): each user's id, role and open-deal count. The worklist rows (FR-12) carry each deal's earlier assignees. |

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
| D-8 | Every PR updates `learnings/ui_summary.md`, `learnings/implementation_summary.md` and `README.md`. |
| D-9 | Worklist filters include **stip type** and **hold reason** as well as status, dealer and days to expiry. |
| D-10 | *Superseded by D-15.* Three roles: **funder**, **income specialist** ("speciality funder", for verifying income) and **supervisor**, enforced by the funder API, with the permissions in AS-11.2 (confirmed 2026-11-21). A funder sends income work to the income queue and can't approve while it's pending. |
| D-11 | *Superseded by D-15.* **Income work is done by income specialists or supervisors, never funders.** Supervisors can do everything (owner, 2026-11-21). |
| D-12 | "Expiring soon" means 5 days or fewer to expiry (configurable). |
| D-13 | The identity provider says **who** the user is (`sub`); the funder API decides **what they may do** (role from its user directory, permissions from AS-11.2). The local mock provider sends no role claim, so roles never depend on provider config. `X-Funder-Id` is removed, with no compatibility rule (owner, 2026-11-21). |
| D-14 | The assistant runs as an **ADK `LlmAgent`** through LiteLLM, chosen for learning (owner, 2026-11-21). Our own `AgentExecutor` still routes; ADK runs only for assistant requests. Answers are checked by our code, not by ADK's `output_schema` (local Gemma refuses forced JSON, a2ui-notes §12b). |
| D-15 | **Five specialists and a supervisor** (owner, 2026-11-21), replacing D-10 and D-11. Each deal's kind of work routes it: GAP, income, RISC (TILA and RISC fields), documents (price and VIN) or payment, each to its own specialist. A deal with two or more kinds goes to the income specialist. The supervisor can do everything. "Send to the income queue" is gone, because routing is automatic. Enforced by the funder API (US-11), built in D2. |
| D-16 | **Assignment suggestions** (US-12, owner, 2026-11-21): code decides who may work a deal, the LLM ranks and explains within that, and the supervisor confirms each suggestion. An ADK agent with read-only tools. Built after D2, as slice D2b. |
| D-17 | **A catch-all funder** (`general-1`, role `GENERAL_FUNDER`; owner, 2026-11-21) works only what no specialist owns: the `GENERAL` kind (a manual stip with no check, or a stip code or hold the kinds table doesn't map). It counts toward "two or more kinds" like any other kind. |
| D-18 | **The look: clean fintech, light** (owner, 2026-11-21; made richer the same day): a navy top bar and hero band holding the loan search, white cards lifted over it with a blue edge, key facts in tinted tiles, the assistant in its own indigo colour with a ✦, status colours that always come with words (NFR-04); dark mode follows the OS. Styled in the web app only (A2UI v1.0 has no themes); the agent sends structure. Built as slice D1c, before D2. |

**Open (owner):** none.
