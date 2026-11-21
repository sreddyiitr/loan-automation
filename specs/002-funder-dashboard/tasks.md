# Tasks 002: Funder Dashboard

Ordered work for [`spec.md`](spec.md) and [`plan.md`](plan.md). Each slice is a PR. The spec 001
suites (`make test-unit`, `make test-functional`) stay green in every slice, and every PR updates
`ui_summary.md`, `implementation_summary.md` and `README.md` (D-8). Test names follow `AS_x_y_…`.

## D0: Spike (2–3 days, a throwaway branch)

**Proves:** the stack works end to end before the plan depends on it.
**Exit:**
- versions pinned in `plan.md` §1;
- every **(spike)** item in `plan.md` confirmed or replaced;
- a short findings note added to `a2ui-notes.md`.

- [x] **T001** Agent: an ADK app with `a2ui-adk[a2a]` serving one fixed surface (a hard-coded overview card) over A2A, with no LLM.
- [x] **T002** Web: React + the A2UI React renderer showing that surface; record the renderer package, its version and its v1.0 support.
- [x] **T003** One custom component (`DataTable`) in our catalog, rendered from the agent.
- [x] **T004** One action round trip: a button event goes to the agent's `before_agent_callback`, which updates the data model, and the UI changes, with the LLM never running.
- [x] **T005** The same overview built from the real funder API (`GET /applications/LA-1006`).
- [x] **T006** Findings: the transport, renderer, custom-component registration and pitfalls go into `a2ui-notes.md`; any plan change goes into `plan.md`.

### D0 notes (done 2026-11-21)

- **Kept, not thrown away.** The spike's code is small and became the skeleton under
  `dashboard/agent` and `dashboard/web`, with a Playwright test of the whole round trip. D2
  restructures it into the plan's layout.
- **Verified end to end:**
  1. A2A request.
  2. A v1.0 surface built by code, rendered by `@a2ui/react` 0.15.0.
  3. Custom `DataTable`.
  4. A click as a v1.0 `action`.
  5. A funder-API write (`PUT /assignee`).
  6. A re-read; the write shows in the audit trail.
- **Plan changes:**
  - routing in our own `AgentExecutor`, not ADK;
  - A2A JSON-RPC through a Vite proxy;
  - one catalog per surface (ours extends the basic one);
  - versions pinned.

  All in `plan.md`; details and pitfalls in `a2ui-notes.md` §12d.
- **Found in passing:** README step 6 named the stip id `stip_id`, but the API returns camelCase
  (`stipId`). Fixed.

## D1: Roles and identity (backend first)

**Proves:** writes carry a verified user and role, and the API enforces AS-11.2.
**Exit:** AS-11.1–11.6 and AS-1.1 (backend part).

- [ ] **T101** Test: `PermissionsTest` covers every cell of the AS-11.2 table, plus `NOT_ASSIGNED_TO_YOU`, `INCOME_WORK_NEEDS_SPECIALIST` and `ROLE_NOT_ALLOWED`.
- [ ] **T102** `Permissions` in `funding-domain`; income work derived from stips and results.
- [ ] **T103** Dev token: `POST /api/v1/dev/token` (local only), a Spring filter that verifies it, and the `X-Funder-Id` → SUPERVISOR compatibility rule, local only.
- [ ] **T104** `FunderService` checks `Permissions` before the existing guards and returns 403 problem+json with a reason. The role goes into the audit `after_state` (AS-11.4).
- [ ] **T105** `POST /versions/{vid}/income-queue` (AS-11.5), with tests.
- [ ] **T106** Test: `FunderApiTest` additions, `AS_11_3_…`, `AS_11_6_…`. The spec 001 suites stay green unchanged, through the compatibility rule.

## D2: Walking skeleton (open a deal, overview tab)

**Proves:** sign-in → deal → overview → one write, through the real stack.
**Exit:** AS-1.1, AS-1.5, AS-2.1, AS-2.3, AS-2.5, AS-10.1–10.3.

- [ ] **T201** Overview additions: `expiresAt`, `inProgress`, `payment`, `allowedActions` (from `Permissions`), with Java tests.
- [ ] **T202** Agent: the API client (token, reason → message), the `deal_<id>_overview` builder and version history; schema-validated unit tests.
- [ ] **T203** Web: dev sign-in (id and role), open by loan id, the overview tab; `StatusBadge`.
- [ ] **T204** Take an unassigned deal (assign to self) as the first write, with confirmation and refresh.
- [ ] **T205** `make dashboard`, the preflight script and the README section.
- [ ] **T206** Playwright: `AS_1_1_…`, `AS_1_5_…`, `AS_2_1_…`, `AS_10_3_…`.

