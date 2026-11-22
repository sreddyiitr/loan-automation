# Plan 002: Funder Dashboard (ADK + A2UI)

How [`spec.md`](spec.md) is built. A2UI facts come from [`a2ui-notes.md`](a2ui-notes.md); read it
before changing anything here. The questions left open for spike D0 were settled on 2026-11-21 (`a2ui-notes.md` §12d).

## 1. Stack

| Part | Choice | Notes |
|---|---|---|
| Funder API | Java 21 / Spring Boot (spec 001), **extended** | Worklist, overview fields, allowed actions, roles (§4, §5) |
| Agent service | **Python 3.12** (`uv`), `a2a-sdk` 0.3.26, `a2ui-a2a` 0.4.0, `a2ui-agent-sdk` 0.12.0, httpx; `google-adk` and `litellm` from D1b (Summarize), `a2ui-adk` from D9 (generated view) | ADK's A2UI integration is Python-only (D-2) |
| Transport | **A2A JSON-RPC** (`message/send`), served by our own `a2a-sdk` 0.3.26 `AgentExecutor`; the browser posts through a Vite proxy (`/agent`) | Confirmed by D0; no `@a2a-js/sdk` needed |
| Web client | **React 19** (Vite 8, TypeScript) with `@a2ui/react` **0.15.0** on `@a2ui/web_core` **0.15.0** | D0 confirmed that v1.0 renders, through the root exports and `web_core/v1_0` |
| Protocol | **A2UI v1.0**, Basic Catalog v1, plus our custom catalog | Pinned (NFR-07) |
| LLM | LiteLLM model string from config; default `ollama_chat/gemma4:e4b` | No paid model (D-7); swappable (FR-07) |
| Tests | pytest; JUnit (spec 001 style); **Playwright** for the browser | No model needed (AS-10.5) |

## 2. Repository layout (new)

```
dashboard/
  agent/                    Python package (uv project; uv.lock pinned)
    funder_agent/
      api/                  funder API client (adds the bearer token, maps reasons)
      surfaces/             fixed surface builders: data -> A2UI messages (pure functions)
      actions/              action handlers: event -> API call -> surfaces to refresh
      assistant/            LLM features: prompts, JSON schemas, validate and retry
      models.py             model registry from models.yaml (FR-07)
      server.py             ADK app + A2A executor
    models.yaml             model strings + declared capabilities
    messages.yaml           API reason -> plain message (FR-06)
    tests/                  unit tests, vendored A2UI v1.0 schemas, recorded LLM responses
  catalog/
    funder_catalog.json     our custom catalog (A2UI v1.0 catalog rules)
  web/                      React app (package-lock.json pinned)
    src/catalog/            React implementations of DataTable, StatusBadge, ConfidenceTag
    e2e/                    Playwright tests
scripts/dashboard-run.sh    one-command start (AS-10.1)
scripts/dashboard-preflight.sh
```

The Java changes stay in `funding-domain`, `funding-persistence` and `funding-api`.

## 3. How a screen gets to the browser

```
browser ── action / navigation event (A2UI renderer_to_agent) ──▶ agent
agent:  our A2A AgentExecutor ──▶ get_a2ui_messages(): is it an action, or a request for a screen?
          yes ──▶ code: call the funder API (with the user's token) ──▶ build A2UI messages
                   ──▶ create_a2ui_parts() reply  (the LLM never runs)   (FR-02, FR-03)
          no  ──▶ assistant request ──▶ ADK LlmAgent (configured model) ──▶ JSON ──▶ validate
                   ──▶ code renders it as A2UI                           (US-8)
browser ◀── createSurface / updateComponents / updateDataModel (agent_to_renderer)
```

- Routing is in our own `AgentExecutor`, as in the official A2UI sample (D0). ADK only runs behind
  it, for assistant requests.
- Before applying a reply, the web app skips a `createSurface` for a surface it already has, which
  A2UI treats as an integrity error.
