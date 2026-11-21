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
  `dashboard/agent` and `dashboard/web`, with a Playwright test of the whole round trip. D1
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

## D1: Walking skeleton (sign in, open a deal, overview tab)

**Proves:** OIDC sign-in → agent → deal → overview → one write, through the real stack, made by a
verified user. Roles are only looked up here; D2 enforces them.
**Exit:** AS-1.1, AS-1.5, AS-2.1, AS-2.3, AS-2.5, AS-10.1–10.3, AS-10.9 (spec 001, now bearer tokens).

- [x] **T101** OIDC: the funder API as an OAuth2 Resource Server (`issuer-uri` from `OIDC_ISSUER_URI`, audience `funding-api`), `UserDirectory` from `funding.auth.users` (401 / 403 `UNKNOWN_USER`), and `scripts/oidc-env.sh` sourced by `make run` and `make test-functional`. `X-Funder-Id` is removed from the controllers and the contract. Check the `aud` of a code-flow token from the mock provider (plan §10).
- [x] **T102** Spec 001 moves to tokens, in the same change as T101: `jwt()` in MockMvc; a `client_credentials` token helper for `FunderActionsE2EIT`, `VersioningE2EIT` and `WorkerRestartIT`; no assertion changed. `AS_10_9_…` covers 401 without a token and 403 `UNKNOWN_USER`. README `curl` examples get a token first.
  **T101–T102 notes (done 2026-11-21):**
  - Every funder API call needs a token (401 `UNAUTHORIZED`). An unlisted user gets 403 `UNKNOWN_USER`.
  - `FunderAuthTest` (5) runs the real security chain with `jwt()`. The functional
    `AS_10_9_realTokensFromTheMockProvider` tries no token, a garbage token, another issuer, the
    wrong audience, an unknown user and a known user.
  - Spec 001's assertions are unchanged. `FunderApiTest` signs its user in as SUPERVISOR (D-11).
  - The contract drops `X-Funder-Id` and marks all 14 operations `bearerAuth`.
  - The dev endpoints keep their optional `X-Funder-Id` audit label.
  - Fixed on the way: `InfraBootstrap` now looks a queue up before creating it, so a second
    `make run` on the same emulator no longer fails ("a different value for attribute RedrivePolicy").
  - The spike agent fetches a token for `funder-1` until T104 relays the signed-in user's.
  - **Still open:** the `aud` of a browser code-flow token (plan §10). It moves to T105, which
    builds the browser sign-in.
- [x] **T103** Overview additions: `expiresAt`, `inProgress`, `payment`, with Java tests. (`allowedActions` comes in D2, from `Permissions`.)
  **Notes (done 2026-11-21):**
  - Also added `workflowId` (for the Temporal UI link, AS-2.5) and `workflowStep`. The exact rules are in plan §5.
  - Four tests in `FunderApiTest`:
    - `AS_2_1_overviewOfADraft`;
    - `AS_2_1_overviewShowsExpiryWorkflowAndPayment`;
    - `AS_2_2_inProgressWhileTheWorkflowIsBusy`;
    - `AS_2_1_paymentStates`: all five states, and no deadline once paid.
  - The overview runs one workflow query. With no run, for example after a Temporal dev-server
    restart, `workflowStep` is `null` and `inProgress` is `false`.
  - The funder contract describes these responses as plain maps, so `FunderOpenApiTest` doesn't
    see the new fields.
