# Funder dashboard: UI summary (spec 002, spike D0 done)

A dashboard where funders (underwriters) handle the loans the automation couldn't fund on its own.
It is built on the spec 001 funder API and uses **A2UI** for the UI and **ADK** for the agent.
Details are in [`capabilities.md`](specs/002-funder-dashboard/capabilities.md) and
[`a2ui-notes.md`](specs/002-funder-dashboard/a2ui-notes.md).

## Status (2026-11-21)

**Spike D0 is done** (T001–T006), and **D1, the walking skeleton, is under way** (T101–T104
done). What works end to end today:

1. The browser asks the agent over A2A, with the user's OIDC bearer token.
2. The agent replies with A2UI v1.0, built by code with no LLM.
3. React renders it with `@a2ui/react` 0.15.0, including our custom `DataTable`.
4. Clicking **Take this deal** sends a v1.0 `action` back to the agent.
5. Agent code calls the funder API (`PUT /assignee`) with that same token, then re-reads the deal.
6. The screen updates, and the write appears in the audit trail.

It's one screen: the LA-1006 overview. It shows the deal facts, days to expiry, payment status, a
"running" note while the workflow is busy, a link to the run in the Temporal UI, and the version
history. The actor in every write is whoever the token names. Not yet built:
- the browser sign-in screen (T105; until then a token is handed to the page, see below);
- opening any loan id;
- the worklist, the other tabs, role enforcement (D2) and the assistant.

The findings and pitfalls are in `a2ui-notes.md` §12d. New to A2UI?
[`a2ui_overview.md`](a2ui_overview.md) walks through how the code works, and
[`oidc_authn_authz_summary.md`](oidc_authn_authz_summary.md) explains sign-in.

## What A2UI is, in one paragraph

An agent sends **JSON describing the UI**: components from a known catalog, bound to a data model.
The browser draws it with its own trusted widgets. No code crosses the wire. Clicks come back to
the agent as events. We use A2UI **v1.0** with the React renderer.

## Architecture

```
Browser (React + A2UI renderer + our custom components)
   ▲ A2UI JSON         │ click events
   │                   ▼
Agent service (Python: a2a-sdk AgentExecutor; ADK + a2ui-adk only behind the assistant)
   ├─ Routing: our own AgentExecutor, plain A2A JSON-RPC (through a Vite proxy in dev)
   ├─ Screen builders (funder_agent/surfaces): code, no LLM
   ├─ Action handlers (funder_agent/actions): code, no LLM
   ├─ API client (funder_agent/api): relays the user's bearer token, maps refusals to messages
   └─ Assistant: LLM (local Gemma by default)
   │ REST + the user's own OIDC token
   ▼
Funding API (Java, spec 001): the only thing that writes
```

## Capabilities