- **Surface ids** use underscores (UAX #31): `worklist`, `deal_<versionId>_overview`,
  `deal_<versionId>_stips`, `deal_<versionId>_results`, `deal_<versionId>_documents`,
  `deal_<versionId>_audit`, `deal_<versionId>_assistant`.
- **Events** are named `<area>.<verb>`, e.g. `stip.waive`, `deal.approve`, `queue.filter`. Their
  `context` carries ids and the input values, read from the data model by `@path`.
- **Writes** need a confirmation. A first click opens a `Modal` with the reason input; the
  confirm button sends the real event (AS-6.2). The Basic Catalog's `Modal` opens on its trigger,
  and closes only by its own × or the backdrop. The trigger button still sends its own event, so
  those are named `ui.<…>` and ignored by the agent.
- **When a write changes which controls a deal offers** (e.g. Take disappears once taken), the
  agent rebuilds the surface: `deleteSurface`, then `createSurface`, `updateComponents` and
  `updateDataModel`. That also closes an open `Modal`. The web app applies a `createSurface` that
  follows a `deleteSurface` in the same reply. The handler calls the API and then refreshes the
  deal's surfaces with `updateDataModel`. A `409`, `400` or `403` becomes a message from
  `messages.yaml` (FR-06).
- **Freshness (FR-10):** while the overview's `inProgress` flag is set, the agent re-reads the
  overview every 2 s, up to 60 s, and pushes `updateDataModel`. It stops once the deal settles.

## 4. Identity and roles (US-11, FR-09, FR-11)

- **Provider:** `navikt/mock-oauth2-server` 6.1.5 in Docker, found or started by
  `scripts/oidc-env.sh` (reuse first, P-3), at `OIDC_ISSUER_URI=http://127.0.0.1:8090/funding`. It
  signs RS256 tokens and publishes discovery and JWKS. Everything uses `127.0.0.1`, because the
  provider builds `iss` from the URL it was called on.
- **Provider config (`scripts/oidc-config.json`, passed as `JSON_CONFIG`):** tokens for the
  dashboard's client (`client_id=funder-dashboard`, the browser login) and for `scope=funding-api`
  (tests, `curl`) carry `aud=funding-api`. Without it, a browser-login token has `aud=default`
  and the API rightly refuses it (verified on 6.1.5, 2026-11-21). This is the local version of
  what Auth0's `audience`, or an Okta or Entra ID API registration, does. The API's audience check
  stays strict. `oidc-env.sh` starts its own container with the config. A reused container must
  prove the rule is active with one token request, or the script refuses with the fix.
- **Browser sign-in:** the web app runs Authorization Code with PKCE as a public client
  (`funder-dashboard`). On the provider's login page the user types an id, which becomes `sub`.
  The token is kept in memory only (never `localStorage`). The web app sends it to the agent as
  `Authorization: Bearer`, and the agent card declares that scheme.
- **Token relay:** the agent forwards the same token to the funder API. It never creates or
  stores tokens.
- **The funder API is an OAuth2 Resource Server** (`spring-boot-starter-oauth2-resource-server`).
  It uses `issuer-uri` from `OIDC_ISSUER_URI`, requires audience `funding-api`, and checks
  signature, `iss`, `exp` and `nbf` through Spring Security, with no custom filter. Every
  `/api/v1/applications/**`, `/versions/**`, `/stip-codes` and `/settings/**` call needs a token
  (401 otherwise). The start API (`X-Trigger-Api-Key`), the `local` dev endpoints, Swagger and
  actuator keep their current access.
- **Roles (D-13, D-15):** a `UserDirectory` maps `sub` to a role from `funding.auth.users`. Locally
  (from D2) that is `gap-1` → GAP_SPECIALIST, `income-1` → INCOME_SPECIALIST, `risc-1` →
  RISC_SPECIALIST, `docs-1` → DOCUMENTS_SPECIALIST, `payment-1` → PAYMENT_SPECIALIST,
  `general-1` → GENERAL_FUNDER (the catch-all, D-17) and `supervisor-1` → SUPERVISOR; tests add their own ids. Until D2, `funder-1` and `funder-2` are
  FUNDER, and D2 moves the tests and docs to the new ids. An unknown `sub` gets 403 `UNKNOWN_USER`. With single
  sign-on, the directory can read a provider claim (e.g. groups) instead; that's configuration.
- **Tokens without a browser** (tests, `curl`): `POST $OIDC_ISSUER_URI/token` with
  `grant_type=client_credentials`, `client_id=<user id>`, any secret and `scope=funding-api` returns
  an RS256 token with `sub=<user id>` and `aud=funding-api` (verified against 6.1.5 on
  2026-11-21). MockMvc tests skip the provider and use Spring Security Test's `jwt()`.
- **No compatibility rule:** `X-Funder-Id` and `MISSING_FUNDER_ID` are removed. Spec 001's tests
  move to tokens (T102) and keep their assertions; README `curl` examples get a token first.
- **Kinds of work and routing (§3 of the spec)** are a pure `WorkRouting` class in
  `funding-domain`: `kinds(deal) -> Set<WorkKind>` from the pending stips (by check, or by the
  field's document for a field review), the current results in ERROR or NEEDS_REVIEW, the
  `RISC_INCOMPLETE` hold and the payment state, with `GENERAL` for anything the table doesn't map;
  `routedTo(kinds) -> Role` (one kind → its specialist, `GENERAL` → GENERAL_FUNDER, two or more →
  INCOME_SPECIALIST, none → SUPERVISOR only). Table-driven, so the
  mapping is one table a test walks cell by cell.
- **Permissions** are a pure, table-driven `Permissions` class in `funding-domain`:
  `allowed(role, action, deal) -> Allowed | Refused(reason)`, on top of `WorkRouting`. It covers:
  - assignment (`NOT_ASSIGNED_TO_YOU`);
  - routing (`NOT_YOUR_SPECIALTY`);
  - role-only actions (`ROLE_NOT_ALLOWED`: reassign and US-12 are the supervisor's).

  `FunderService` checks it before the existing guards. The same class produces `allowedActions`
  (FR-05b), so the UI and the API can't disagree. A deal whose assignee isn't its routed role
  (AS-11.5) is "needs reassignment", derived in the worklist query.
- **Audit:** `actor` stays the user id; the role goes into `after_state.role` (AS-11.4). There's
  no schema change.

## 5. Funder API additions (FR-05, FR-12)

| Addition | Shape | Notes |
|---|---|---|
| Worklist | `GET /api/v1/applications?queue=mine\|unassigned\|specialty\|needs-reassignment\|all&workKind=&status=&stipCode=&holdReason=&dealer=&expiresWithinDays=&sort=expiry\|age&page=` | Latest, non-terminal versions only (AS-1.6). Each row: loan id, version id, status, dealer, assignee, earlier assignees, hold reasons, pending stip codes, `workKinds`, `routedTo`, `expiresAt`, `inProgress` |
| Hold reasons | Derived in SQL from stips, current results, the disbursement row and status | `STIPS_PENDING`, `CHECK_ERROR`, `RISC_INCOMPLETE` (from the last `RISC_INCOMPLETE` status audit), `PAYMENT_FAILED`, `PAYMENT_UNKNOWN`; not stored |
| Overview fields | `expiresAt`, `workflowId`, `workflowStep`, `inProgress`, `payment` {state, reference, amount}, `allowedActions[]` {action, allowed, reason} | T103 (D1) adds all but `allowedActions` (D2, from `Permissions`). **`expiresAt`** = `submittedAt` + `funding.workflow.expire-after` (the workflow's own deadline, AS-8.6); `null` before submission and once FUNDED or REJECTED. **`workflowStep`** is the workflow query's `step` (`null` with no run). **`inProgress`** is true for every step except `WAITING_FOR_FUNDER` and `DONE` (AS-2.2). **`payment.state`** is `NOT_STARTED` (no disbursement row), `IN_FLIGHT` (REQUESTED while the step is `DISBURSING`), `PAID` (COMPLETED, with `reference` and `amount`), `FAILED` (the API refused it), or `UNKNOWN` (REQUESTED with no call running: retries exhausted, C-8). **`workflowId`** builds the Temporal UI link (AS-2.5) |
| Stip codes | `GET /api/v1/stip-codes` | From `StipCodes`, with descriptions and `waivable` |
| Comparison attribution | `documents: {left, right}` and `fields` on each comparison | Optional fields, so `rules.lock` stays unaffected. **Check:** does the result-details JSON change the conformance gate? Expected: no |
| Confidence cut-off | `GET /api/v1/settings/ui` → `{confidenceFlagBelow, expiringSoonDays}` | `expiringSoonDays` defaults to 5 (D-12) |
| Take | `POST /api/v1/versions/{vid}/take` | AS-2.6: assigns the version to the caller (the token's `sub`) under the row lock. Already the caller's → 200, unchanged and not audited; someone else's → 409 `ALREADY_ASSIGNED`; otherwise audited as `ASSIGNEE_CHANGED` with reason `TAKEN`. D2 adds the AS-11.2 rule: only a deal routed to the caller (or any, for the supervisor). `PUT …/assignee` stays the supervisor's reassign (AS-2.4) |
| Users | `GET /api/v1/users` | Supervisor only (FR-13): each user's id, role and open-deal count, for US-12 |
| Bearer auth | Every endpoint above, and spec 001's funder endpoints: `Authorization: Bearer <OIDC token>` | §4; 401 without a valid token, 403 `UNKNOWN_USER` for an id not in the directory |

The contract is regenerated (`-Dwrite.contract=true`) and `FunderOpenApiTest` keeps it in sync.

## 6. Custom catalog (FR-04)

- **`catalogId`:** `https://funding.local/a2ui/catalogs/funder/v1/catalog.json`. It follows the
  A2UI v1.0 catalog rules (notes §8): `components` at the top level, `component` as a `const`, refs
  only to `common_types.json`, and IDs per UAX #31.
- **`DataTable`:**
  - `columns`: an array of `{key, title, align}`;
  - `rows`: a `@path` to a list of row objects;
  - `highlight`: a `@path` to row keys to emphasise;
  - `emptyText`;
  - `onRowAction`: an optional `Action` per row, carrying `context.rowKey`.
- **`StatusBadge`:** `status`, with a label and an icon so it doesn't rely on color (NFR-04).
- **`ConfidenceTag`:** `value` and `threshold`, showing a "low" text when below the threshold.
- **`Heading`** (D1c): `text` and `level` (1–3). The basic `Text` allows only `caption` and
  `body`, so titles need their own component.
- **`FactList`** (D1c): `items`, a `@path` to a list of `{label, value}`, drawn as a two-column
  label/value grid. It replaces "Label: value" lines.
- **`CheckList`** (D1c, AS-2.7): `items`, a `@path` to `{check, outcome, label, detail}`; one row per
  check with an icon and a word, so the outcome never relies on colour (NFR-04).
- **`Timeline`** (D1c, AS-2.7): `items`, a `@path` to `{when, title, actor, tone}`, newest first; a
  dot per event, its tone (`done`, `waiting`, `refused`, `neutral`) only groups. The agent builds
  both from the funder API's results and audit trail; no API change.
- **The look (D1c, D-18):** styling is the renderer's job in A2UI v1.0 (themes left the protocol),
  so it lives in `web/src/index.css`: design tokens on `:root`, the renderer's `--a2ui-*`
  variables set from them, and our components' classes. The agent sends structure, never style.
- **Basic Catalog** for everything else: Tabs, Card, Row/Column, List, Text, Button, Modal,
  TextField, ChoicePicker, Divider, Icon.
- The React implementations live in `web/src/catalog/` (`createComponentImplementation` with a Zod
  API built from `CommonSchemas`).
- **The published `web_core` 0.15.0 rejects a per-component `@catalogId`**, so a surface uses one
  catalog. **Our catalog extends the Basic Catalog**: all its components and functions, plus ours.
  Every surface uses our `catalogId`. The agent-side catalog JSON (for validation, NFR-05) is the
  basic catalog plus our component schemas (D1).

## 7. Assistant (US-8, US-9)

- **Model registry** (`dashboard/agent/models.yaml`, or `ASSISTANT_MODELS`; built in T152):

  ```yaml
  default: local_gemma
  models:
    local_gemma:
      model: ollama_chat/gemma4:e4b
      api_base: http://localhost:11434
      health: http://localhost:11434/api/tags      # 2xx = usable; otherwise "assistant unavailable"
      context: 4096
      structured_output: false
      reasoning_effort: {thinking_off: none, thinking_on: medium}
      cost_per_mtok: {in: 0, out: 0}
  features:
    summary: {model: local_gemma, thinking: false}
    suggest: {model: local_gemma, thinking: true}
    # draft_stip, generated_view: likewise; a feature left out uses the default, thinking off
  ```

  Swapping a model or a feature is a YAML edit (FR-07). The `reasoning_effort` values are LiteLLM
  levels (not `off:`/`on:` keys, which YAML reads as booleans), which LiteLLM turns into each provider's own setting (for Ollama,
  `think: false` / `true`; verified through ADK, a2ui-notes §12b), so no provider key is in code.
  The `health` URL is in the file for the same reason: the availability check (AS-8.4) is
  provider-specific, so it's configuration. A test fails if a provider or model name appears in
  the agent's code.
- **ADK (D-14):** each feature is an ADK `LlmAgent` with a `LiteLlm` model built from the
  registry, run by an `InMemoryRunner` with one throwaway session per request (no memory between
  requests, so nothing from another deal can leak in, AS-8.5). `output_schema` is **not** used:
  it makes LiteLLM send Ollama `format: <schema>`, which local Gemma's MLX runner refuses (501).
- **Summary, suggest, draft:** the prompt gets the deal's API data (no SSN exists in it). For the
  summary that's the overview, the **open** stips and the checks that **didn't pass**, which keeps
  the prompt well inside the 4k context (the full data was ~2,000 tokens) and the answer fast. The reply
  is JSON validated against a schema. If `structured_output` is false, the code strips the fence
  and parses. One retry passes back the validation error (FR-08). Suggestions are filtered against
  `allowedActions` (AS-8.2). Summaries run with thinking off and suggestions with thinking on
  (notes §12b).
- **Generated view (US-9):** the model writes A2UI using the compact `express` format or a small
  subset of the catalog, to fit the context window (notes §12c). It's validated with `a2ui-adk`'s
  validator against both catalogs, read-only (only "open action" events are allowed), with one
  retry.
- **No model available:** `/api/tags` on Ollama fails, so the assistant shows "unavailable"
  (AS-8.4).
- **Assignment suggestions (US-12, slice D2b):** the `assign` feature in `models.yaml`, thinking on
  (it ranks). An ADK `LlmAgent` with **read-only function tools**, each calling the funder API with
  the supervisor's token: `unassigned_deals()` (loan id, kinds, routing, days to expiry, earlier
  assignees), `users()` (id, role, open deals) and `eligible(loan_id)`. It returns JSON
  `{suggestions[{loanId, assignee, reason}]}`; code drops any suggestion whose assignee isn't
  eligible (FR-08 retry once), and fills the rest from code's own plan (most urgent first, fewest
  open deals), marked as such. Each row's **Assign** sends the normal reassign (`PUT …/assignee`)
  after a confirmation. The deals are batched to fit the 4k window (~20 per call). Replay tests
  as in T155.

## 8. Developer setup (US-10)

- **`make dashboard`** (`scripts/dashboard-run.sh`):
  1. runs the preflight;
  2. starts the funding API as `make run` does;
  3. starts the agent (`uv run`, port 8091);
  4. starts the web app (`npm run dev`, port 5173);
  5. prints the URL.

  It reuses Temporal, Postgres, the emulator and WireMock, and works when sourced from zsh with no
  arrays (CLAUDE.md).
- **`scripts/dashboard-preflight.sh`:** Java 21, Docker and Temporal (as spec 001); Python 3.12
  and `uv`; Node 22. Ollama with `gemma4:e4b` is optional: it warns, doesn't fail, and prints the
  install commands.
- **The seeded scenarios are the dashboard's data.** On first launch, sign in as a funder and
  open LA-1006 (AS-10.3).

## 9. Testing (§8 of the spec)

| Level | What | Runs with |
|---|---|---|
| Agent unit | Every surface builder's output validated against the vendored **A2UI v1.0 schemas** and both catalogs (NFR-05); action handlers against a fake API; reason mapping; assistant parsing and filtering on recorded responses | `make test-dashboard` (no services) |
| Java | `Permissions` against the whole AS-11.2 table; worklist filters and hold reasons on seeded data; each new endpoint; contract sync | `make test-unit` |
| Browser | Playwright against the real stack: AS-1.x, 2.x, 3.2–3.5, 6.4, 11.3 | `make test-dashboard-e2e` (needs the stack running) |
| Assistant eval | The A2UI eval harness `--profile offline` against the configured model, plus our recorded reference answers | `make eval-assistant`, on request only |

Test names follow `AS_x_y_…` (constitution P-1).

## 10. Risks

| Risk | Mitigation |
|---|---|
| The React renderer's v1.0 support is partial (types are v0.9; no per-component `@catalogId`) | Settled by D0: v1.0 renders; one extended catalog per surface; re-check when `@a2ui/react` 0.16 ships |
| One invalid component blanks the page | Validate every surface on the agent before sending (NFR-05); a React error boundary around each surface |
| The roles change breaks spec 001 tests | T102 moves them to tokens (`jwt()` in MockMvc, `client_credentials` tokens in functional tests) without changing any assertion; the spec 001 suites must stay green in every slice |
| The browser code flow's `aud` differs from `funding-api` | **Confirmed in T105:** by default it's `default`. Settled by the provider config (§4); the API's audience check stays strict |
| Gemma's 4K context can't hold the generated-view prompt | `express` format or a small catalog subset; raise `num_ctx` if memory allows |
