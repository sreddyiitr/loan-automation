# Plan 002: Funder Dashboard (ADK + A2UI)

How [`spec.md`](spec.md) is built. A2UI facts come from [`a2ui-notes.md`](a2ui-notes.md); read it
before changing anything here. Items that were marked **(spike)** were settled by D0 on 2026-11-21 (`a2ui-notes.md` §12d).

## 1. Stack

| Part | Choice | Notes |
|---|---|---|
| Funder API | Java 21 / Spring Boot (spec 001), **extended** | Worklist, overview fields, allowed actions, roles (§4, §5) |
| Agent service | **Python 3.12** (`uv`), `a2a-sdk` 0.3.26, `a2ui-a2a` 0.4.0, `a2ui-agent-sdk` 0.12.0, httpx; `google-adk` and `a2ui-adk` from D8 (assistant) | ADK's A2UI integration is Python-only (D-2) |
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
  confirm button sends the real event (AS-6.2). The handler calls the API and then refreshes the
  deal's surfaces with `updateDataModel`. A `409`, `400` or `403` becomes a message from
  `messages.yaml` (FR-06).
- **Freshness (FR-10):** while the overview's `inProgress` flag is set, the agent re-reads the
  overview every 2 s, up to 60 s, and pushes `updateDataModel`. It stops once the deal settles.

## 4. Identity and roles (US-11, FR-09, FR-11)

- **Dev sign-in** (local profile only): the web app asks for a user id and role. The agent gets a
  **dev token** for them from the funder API: `POST /api/v1/dev/token`, which exists only under
  `local`. It's an HMAC-signed JWT with `sub`, `role` and `exp`, using a per-run secret.
- **The funder API trusts only the token.** A Spring filter verifies it and puts `actor` and
  `role` in the request. That replaces the free-text `X-Funder-Id` header for the dashboard.
  Single sign-on later only swaps the issuer (FR-09).
- **Compatibility:** spec 001's tests and `curl` examples send `X-Funder-Id`. Under `local`
  **only**, a request without a token but with `X-Funder-Id` is treated as role `SUPERVISOR`, so
  spec 001 behaves as before. Supervisors can do everything (D-11). Outside `local` it's refused.
- **Permissions** are a pure, table-driven `Permissions` class in `funding-domain`:
  `allowed(role, action, deal) -> Allowed | Refused(reason)`. It covers:
  - assignment (`NOT_ASSIGNED_TO_YOU`);
  - income work (`INCOME_WORK_NEEDS_SPECIALIST`);
  - role-only actions (`ROLE_NOT_ALLOWED`).

  `FunderService` checks it before the existing guards. The same class produces `allowedActions`
  (FR-05b), so the UI and the API can't disagree.
- **Income work** = a PENDING income stip, or a current INCOME result that is FAIL, NEEDS_REVIEW
  or ERROR.
- **Audit:** `actor` stays the user id; the role goes into `after_state.role` (AS-11.4). There's
  no schema change.

## 5. Funder API additions (FR-05, FR-12)

| Addition | Shape | Notes |
|---|---|---|
| Worklist | `GET /api/v1/applications?queue=mine\|unassigned\|income\|all&status=&stipCode=&holdReason=&dealer=&expiresWithinDays=&sort=expiry\|age&page=` | Latest, non-terminal versions only (AS-1.6). Each row: loan id, version id, status, dealer, assignee, hold reasons, pending stip codes, `expiresAt`, `inProgress` |
| Hold reasons | Derived in SQL from stips, current results, the disbursement row and status | `STIPS_PENDING`, `CHECK_ERROR`, `RISC_INCOMPLETE` (from the last `RISC_INCOMPLETE` status audit), `PAYMENT_FAILED`, `PAYMENT_UNKNOWN`; not stored |
| Overview fields | `expiresAt`, `inProgress` (the workflow's `step`), `payment` {state, reference, amount}, `allowedActions[]` {action, allowed, reason} | `inProgress` comes from the workflow query that `FunderService` already uses |
| Stip codes | `GET /api/v1/stip-codes` | From `StipCodes`, with descriptions and `waivable` |
| Comparison attribution | `documents: {left, right}` and `fields` on each comparison | Optional fields, so `rules.lock` stays unaffected. **Check:** does the result-details JSON change the conformance gate? Expected: no |
| Confidence cut-off | `GET /api/v1/settings/ui` → `{confidenceFlagBelow, expiringSoonDays}` | `expiringSoonDays` defaults to 5 (D-12) |
| Send to income queue | `POST /api/v1/versions/{vid}/income-queue` | Flags the deal for income work without changing the assignee (AS-11.5); audited |
| Dev token | `POST /api/v1/dev/token` (local only) | §4 |

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
- **Basic Catalog** for everything else: Tabs, Card, Row/Column, List, Text, Button, Modal,
  TextField, ChoicePicker, Divider, Icon.
- The React implementations live in `web/src/catalog/` (`createComponentImplementation` with a Zod
  API built from `CommonSchemas`).
- **The published `web_core` 0.15.0 rejects a per-component `@catalogId`**, so a surface uses one
  catalog. **Our catalog extends the Basic Catalog**: all its components and functions, plus ours.
  Every surface uses our `catalogId`. The agent-side catalog JSON (for validation, NFR-05) is the
  basic catalog plus our component schemas (D2).

## 7. Assistant (US-8, US-9)

- **Model registry** (`models.yaml`):

  ```yaml
  default: local_gemma
  models:
    local_gemma: {model: "ollama_chat/gemma4:e4b", context: 4096, structured_output: false,
                  thinking: {param: think, off: false, on: true}, cost_per_mtok: {in: 0, out: 0}}
  features: {summary: local_gemma, suggest: local_gemma, draft_stip: local_gemma, generated_view: local_gemma}
  ```

  Swapping a model or a feature is a YAML edit (FR-07).
- **Summary, suggest, draft:** the prompt gets the deal's API data (no SSN exists in it). The reply
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
| The roles change breaks spec 001 tests | Under `local`, `X-Funder-Id` means SUPERVISOR; the spec 001 suites must stay green in every slice |
| Gemma's 4K context can't hold the generated-view prompt | `express` format or a small catalog subset; raise `num_ctx` if memory allows |
