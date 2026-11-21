# Funder dashboard: UI summary (spec 002: D0, D1 and D1b done)

A dashboard where funders (underwriters) handle the loans the automation couldn't fund on its own.
It is built on the spec 001 funder API and uses **A2UI** for the UI and **ADK** for the agent.
Details are in [`capabilities.md`](../specs/002-funder-dashboard/capabilities.md) and
[`a2ui-notes.md`](../specs/002-funder-dashboard/a2ui-notes.md).

## Status (2026-11-21)

**Spike D0** (T001–T006), **D1, the walking skeleton** (T101–T108, merged as PR #11) and **D1b,
Summarize** (T151–T157) are done. What works end to end today:

1. The browser asks the agent over A2A, with the user's OIDC bearer token.
2. The agent replies with A2UI v1.0, built by code with no LLM.
3. React renders it with `@a2ui/react` 0.15.0, including our custom `DataTable`.
4. Clicking **Take this deal** sends a v1.0 `action` back to the agent.
5. Agent code calls the funder API (`PUT /assignee`) with that same token, then re-reads the deal.
6. The screen updates, and the write appears in the audit trail.

You sign in on the OIDC provider's login page, then open any deal by its loan id. The overview
is the one screen so far. It shows the deal facts, days to expiry, payment status, a
"running" note while the workflow is busy, a link to the run in the Temporal UI, and the version
history. **Take this deal** appears only while the deal is unassigned, asks for confirmation, and is
refused if someone else took the deal first. The actor in every write is whoever the token names.

**Summarize** (D1b) asks the local model to explain where the deal stands. The answer appears in
its own read-only panel under the overview: "Summarizing…" at once, then two or three sentences
with a note that the assistant wrote them from this deal's data only. With no model running, the
panel says the assistant is unavailable and everything else works.

Not yet built: the worklist, the other tabs, role enforcement (D2), and the assistant's
"suggest next step" and "draft stip text" (D8).

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
   └─ Assistant (funder_agent/assistant): an ADK LlmAgent on the model from models.yaml
        (local Gemma by default); our code picks what it sees and checks what it says
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
| **Assistant** | Plain-language case summary (**built**, D1b), suggested next step, drafted stip text. It only *suggests*; the funder clicks to act |

## What's fixed and what's dynamic

| Part | Built by | Why |
|---|---|---|
| The screens: worklist and the five tabs | **Code** → A2UI JSON (no LLM) | Predictable, fast, testable like spec 001 |
| Every write (waive, approve, reject, …) | **Code**, after the funder's click → funder API | The model never acts on its own |
| Assistant answers | **LLM** → small JSON, which code checks and renders as plain text in a fixed panel | Works on small local models; the model can't add buttons or actions |
| One "ask for a view" feature (e.g. "show the income history as a card") | **LLM writes the A2UI itself** | The generative-UI learning piece |

## Components

- **One catalog per surface:** ours *extends* the Basic Catalog, because `web_core` 0.15.0 rejects a
  per-component `@catalogId`.
- **From A2UI's Basic Catalog:** Text, Row/Column, List, Card, Tabs, Modal, Button, TextField, ChoicePicker, CheckBox, Divider, Icon, Image.
- **Our custom catalog**, for what the basic one lacks: `DataTable` (**built** in D0) and `StatusBadge` (**built** in T105; the status is always written out, never colour alone), then `ConfidenceTag`, and later `DocumentPageViewer` with bounding-box overlays.

## Model

- **Default:** local **Gemma** (`gemma4:e4b`) through Ollama. **$0, nothing leaves the laptop.**
- **The model is configuration** (`dashboard/agent/models.yaml`, built in T152): a LiteLLM model
  string, a health-check URL and capability flags, plus each feature's model and whether it
  thinks. Swapping to any paid or larger model needs no code change, and each feature can use its
  own model. A test fails if a provider or model name appears in the agent's code.
- **How Summarize runs** (D1b):
  - The agent sends the model only this deal, trimmed: the facts, the passed checks by name, and
    the failing comparisons and stips. No names, ids or SSN; prompts are 120–220 tokens.
  - It runs as an **ADK `LlmAgent`** through LiteLLM, with thinking off (`reasoning_effort:
    none`), on a fresh session per request.
  - The JSON reply is checked in code (fence stripped, one retry with the error). ADK's
    `output_schema` isn't used: local Gemma refuses forced JSON.
  - The panel is streamed over A2A `message/stream`: "Summarizing…", then the answer.
- **Speed:** about 1–2 s warm, ~9 s when Ollama has to load the model first; the target is 15 s
  (NFR-02, owner).
- With no model running, the assistant is shown as unavailable and everything else works.

## Run the spike (today)

Needs Python 3.12 with `uv`, and Node 22 or newer (tested with 24). No model is needed.
`make dashboard-preflight` names anything missing, with its install command (AS-10.2).

1. Start Temporal (`make temporal`, which keeps runs in `~/.temporal/funding.db`), then run `make dashboard` (T107, AS-10.1). It
   runs the preflight, then reuses or starts each part:
   - the funding API, as `make run` does (reusing Postgres, the emulator, WireMock and the OIDC
     provider), then submits every seeded scenario still in DRAFT (AS-10.3);
   - the agent on port 8091 (`FUNDER_API_URL`, `TEMPORAL_UI_URL` and `AGENT_PORT` override it);
   - the web app on port 5173, whose dev server proxies `/agent` to the agent.
   A part counts as reused only if it answers as ours (the API refuses a call without a token,
   the agent card is the dashboard's); a port held by anything else stops it with a message.
   Logs are in `target/dashboard/`. `Ctrl+C` stops the whole process tree of each part it
   started (the Maven wrapper forks the JVM), and leaves the reused parts running.
2. By hand instead: `make run` and `make seed-submit-all`, then
   `cd dashboard/agent && uv run python main.py`, then `cd dashboard/web && npm install && npm run dev`.
3. Go to http://localhost:5173 and click **Sign in**. On the provider's login page ("Just a mock
   login"), type a user id: `funder-1`, `funder-2`, `income-1` or `supervisor-1`. Leave the claims
   box empty, then click **Sign-in**. Back on the dashboard, type a loan id (e.g. `LA-1006`) and
   click **Open**.
   - The sign-in is OIDC Authorization Code with PKCE (`src/auth.ts`, `oidc-client-ts`). The
     token stays in memory, so reloading the page means signing in again.
   - The provider must run with `scripts/oidc-config.json`, which `oidc-env.sh` checks. Without it,
     browser-login tokens get the wrong audience and every deal says your sign-in isn't valid.
   - A user id that isn't in the directory signs in, but gets "your user id isn't set up for the
     dashboard".
4. Tests:
   - Agent: `make test-dashboard`. 121 tests, no services and no model needed. Every surface
     passes A2UI v1.0 strict validation against our catalog (NFR-05), including a rebuild applied
     on top of the first screen. A second check catches keys the web renderer rejects but the
     Python validator accepts (`@accessibility`, `@catalogId`).
     The assistant's tests cover the registry, the trimmed input, the checks and the retry, the
     panel and its stream (through the real A2A app), and the 7 recorded answers.
   - Browser: `make test-dashboard-e2e` (`e2e/walking-skeleton.spec.ts`, `e2e/assistant.spec.ts`).
     13 tests, each signing in on the real login page and named for its acceptance scenario.
     The ones that use LA-1006 start by unassigning it through the API, so no versions are added:
     - `AS_1_1`: every write is made as the signed-in user (the audit's actor), and a user who
       isn't in the directory is told so;
     - `AS_1_5`: an unknown loan id shows "not found";
     - `AS_2_1`: the overview shows each deal fact, expiry and payment, checked against the API;
     - `AS_2_3`: version history, newest first, earlier ones "superseded by vN";
     - `AS_2_5`: the link opens the deal's workflow in the Temporal UI;
     - `AS_2_6`: Take asks for confirmation, then the deal is yours; if someone else took it
       first, nothing changes and the screen says who has it;
     - `AS_10_3`: every seeded scenario opens with the status its scenario file expects. This
       holds on a fresh database; a deal changed by hand (say, LA-1006 waived) fails it, by name;
     - `FR_11`: signed out shows only the sign-in screen;
     - `AS_8_1`: Summarize shows the recorded summary, read-only, and the overview stays; a
       finished deal is summarized by how it ended;
     - `AS_8_4`: with no model, the panel says so and everything else works.
     They start their own agent (port 8092, replaying recorded answers: `ASSISTANT_REPLAY`) and
     web app (5174), so they always run the current code. They use `make dashboard`'s funding API
     and OIDC provider. Install the browser once with `npx playwright install chromium`.
   - Recordings: `make record-assistant` asks the model to summarize 7 seeded deals and saves the
     conversations in `dashboard/agent/recordings/summary/`. Rerun it when the prompt or the model
     changes; a test says when.

Pinned versions: `@a2ui/react` / `@a2ui/web_core` 0.15.0 and React 19 on the web side; `a2a-sdk`
0.3.26, `a2ui-a2a` 0.4.0, `a2ui-agent-sdk` 0.12.0 (its `a2ui-core` 0.7.0 does the validation),
`httpx`, `pyyaml`, `google-adk` 2.15.0 and `litellm` 1.110.2 on the agent side, `oidc-client-ts` 3.5.0 on the web side, plus `pytest` for development (`package-lock.json`, `uv.lock`).

## Developer setup

- **Built (T107):** `make dashboard` starts the Java app, the agent and the web app, reusing
  Temporal, Postgres, the emulator, WireMock and the OIDC provider from spec 001. Seeded
  scenarios are submitted on launch. `make dashboard-preflight` checks the tools.
- New tools: Python 3.12 with `uv`, Node 22, and Ollama (only for the assistant; the preflight
  only warns about it).
- **Built (D1b):** tests need no model. Screens are checked against the A2UI v1.0 schemas, the
  browser tests run against the real funding API, and model answers are recorded once
  (`make record-assistant`) and replayed.

## Not in this spec

- Editing extracted values (corrections arrive as new document versions)
- Uploading documents
- Messaging the dealer
- Bulk actions
- Real single sign-on. Sign-in is standard OIDC against a local mock provider (`scripts/oidc-env.sh`):
  you type a user id, and the funder API gives that user a role from its user directory. Single
  sign-on later is a configuration change