| Area | What the funder can do |
|---|---|
| **Worklist** | My queue, unassigned, income queue, expiring soon; filter by status, stip type, hold reason (pending stips, check error, RISC incomplete, payment failed), dealer, days to expiry |
| **Roles** | **Funder** (own and unassigned deals; sends income work to the income queue), **income specialist** (works the income queue) and **supervisor** (can do everything: all queues, income work, reassigns). Enforced by the API, not just the UI |
| **Overview** | Deal details, status, days to expiry, payment status, version history, reassign the funder |
| **Stipulations** | List, satisfy or waive (with a reason; contract stips can't be waived), add a manual stip |
| **Mismatches** | Expected vs actual per check, side by side per document, re-run history, low-confidence fields highlighted |
| **Documents** | Document versions, extracted fields with confidence, compare v1 vs v2. *A page viewer with highlighted fields comes later (it needs document storage)* |
| **Decisions** | Approve, reject, re-run checks, retry a failed payment; only the actions the status allows are shown |
| **Audit** | Who did what and when, with before and after values; a link to the run in the Temporal UI |
| **Assistant** | Plain-language case summary, suggested next step, drafted stip text. It only *suggests*; the funder clicks to act |

## What's fixed and what's dynamic

| Part | Built by | Why |
|---|---|---|
| The screens: worklist and the five tabs | **Code** → A2UI JSON (no LLM) | Predictable, fast, testable like spec 001 |
| Every write (waive, approve, reject, …) | **Code**, after the funder's click → funder API | The model never acts on its own |
| Assistant answers | **LLM** → small JSON, which code renders | Works on small local models; validated in code |
| One "ask for a view" feature (e.g. "show the income history as a card") | **LLM writes the A2UI itself** | The generative-UI learning piece |

## Components

- **One catalog per surface:** ours *extends* the Basic Catalog, because `web_core` 0.15.0 rejects a
  per-component `@catalogId`.
- **From A2UI's Basic Catalog:** Text, Row/Column, List, Card, Tabs, Modal, Button, TextField, ChoicePicker, CheckBox, Divider, Icon, Image.
- **Our custom catalog**, for what the basic one lacks: `DataTable` (**built** in D0), then `StatusBadge` and `ConfidenceTag`, and later `DocumentPageViewer` with bounding-box overlays.

## Model

- **Default:** local **Gemma** (`gemma4:e4b`) through Ollama. **$0, nothing leaves the laptop.**
- **The model is configuration**: a LiteLLM model string plus capability flags. Swapping to any
  paid or larger model needs no code change, and each feature can use its own model.
- With no model running, the assistant is shown as unavailable and everything else works.

## Run the spike (today)

Needs Python 3.12 with `uv`, and Node 22 or newer (tested with 24). No model is needed.

1. Start Temporal (`temporal server start-dev`) and the app (`make run`), then submit LA-1006
   (`make seed-submit-all`).
2. Start the agent: `cd dashboard/agent && uv run python main.py` (port 8091). It calls the funder
   API on `localhost:8080`; override that with `FUNDER_API_URL`, and the Temporal UI link with
   `TEMPORAL_UI_URL`.
3. Start the web app: `cd dashboard/web && npm install && npm run dev`. The dev server proxies
   `/agent` to the agent.
4. **Sign in, for now by handing the page a token** (T105 adds a real sign-in screen). Get a token
   for one of the directory's users and open the page with it in the URL fragment. The page keeps
   it in memory and clears it from the address bar; the fragment never reaches a server, and this
   works in the dev server only.
   ```bash
   TOKEN=$(curl -s -X POST http://127.0.0.1:8090/funding/token -d grant_type=client_credentials \
     -d client_id=funder-1 -d client_secret=any -d scope=funding-api \
     | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
   open "http://localhost:5173/#access_token=$TOKEN"
   ```
   Without a token the page says "Sign in to use the funder dashboard."
5. Tests:
   - Agent: `cd dashboard/agent && uv run pytest`. 32 tests, no services needed. Every surface
     passes A2UI v1.0 strict validation against our catalog (NFR-05).
   - Browser: `cd dashboard/web && npx playwright test`. 2 tests against the running stack: the
     overview and Take, and the signed-out page. It starts its own dev server; install the
     browser once with `npx playwright install chromium`.

Pinned versions: `@a2ui/react` / `@a2ui/web_core` 0.15.0 and React 19 on the web side; `a2a-sdk`
0.3.26, `a2ui-a2a` 0.4.0, `a2ui-agent-sdk` 0.12.0 (its `a2ui-core` 0.7.0 does the validation),
`httpx` and `pyyaml` on the agent side, plus `pytest` for development (`package-lock.json`, `uv.lock`).

## Developer setup (planned)

- One command starts the Java app, the agent and the web app. It reuses Temporal, Postgres, the
  emulator and WireMock from spec 001.
- Seeded scenarios appear from the first launch.
- New tools: Python 3.12 with `uv`, Node 22, and Ollama (only for the assistant).
- Tests need no model: screens are checked against the A2UI v1.0 schemas, the browser tests run
  against the real stack, and model responses are recorded and replayed.

## Not in this spec

- Editing extracted values (corrections arrive as new document versions)
- Uploading documents
- Messaging the dealer
- Bulk actions
- Real single sign-on. Sign-in is standard OIDC against a local mock provider (`scripts/oidc-env.sh`):
  you type a user id, and the funder API gives that user a role from its user directory. Single
  sign-on later is a configuration change
