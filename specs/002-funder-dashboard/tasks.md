# Tasks 002: Funder Dashboard

Ordered work for [`spec.md`](spec.md) and [`plan.md`](plan.md). Each slice is a PR. The spec 001
suites (`make test-unit`, `make test-functional`) stay green in every slice, and every PR updates
`learnings/ui_summary.md`, `learnings/implementation_summary.md` and `README.md` (D-8). Test names follow `AS_x_y_…`.

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
- [x] **T108** Playwright: `AS_1_1_…`, `AS_1_5_…`, `AS_2_1_…`, `AS_10_3_…`.
  **Notes (done 2026-11-21):**
  - `e2e/spike.spec.ts` is now `e2e/walking-skeleton.spec.ts`, and every test is named
    `AS_x_y_…` (or `FR_11_…`). 10 tests, up from 6, all passing twice in a row against
    `make dashboard`.
  - **`AS_1_1`:** after Take through the browser, the audit's actor is the signed-in user. The
    unknown-user test moved under `AS_1_1` too.
  - **`AS_2_1`:** every fact (dealer, borrower, co-borrower, stated income, VIN, assignee,
    submitted, expiry, payment) is compared with the funder API's own overview, not hardcoded.
  - **Also covered,** since they're in D1's exit: `AS_2_3` (version history) and `AS_2_5` (the
    Temporal link opens the deal's workflow).
  - **`AS_10_3`, the half D1 can show:** every seeded scenario opens with the `expect.status` read
    from its `test/data/scenarios/*.yaml` (P-2). The other half, waiving LA-1006's price stip,
    needs the stips tab, so it's T304's `AS_3_2`.
    - It holds on a fresh database. A deal changed by hand (say, LA-1006 waived as in README
      step 6) fails it, by name. That's deliberate, not weakened.
    - **Heads-up for T304:** its waive test turns LA-1006 FUNDED, which would break this one on
      the next run. T304 needs a way to start from STIPULATED again (a resubmit makes a new
      version) or its own deal.

## D1b: Summarize this deal (the model path, read-only)

**Proves:** browser → agent → ADK `LlmAgent` → local Gemma → JSON checked by code → a panel drawn
by code, with no model needed for the tests. Moved up from D8 by D-4's exception; read-only, so it
doesn't wait for D2.
**Exit:** AS-8.1, AS-8.4, AS-8.5; FR-07, FR-08; NFR-02 for the summary; AS-10.4 and AS-10.5 for it.

- [x] **T151** Spec first: AS-8.1 (finished deals, read-only), D-4's exception, D-14 (ADK), plan
  §7, a2ui-notes §12b (`reasoning_effort="none"` verified; `output_schema` gets a 501).
- [x] **T152** `models.yaml` and the model registry (FR-07; was T801): a `LiteLlm` per feature,
  with `thinking` as a `reasoning_effort` level. Unit test: switching the model, or one feature's
  model, is a YAML edit with no code change.
  **Notes (done 2026-11-21):**
  - `dashboard/agent/models.yaml` (or `ASSISTANT_MODELS`) and `funder_agent/assistant/models.py`.
    `Registry.load().for_feature("summary").llm()` is the ADK `LiteLlm`, at temperature 0 so
    recorded answers replay.
  - **Pitfall:** the plan's `thinking: {off: none, on: medium}` loads as `{False: …, True: …}`
    (YAML 1.1 booleans). The keys are `thinking_off` / `thinking_on`; a test pins it.
  - Each model also names a `health` URL, so the AS-8.4 check stays out of code.
  - A broken file is refused with the entry named (7 cases). A test fails if a provider or model
    name appears in `funder_agent/**/*.py`.
  - `google-adk` 2.15.0 and `litellm` 1.110.2 added (pinned in `uv.lock`). Tests: 16 new, 62 agent
    tests in all.
- [x] **T153** The summary (was part of T802), in `funder_agent/assistant/`:
  - the prompt gets the overview, the open stips and the checks that didn't pass, as data;
  - an ADK `LlmAgent` on a throwaway session, thinking off;
  - strip the fence, validate `{summary}` (a non-empty string, a length cap), one retry that
    passes back the error (FR-08).
  **Notes (done 2026-11-21):**
  - `funder_agent/assistant/summary.py`: `summary_input` (what the model sees), `parse` (the
    check), `summarize` (one retry in the same conversation, so the data isn't sent twice) and
    `AdkConversation` (an ADK `LlmAgent` and `InMemoryRunner`, one session per request).
  - **The input is trimmed:** the deal's facts (income, expiry and payment formatted as the
    overview shows them), the names of the checks that passed, and only the failing comparisons
    of the current checks that didn't. Open and resolved stips are included. Names, VINs, ids and
    document ids are left out. Prompts dropped from ~2,000 tokens to **120–220**.
  - **Live, against local Gemma** (5 seeded deals, thinking off): all valid on the first try, in
    **0.8–1.4 s warm** (2.9 s cold). The summaries are factual, and no longer recommend actions:
    the instruction says the funder decides, which fixes the "documents must be corrected" advice
    (a2ui-notes §12b).
  - The instruction has no braces, because ADK reads `{name}` in an instruction as session state;
    the JSON format goes in the message instead. Hidden thinking parts aren't part of the reply.
  - Any model error (connection refused, 5xx, the 501) becomes "unavailable", with no retry.
  - The client gains `stipulations()` and `validation_results()`. LiteLLM's per-call "Provider
    List" banner is silenced.
  - Tests: 23 new (85 agent tests), 4 of them through a real ADK runner with a scripted model.
- [x] **T154** The `deal_<versionId>_assistant` surface: a **Summarize** button on the overview,
  and a panel drawn by code. It shows "Summarizing…", then the summary, "assistant unavailable"
  (Ollama's `/api/tags` fails, AS-8.4) or "couldn't produce an answer" (invalid twice). The
  overview never waits for it (NFR-02).
  **Notes (done 2026-11-21):**
  - **Streaming.** A2UI v1.0 has no local "set this text" action, so "Summarizing…" comes from
    the agent: `deal.summarize` replies over A2A `message/stream` as task status updates.
    "Summarizing…" goes out at once as a `working` update, and the panel with the answer completes
    the task. The agent card now says `streaming: true`, and the browser sends every action over
    `message/stream` (`streamToAgent`, applying each update as it arrives). A one-reply action,
    like Take, is one event. A plain `message/send` caller gets the final panel as the task's
    status message.
  - **Each update is the whole panel, rebuilt** (`deleteSurface`, then create, components and
    data). The strict validator refused a second `createSurface` of the same id. The browser
    skips duplicates, but a stricter renderer wouldn't, so the agent was fixed. Deleting a
    surface the browser hasn't got is accepted.
  - **The model's text is only data** in a fixed Card of Texts, shown as plain text. A test
    feeds it text shaped like a component with an action and checks it never reaches the layout
    (D-1). A disclosure under the answer says it was written by the assistant from this deal's
    data only.
  - `SummaryService` checks the model's `health` URL (1.5 s timeout) before calling it, so no
    model means "unavailable" quickly, with no model call. A funder API refusal mid-way removes
    the panel and shows the notice. `models.yaml` is read on first use, so screens never need it.
  - **Live in the browser** (LA-1012, LA-1006, LA-1001): "Summarizing…" within 30 ms; the answer
    in **1.3–1.8 s warm**, but **8.9 s cold**. Ollama unloads the model after 5 idle minutes. Within
    NFR-02's 15 s (T157).
  - Tests: 11 new (96 agent tests), 3 of them through the real A2A app over HTTP, including
    `message/stream` order; 10 Playwright still pass with actions on the stream.
- [x] **T155** Recorded answers (AS-10.5; was part of T803): record local Gemma's summaries of the
  seeded deals once (`make record-assistant`), and replay them in pytest and, through
  `ASSISTANT_REPLAY`, in Playwright. Tests: valid, invalid once then valid, invalid twice,
  unavailable, and AS-8.5 (the prompt holds only this deal's data: no other loan id, no SSN).
  **Notes (done 2026-11-21):**
  - `make record-assistant` (`funder_agent/assistant/record.py`) records 7 deals, one per kind of
    story: LA-1006 price, LA-1007 VIN, LA-1012 income, LA-1016 an ERROR hold, LA-1023 low
    confidence, LA-1030 approved, LA-1001 funded. Each file in
    `dashboard/agent/recordings/summary/` keeps the model, what was sent, the replies and the time.
    All 7 were valid on the first try, in 0.7–2.1 s.
  - `ASSISTANT_REPLAY=<dir>` makes the agent answer from the files, with no model. **A deal with
    no recording behaves as if no model is running**, which is how the browser tests see
    "assistant unavailable" (AS-8.4).
  - **Playwright now starts its own agent (replaying, port 8092) and web app (5174)**, so it always
    runs the current code and never uses `make dashboard`'s 8091 and 5173. It still needs the
    funding API and the OIDC provider. Vite's `/agent` target comes from `AGENT_URL`.
  - Tests: 25 new (121 agent tests). Every recording is valid, sent only its own deal (no names,
    no SSN; LA-1007's VINs are its failing check), and has the shape `summary_input` sends today.
    If that shape changes, the test says to rerun `make record-assistant`. The invalid-once,
    invalid-twice and unavailable cases stay scripted (T153, T154): the real model never failed.
- [x] **T156** Playwright: `AS_8_1_…` (replayed) and `AS_8_4_…` (no model: the overview still works).
  **Notes (done 2026-11-21):**
  - `e2e/assistant.spec.ts`, 3 tests: `AS_8_1` on LA-1006 (the recorded summary as plain text,
    the disclosure, no buttons in the panel, the overview untouched, and a second click rebuilds
    one panel); `AS_8_1` on a finished deal (LA-1001, FUNDED); `AS_8_4` on LA-1002, which has no
    recording (unavailable, and the overview and opening another deal still work).
  - The shared helpers moved to `e2e/support.ts`. 13 Playwright tests, all passing twice in a row.
  - **Caught on the way:** my first panel locator matched nothing, so "no buttons" passed
    trivially. The test now first asserts it found exactly one panel.
  - "Summarizing…" before the answer is checked on the agent over `message/stream`
    (`test_assistant_panel.py`); a replayed answer arrives too fast for the browser to show it.
- [x] **T157** ~~Measure the summary's p95 against local Gemma (NFR-02, < 5 s).~~ **Dropped by the
  owner (2026-11-21):** NFR-02 is now "within 15 s, including a cold start", and speed isn't tuned
  further. Observed in T153–T155: **0.7–2.1 s warm**, **8.9 s cold** (Ollama unloads the model after
  5 idle minutes), all within 15 s. No `keep_alive` tuning.

## D1c: Look and feel (D-18)

**Proves:** the dashboard looks like a real product, with no protocol or API change: the theme
lives in the web app, and the overview's layout is still built by agent code.
**Exit:** AS-2.1, AS-2.3, AS-2.5, AS-2.6 and AS-8.1 still pass on the new layout; NFR-04, NFR-05.

- [x] **T161** Theme: design tokens on `:root` (light, with a dark mode that follows the OS), the
  renderer's `--a2ui-*` variables set from them, and styles for the shell, `DataTable` and
  `StatusBadge`. Replaces Vite's starter stylesheet.
- [x] **T162** Two catalog components, on both sides (web and agent validation): `Heading` and
  `FactList` (plan §6).
- [x] **T163** The overview's new layout: a header (title, dealer and submitted date, status
  badge), the facts as a `FactList`, an action bar, and the version history with its own
  heading. The assistant's panel gets a heading too, and sits beside the overview on wide screens.
- [x] **T164** Tests follow the layout: agent surface tests, and Playwright reads facts by label.
  A screenshot of the new look goes into `learnings/screenshots/`.
  **Notes (done 2026-11-21):**
  - The theme is CSS only: design tokens, a dark mode, and the renderer's `--a2ui-*` variables (it
    has 139). Vite's starter stylesheet, which centred everything, is gone.
  - **Pitfalls:**
    - The renderer wraps a caption in `<em>`, so `.a2ui-text-caption em` is what un-italicises
      it.
    - Strict A2UI refuses a component nothing reaches, so the assistant's panel sends only the
      lines its state uses.
  - The overview's header row aligns to the top, and its action row to the centre. The shell has a
    top bar, a toolbar, a two-column workspace with the assistant beside the deal, an empty state
    and a sign-in card.
  - Tests: 122 agent tests (1 new: the facts are a `FactList` built from the deal's own fields) and
    13 Playwright tests, which now read facts by label (`fact(page, label)` in `e2e/support.ts`).
    The no-buttons check on the assistant's panel stays strict: only Card, Column, Heading and Text.
  - Screenshot: `learnings/screenshots/Funder dashboard.png`, also shown in the README and
    `ui_summary.md`.

## D2: Roles and permissions (five specialists and a supervisor, D-15)

**Proves:** each deal's kinds of work route it to one specialist (or the income specialist when it
has two or more), the API enforces AS-11.2, and the overview offers only the actions it allows.
**Exit:** AS-11.1–11.7; D-17.

- [ ] **T201** Test first: `WorkRoutingTest` walks the kinds-of-work table (spec §3) cell by cell:
  each stip by its check, a field review by its field's document, a manual stip, a check in ERROR,
  `RISC_INCOMPLETE`, a failed or unknown payment, and `GENERAL` (a manual stip with no check, an
  unmapped stip code); then routing for one kind, two or more kinds
  (LA-1027: RISC + documents + income → the income specialist) and none. It also checks every
  seeded scenario's routing (e.g. LA-1006 → documents, LA-1023 → RISC, LA-1030 → payment).
- [ ] **T202** `WorkRouting` and `Permissions` in `funding-domain` (plan §4). `PermissionsTest`
  covers every cell of AS-11.2 plus `NOT_ASSIGNED_TO_YOU`, `NOT_YOUR_SPECIALTY`,
  `ROLE_NOT_ALLOWED`, and AS-11.5 (routing changes, the assignee keeps the deal but can't act).
- [ ] **T203** The new user directory (AS-11.7): `gap-1`, `income-1`, `risc-1`, `docs-1`,
  `payment-1`, `general-1` (catch-all), `supervisor-1`. Spec 001's tests, the agent's tests, Playwright, the README and
  `learnings/oidc_authn_authz_summary.md` move off `funder-1` and `funder-2`.
- [ ] **T204** `FunderService` checks `Permissions` before the existing guards (including Take
  from T106) and returns 403 problem+json with a reason. The role goes into the audit
  `after_state` (AS-11.4).
- [ ] **T205** On the overview: `workKinds`, `routedTo` and `allowedActions` from `Permissions`
  (FR-05b). The overview surface shows the kinds and who it's routed to, and only allowed
  actions; Take only when the deal is routed to you.
- [ ] **T206** Tests: `FunderApiTest` `AS_11_3_…`, `AS_11_5_…`, `AS_11_6_…`; Playwright: a
  specialist can't act outside their lane and is told why, and the supervisor can.

## D2b: Assignment suggestions for the supervisor (US-12)

**Proves:** an ADK agent with read-only tools proposes assignments, code decides who's eligible,
and nothing is assigned without the supervisor's click.
**Exit:** AS-12.1–12.7; FR-13.

- [x] **T251** Spec first: US-12, D-15, D-16, D-17, FR-13 and plan §7 (the spec PR, 2026-11-21).
- [ ] **T252** API: the unassigned part of the worklist (enough for US-12; D6 adds the rest), with
  `workKinds`, `routedTo` and earlier assignees per row; `GET /api/v1/users` (supervisor only)
  with role and open-deal count (FR-12, FR-13). Java tests.
- [ ] **T253** Code's own plan (AS-12.5) as a pure function: most urgent first, each deal to the
  eligible user with the fewest open deals. Unit tests.
- [ ] **T254** The agent: an ADK `LlmAgent` with read-only tools (`unassigned_deals`, `users`,
  `eligible`) on the `assign` model in `models.yaml`; code drops ineligible suggestions (one
  retry) and fills the rest from T253's plan; the model sees no names, amounts or documents
  (AS-12.6).
- [ ] **T255** The supervisor's surface: **Suggest assignments** on the unassigned list, one row
  per suggestion with its reason and an **Assign** button behind a confirmation (the normal
  reassign, AS-12.4). Not offered to specialists.
- [ ] **T256** Recorded answers (as T155) and tests: an ineligible suggestion is dropped, no model
  shows code's plan, the prompt holds no names; Playwright `AS_12_1_…`, `AS_12_4_…`.

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
- [ ] **T603** The worklist surface: my queue, unassigned, my specialty queue, needs reassignment (supervisor), expiring soon; filters for status, kind of work, stip type, hold reason, dealer and days to expiry.
- [ ] **T604** Needs reassignment (AS-11.5): a deal whose routing changed shows there, and the supervisor reassigns it from the list.
- [ ] **T605** Playwright: filters, `AS_1_6_…`, a routing change landing in needs reassignment and the supervisor's reassign.

## D7: Documents and audit

**Exit:** AS-5.1–5.3, AS-7.1–7.2.

- [ ] **T701** The documents surface: versions, fields with `ConfidenceTag`, v1-vs-v2 differences (computed in the agent).
- [ ] **T702** The audit surface, newest first, with system changes labelled and the role shown.
- [ ] **T703** Tests: unit and Playwright (LA-1029 shows RISC v1 and v2).

## D8: Assistant

**Exit:** AS-8.2, AS-8.3, and AS-8.4–8.5 for them (AS-8.1 is D1b), NFR-02.

- [x] **T801** Moved to D1b as T152.
- [ ] **T802** Suggest and draft stip (the summary is D1b's T153): prompts, schemas, validate and one retry, suggestion filtering against `allowedActions`.
- [ ] **T803** Record and replay suggest and draft stip, on D1b's recorder (T155).
- [ ] **T804** `make eval-assistant` (offline eval plus reference answers); suggestions within NFR-02's 15 s.

## D9: Generated view (learning)

**Exit:** AS-9.1–9.3.

- [ ] **T901** "Ask for a view": the LLM writes A2UI (`express` format or a catalog subset), validated against both catalogs, read-only.
- [ ] **T902** Recorded responses, plus a test that an invalid view is never shown.
- [ ] **T903** Findings on how well local Gemma writes A2UI go into `a2ui-notes.md`.

## Traceability (stories → slices)

| Story | Slice(s) | | Story | Slice(s) |
|---|---|---|---|---|
| US-1 Worklist | D1, D6 | | US-7 Audit | D7 |
| US-2 Overview | D1, D5 | | US-8 Assistant | D1b (Summarize), D8 |
| US-3 Stipulations | D3 | | US-9 Generated view | D9 |
| US-4 Results | D4 | | US-10 Developer setup | D1 (and every slice) |
| US-5 Documents | D7 | | US-11 Roles | D1 (sign-in, directory), D2 (routing, permissions), and every write |
| | | | US-12 Assignment suggestions | D2b |
| US-6 Decisions | D5 | | | |