- [x] **T104** Agent: the API client (relays the user's token, reason → message), the `deal_<id>_overview` builder and version history; schema-validated unit tests.
  **Notes (done 2026-11-21):**
  - The agent is now the `funder_agent` package, in the plan §2 layout: `api/`, `surfaces/`,
    `actions/`, `catalog.py`, `server.py`. `main.py` only starts it.
  - It reads `Authorization: Bearer` from the A2A request and relays it, so the spike's own token
    is gone. Missing token → a `notice` surface ("Sign in…"). Refusal → the reason's message from
    `messages.yaml`, or a logged fallback. The other reasons come in T303.
  - **NFR-05 validation** uses `a2ui-core` 0.7.0's strict `MessageProcessor` with our catalog (the
    Basic Catalog plus `DataTable`), not vendored JSON schemas. It makes the renderer's own checks
    (it rejects `Text.variant: h2` with the same message), and a test proves it really rejects.
  - **Deviation:** the agent-side catalog is defined in `catalog.py`, not yet as
    `dashboard/catalog/funder_catalog.json`.
  - The overview's texts are formatted in code (`deal_model`), so they're unit-tested: days to
    expiry, the five payment states, "superseded by vN", the Temporal URL. The stips table left
    the overview; stips are D3's tab.
  - Web: `a2a.ts` sends the token from `src/auth.ts`, which holds it in memory only. Until T105,
    the token comes from Playwright, or from `#access_token=` in the dev server, cleared from the
    address bar at once.
  - Tests: 32 agent tests (`uv run pytest`) and 2 Playwright tests. The Playwright overview test
    first assigns LA-1006 to `funder-2`, so Take visibly changes it.
  - `httpx` and `pyyaml` are declared explicitly now, and `pytest` is a dev dependency.
- [x] **T105** Web: OIDC sign-in (Authorization Code with PKCE against the mock provider; token in memory, sent to the agent as a bearer), open by loan id, the overview tab; `StatusBadge`.
  **Notes (done 2026-11-21):**
  - **The open `aud` question, settled.** By default, a browser-login access token from
    `mock-oauth2-server` 6.1.5 has `aud=default`, whatever the scope. `audience=` and `resource=`
    don't change it.
  - **The fix is provider config, not a weaker API:** `scripts/oidc-config.json` (as
    `JSON_CONFIG`) gives the dashboard's client and `scope=funding-api` tokens
    `aud=funding-api`. That's the local version of Auth0's `audience`.
  - `oidc-env.sh` starts its own container with the config and refuses a reused one without it
    (a one-request probe). The owner's `funding-oidc` was recreated with it.
  - **Web sign-in:** `oidc-client-ts` 3.5.0 (pinned). The access token is in memory
    (`InMemoryWebStorage`); only the PKCE verifier and state sit in sessionStorage for the round
    trip.
  - The callback is handled once per page load. StrictMode's double effect had exchanged the
    code twice, and the provider refused the second exchange with a 400.
  - **Stand-ins removed:** `window.__FUNDER_TOKEN__` and the `#access_token=` dev stand-in are gone.
  - **Shell:** sign-in screen → "Signed in as <id>" with sign out, plus open by loan id. Only the
    surfaces of the last opened deal are shown, plus any notice an action brings back.
  - **`StatusBadge`** is in both catalogs, written out with the colour only grouping (NFR-04).
    The overview uses it.
  - **Tests:** 33 agent tests. 4 Playwright tests, each signing in on the real login page:
    overview and Take, unknown loan, unknown user, signed out and sign out.
  - **Not done here:** a tab bar. The overview is the only tab until D3 adds the next one.
  - **Not done here:** fixing `npm audit`'s 4 high and 1 moderate findings. They're all in the
    A2UI packages pinned at D0 (`@a2ui/react`, `@a2ui/web_core`, `@a2ui/markdown-it` via
    `dompurify`) and in `zod`, not in `oidc-client-ts`.