## D3: Stipulations tab

**Exit:** AS-3.1–3.5, AS-6.4 (for stip actions).

- [ ] **T301** `GET /api/v1/stip-codes`, with a test.
- [ ] **T302** The stips surface (`DataTable`), satisfy and waive with a reason, Waive hidden when not waivable, add stip from the code list.
- [ ] **T303** `messages.yaml` covering every funder API reason (FR-06), and a unit test that fails when a known reason has no message.
- [ ] **T304** Playwright: `AS_3_2_…` (waive LA-1006 → FUNDED), `AS_3_3_…`, `AS_3_4_…` (two browsers race), `AS_3_5_…`.

## D4: Results and mismatches tab

**Exit:** AS-4.1–4.5.

- [ ] **T401** Comparison attribution (`documents`, `fields`) in the rule results, plus `GET /settings/ui`. Check that `make seed-check` stays green (no rule or outcome change).
- [ ] **T402** The results surface: per-check outcome, expected vs actual, side by side, `ConfidenceTag`, ERROR with re-run, attempt history.
- [ ] **T403** Tests: unit (schemas) and Playwright `AS_4_2_…`, `AS_4_4_…`.

## D5: Decisions

**Exit:** AS-2.2, AS-6.1–6.4, FR-10.

- [ ] **T501** Approve, reject, re-run (pick checks, ERROR by default) and retry payment, each with confirmation, from `allowedActions`.
- [ ] **T502** Freshness: poll while `inProgress` and push `updateDataModel` until the deal settles.
- [ ] **T503** Playwright: approve LA-1016 over ERROR → FUNDED (as an income specialist); reject; retry payment on LA-1030; every refusal shown as a message.

## D6: Worklist and queues

**Exit:** AS-1.2–1.4, AS-1.6, AS-1.7, FR-12.

- [ ] **T601** Test: hold reasons and every filter on the seeded scenarios (Java, Testcontainers).
- [ ] **T602** `GET /api/v1/applications` with the queues, filters, sort and paging.
- [ ] **T603** The worklist surface: my queue, unassigned, income queue, expiring soon; filters for status, stip type, hold reason, dealer and days to expiry.
- [ ] **T604** Send to the income queue from a deal (funder), and pick it up from the income queue (specialist).
- [ ] **T605** Playwright: filters, `AS_1_6_…`, the income-queue round trip.

## D7: Documents and audit

**Exit:** AS-5.1–5.3, AS-7.1–7.2.

- [ ] **T701** The documents surface: versions, fields with `ConfidenceTag`, v1-vs-v2 differences (computed in the agent).
- [ ] **T702** The audit surface, newest first, with system changes labelled and the role shown.
- [ ] **T703** Tests: unit and Playwright (LA-1029 shows RISC v1 and v2).

## D8: Assistant

**Exit:** AS-8.1–8.5, NFR-02.

- [ ] **T801** `models.yaml` and the model registry (FR-07); a unit test that switching models needs no code change.
- [ ] **T802** Summary, suggest and draft stip: prompts, schemas, fence stripping, validate and one retry, suggestion filtering.
- [ ] **T803** Record the reference responses from local Gemma; unit tests replay them; tests for "unavailable" and "invalid twice".
- [ ] **T804** `make eval-assistant` (offline eval plus reference answers) and a measured p95 for NFR-02.

## D9: Generated view (learning)

**Exit:** AS-9.1–9.3.

- [ ] **T901** "Ask for a view": the LLM writes A2UI (`express` format or a catalog subset), validated against both catalogs, read-only.
- [ ] **T902** Recorded responses, plus a test that an invalid view is never shown.
- [ ] **T903** Findings on how well local Gemma writes A2UI go into `a2ui-notes.md`.

## Traceability (stories → slices)

| Story | Slice(s) | | Story | Slice(s) |
|---|---|---|---|---|
| US-1 Worklist | D2, D6 | | US-7 Audit | D7 |
| US-2 Overview | D2, D5 | | US-8 Assistant | D8 |
| US-3 Stipulations | D3 | | US-9 Generated view | D9 |
| US-4 Results | D4 | | US-10 Developer setup | D2 (and every slice) |
| US-5 Documents | D7 | | US-11 Roles | D1 (and every write) |
| US-6 Decisions | D5 | | | |
