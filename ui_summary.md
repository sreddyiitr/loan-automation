# Funder dashboard: UI summary (spec 002, planned)

A dashboard where funders (underwriters) handle the loans the automation couldn't fund on its own.
It is built on the spec 001 funder API and uses **A2UI** for the UI and **ADK** for the agent.
Details are in [`capabilities.md`](specs/002-funder-dashboard/capabilities.md) and
[`a2ui-notes.md`](specs/002-funder-dashboard/a2ui-notes.md).

## What A2UI is, in one paragraph

An agent sends **JSON describing the UI**: components from a known catalog, bound to a data model.
The browser draws it with its own trusted widgets. No code crosses the wire. Clicks come back to
the agent as events. We use A2UI **v1.0** with the React renderer.

## Architecture

```
Browser (React + A2UI renderer + our custom components)
   ▲ A2UI JSON         │ click events
   │                   ▼
Agent service (Python, ADK + a2ui-adk)
   ├─ Screen builders: code, no LLM
   ├─ Action handlers: code, no LLM
   └─ Assistant: LLM (local Gemma by default)
   │ REST + the funder's identity
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

- **From A2UI's Basic Catalog:** Text, Row/Column, List, Card, Tabs, Modal, Button, TextField, ChoicePicker, CheckBox, Divider, Icon, Image.
- **Our custom catalog**, for what the basic one lacks: `DataTable`, `StatusBadge`, `ConfidenceTag`; later `DocumentPageViewer` with bounding-box overlays.

## Model

- **Default:** local **Gemma** (`gemma4:e4b`) through Ollama. **$0, nothing leaves the laptop.**
- **The model is configuration**: a LiteLLM model string plus capability flags. Swapping to any
  paid or larger model needs no code change, and each feature can use its own model.
- With no model running, the assistant is shown as unavailable and everything else works.

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
- Real single sign-on (a dev sign-in with a chosen role stands in at first)