- [x] **T106** Take an unassigned deal (assign to self) as the first write, with confirmation and refresh. Any user in the directory may take one here; D2 adds the role rule (AS-11.2).
  **Notes (done 2026-11-21):**
  - **Spec first:** a new AS-2.6. AS-2.4 and spec 001's AS-10.3 also allow unassigning with
    `funderId: null`.
  - **API:** `POST /versions/{vid}/take`, under the row lock:
    - already yours → 200, unchanged and not audited;
    - someone else's → 409 `ALREADY_ASSIGNED`;
    - otherwise audited `ASSIGNEE_CHANGED` with reason `TAKEN`.
    `PUT …/assignee` stays the reassign and now accepts `null`. A blank id is still a 400.
    Five tests in `FunderApiTest`; the contract is regenerated.
  - **Agent:**
    - Take is offered only on an unassigned, open deal.
    - It's the `trigger` of a Basic Catalog `Modal`, and the confirm button sends `deal.take`.
      The trigger's own event is `ui.confirm_take`, and the agent ignores every `ui.` event.
    - After the call it rebuilds the surface (`deleteSurface`, then create), which closes the
      Modal and drops Take.
    - On a refusal it also rebuilds from a re-read, and adds the reason's notice.
    - Your own deal reads "funder-1 (you)".
  - **Web:** `apply()` keeps a `createSurface` that follows a `deleteSurface` of the same id.
  - **Pitfall found (a2ui-notes §12d):** `web_core` 0.15.0 rejects `@accessibility`, but
    `a2ui-core` accepts it. An agent test now checks for both reserved keys.
  - **Tests:** 46 agent, 6 Playwright, plus 5 Java.
    - The Playwright setup unassigns LA-1006 through the API, so runs repeat without adding
      versions.
    - My first setup made a new version per run, and a stray dev call made one more. Together
      they left LA-1006 at v11 locally, with v2 an unsubmitted draft. No data was lost; the
      history is just longer.
- [x] **T107** `make dashboard`, the preflight script and the README section.
  - `scripts/dashboard-preflight.sh`: each missing tool with its install command; Ollama only warns.
  - `scripts/dashboard-run.sh`: reuse first (a part is reused only if it answers as ours), then
    starts the API, agent and web app, and submits the seeded DRAFTs (34 on a fresh database).
  - Playwright now reuses a running web app (`reuseExistingServer: true`).
  - **Bug found and fixed:** `Ctrl+C` left the API up on 8080, because killing the port's
    listener didn't stop the Maven `spring-boot:run` wrapper. The script now lists each started
    process tree first, sends TERM, waits up to 20 s, then KILLs. Verified under a pty with a
    real `Ctrl+C`: all three ports free, no leftover processes, in under 2 s.
- [ ] **T108** Playwright: `AS_1_1_…`, `AS_1_5_…`, `AS_2_1_…`, `AS_10_3_…`.

## D2: Roles and permissions

**Proves:** the API enforces AS-11.2, and the overview offers only the actions it allows.
**Exit:** AS-11.1–11.6.

- [ ] **T201** Test: `PermissionsTest` covers every cell of the AS-11.2 table, plus `NOT_ASSIGNED_TO_YOU`, `INCOME_WORK_NEEDS_SPECIALIST` and `ROLE_NOT_ALLOWED`.
- [ ] **T202** `Permissions` in `funding-domain`; income work derived from stips and results.
- [ ] **T203** `FunderService` checks `Permissions` before the existing guards (including Take from T106) and returns 403 problem+json with a reason. The role goes into the audit `after_state` (AS-11.4).
- [ ] **T204** `POST /versions/{vid}/income-queue` (AS-11.5), with tests.
- [ ] **T205** `allowedActions` on the overview, from `Permissions` (FR-05b); the overview surface shows only allowed actions.
- [ ] **T206** Test: `FunderApiTest` additions, `AS_11_3_…`, `AS_11_6_…`.

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
| US-1 Worklist | D1, D6 | | US-7 Audit | D7 |
| US-2 Overview | D1, D5 | | US-8 Assistant | D8 |
| US-3 Stipulations | D3 | | US-9 Generated view | D9 |
| US-4 Results | D4 | | US-10 Developer setup | D1 (and every slice) |
| US-5 Documents | D7 | | US-11 Roles | D1 (sign-in, directory), D2 (permissions), and every write |
| US-6 Decisions | D5 | | | |
