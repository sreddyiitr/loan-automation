# A2UI notes (for spec 002)

Working notes on the A2UI protocol and its ADK integration, read on **2026-11-20** so they don't
have to be re-fetched. Field names are quoted exactly from the sources. Anything marked
*unverified* wasn't confirmed in the sources.

**Sources read:**
- the v1.0 spec page, https://a2ui.org/specification/v1.0-a2ui/ (all of it);
- the repo https://github.com/a2ui-project/a2ui: its tree, the `python/a2ui_adk/README.md`, the
  Basic Catalog `catalogs/basic/v1/catalog.json`, and `specification/v1_0/json/common_types.json`.

## 1. Versions and status

- **A2UI v1.0** is what a2ui.org calls "Current". The page was created 2025-11-20 and updated
  2026-06-08. The repo's `specification/v1_0/docs/a2ui_protocol.md` still says **Status:
  Candidate**, so treat v1.0 as stable-but-new.
- **Older versions:** v0.9.1 is the previous production release, and there are v0.9 and v0.8.
  Many blog posts and samples online still show **v0.9**, so check the version of any example
  before copying it (§9).
- **Releases on 2026-11-17:**
  - `python/a2ui-agent-sdk` v0.12.0
  - `python/a2ui-core` v0.7.0
  - `python/a2ui-a2a` v0.4.0
  - Swift v0.11.0
  - `cli` v0.4.0
- Every message carries `"version": "1.0"`.

## 2. Mental model

- The **agent** sends declarative UI messages. The **renderer** (client) draws them with its own
  native, trusted widgets. **No code crosses the wire**, only JSON describing components from a
  *catalog* the client already implements.
- **Surface:** one UI region. It is created by `createSurface` and identified by a `surfaceId`
  that must be unique for the renderer's lifetime. The renderer adds an implicit `Surface`
  container whose child is the component with `id: "root"`.
- **Components** are a **flat list** (an adjacency list). Each one has an `id`, and parents point
  to children by ID. Exactly one has `id: "root"`.
- **Data model:** a JSON object per surface, addressed by **JSON Pointer** (RFC 6901). Components
  bind to it, inputs write to it locally, and the agent updates it with `updateDataModel`.
- **Catalog:** the set of components and functions a renderer supports, identified by a
  `catalogId` (a URI).

## 3. Messages

### Agent → renderer (`agent_to_renderer`)

Each message has `"version": "1.0"` and exactly one of these keys:

| Key | Fields |
|---|---|
| `createSurface` | `surfaceId` (req), `catalogId`, `sendDataModel`, `components`, `dataModel`, `metadata` (v1.0: it can carry the components and data in one message) |
| `updateComponents` | `surfaceId` (req), `components` (req). Upserts by `id` |
| `updateDataModel` | `surfaceId` (req), `path` (default `/`), `value` (req; `null` removes the key). Upsert semantics |
| `deleteSurface` | `surfaceId` (req) |
| `callRendererFunction` | `surfaceId` (opt), `functionCallId` (req), `callFunction` {`@call`, `catalogId`, `args`} |
| `agentFunctionResponse` | `functionCallId`, plus `value` or `error` {`code`, `message`} |

### Renderer → agent (`renderer_to_agent`)

Exactly one of these per message:

| Key | Fields |
|---|---|
| `action` | `name`, `surfaceId`, `sourceComponentId`, `timestamp` (ISO 8601), `context` (req; bindings already resolved), `userMessage` (opt), `metadata` (opt) |
| `callAgentFunction` | `surfaceId`, `functionCallId`, `callFunction` {`@call`, `catalogId`, `args`}. The renderer must fill in the resolved `catalogId`, and the agent must echo `functionCallId` verbatim |
| `rendererFunctionResponse` | `functionCallId`, plus `value` or `error` |
| `error` | `code`, `message`, and optionally `surfaceId` or `functionCallId` (not both) |

**Error codes:**
- `VALIDATION_FAILED`, `UNALLOWED_PARENT`, `UNALLOWED_CHILD`
- `INTEGRITY_ERROR` (duplicate `surfaceId`)
- `INVALID_FUNCTION_CALL`, `UNKNOWN_FUNCTION`
- `EXPRESSION_ERROR` (a binding, check or action failed), `EXECUTION_ERROR` (a local function failed)

### Minimal shapes (condensed from the spec)

```json
{"version":"1.0","createSurface":{"surfaceId":"s1","catalogId":"<catalog uri>"}}
{"version":"1.0","updateComponents":{"surfaceId":"s1","components":[{"id":"root","component":"Text","text":"Hi"}]}}
{"version":"1.0","updateDataModel":{"surfaceId":"s1","path":"/user/name","value":"Alice"}}
{"version":"1.0","deleteSurface":{"surfaceId":"s1"}}
```

## 4. Components

- **Required:** `id` (a `ComponentId`) and `component` (the type name). Everything else is defined
  by the catalog.
- **Reserved keys** on any component: `@catalogId`, `@accessibility` {`label`, `description`,
  `live`, `hidden`}, `@metadata`, `@checks`.
- **IDs follow UAX #31.** `user_card` is valid; **`user-card` is not.** Use underscores, never
  hyphens. The same rule applies to extension names; the `a2ui_` prefix is reserved.
- **Children:** a static array of IDs, or a **template** {`componentId`, `path`} that repeats
  `componentId` for each item of the data-model list at `path`.
- **Nesting:** the catalog can constrain it with `allowedParents` and `allowedChildren`.
- **Rendering is progressive:** renderers handle missing references and undefined data
  gracefully, so the agent can stream components and data in any order.

## 5. Data binding and functions

| Syntax | Meaning |
|---|---|
| `{"@path": "/order/total"}` | Absolute JSON Pointer into the surface's data model. |
| `{"@path": "name"}` | Relative path (no leading `/`), resolved against the current item inside a list template. |
| `{"@call": "formatString", "args": {...}}` | Calls a catalog function. |
| `{"@call": "@index"}` | 0-based index inside a template (optional `offset`). |

- **Dynamic types:** `DynamicString`, `DynamicNumber`, `DynamicBoolean` and `DynamicStringList`
  each take a literal, a binding or a call.
- **String building:** there is no `+` operator. Use `formatString("Hello ${/user/name}")`, and
  `\${` for a literal `${`.
- **The `@` namespace is reserved.** Unknown single-`@` keys are rejected; write `@@` for a
  literal `@` key.

## 6. Actions (how the UI talks back)

A `Button` (or another component) has an `action` that is **one of**:
- `{"event": {"name": "...", "userMessage": <DynamicString>, "context": {...}}}`: this sends an
  `action` message to the agent. Context values are literals or bindings, and the renderer
  resolves them before sending.
- `{"functionCall": {...}}`: this runs a local renderer function, such as `openUrl`.

Input components (`TextField`, `CheckBox`, `Slider`, `ChoicePicker`, `DateTimeInput`) **write to
the local data model immediately**. **Nothing goes to the agent until an action fires.** Put the
input's path in the action's `context`, or set `sendDataModel: true` on the surface so the whole
model rides along in the transport metadata.

**Validation:** `@checks` is a list of `{condition, message}`. A condition is a binding or a
function call such as `required`, `regex` or `length`. A failing check shows its message.

## 7. Basic Catalog v1

The `catalogId` is `https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json`.

| Component | Properties (`*` = required) |
|---|---|
| `Text` | `text`*, `variant`, `weight` |
| `Image` | `url`*, `description`, `fit`, `variant`, `weight` |
| `Icon` | `name`*, `weight` |
| `Video` | `url`*, `posterUrl`, `weight` |
| `AudioPlayer` | `url`*, `description`, `weight` |
| `Row` / `Column` | `children`*, `justify`, `align`, `weight` |
| `List` | `children`*, `direction`, `align`, `weight` |
| `Card` | `child`*, `weight` |
| `Tabs` | `tabs`* = `[{title, child}]` (`title` is a `DynamicString`, `child` an ID), `weight` |
| `Modal` | `trigger`*, `content`*, `weight` |
| `Divider` | `axis`, `weight` |
| `Button` | `child`* (a `Text` id for a labelled button), `action`*, `variant` (`default` / `primary` / `borderless`), `weight`, `@checks` |
| `TextField` | `label`*, `value`*, `placeholder`, `variant`, `weight` |
| `CheckBox` | `label`*, `value`*, `weight` |
| `ChoicePicker` | `options`*, `value`*, `label`, `variant`, `displayStyle`, `filterable`, `weight` |
| `Slider` | `value`*, `max`*, `label`, `min`, `steps`, `weight` |
| `DateTimeInput` | `value`*, `enableDate`, `enableTime`, `min`, `max`, `label` |

**Functions:** `required`, `regex`, `length`, `numeric`, `email`, `formatString`, `formatNumber`,
`formatCurrency`, `formatDate`, `pluralize`, `openUrl`, `and`, `or`, `not`.

**Not in the basic catalog:**
- **There is no Table or DataGrid.**
- **There is no way to draw on an image** (bounding-box overlays).
- There is no badge or status chip.

Other catalogs exist in the repo: `catalogs/vegachart/v1` (charts), `catalogs/mcp/v1`, and
`catalogs/minimal`.

## 8. Catalogs, negotiation and custom components

- **Negotiation:**
  - The renderer advertises `a2uiRendererCapabilities` {`supportedCatalogIds` (req),
    `inlineCatalogs`}.
  - The agent declares its capabilities: `supportedCatalogIds` and `acceptsInlineCatalogs`
    (default `false`).
  - Capabilities sent per message are scoped to that turn; capabilities sent at session setup
    are scoped to the session.
- **Resolving a component's catalog:** the component's `@catalogId` (or the function call's
  `catalogId`), then the surface's `catalogId`, otherwise an **error**. There is no fallback.
- **A custom catalog** has the same shape as the basic one and its own `catalogId`. It
  references `common_types.json`. The rules:
  - component schemas go under top-level `components`;
  - each component's `component` is a `const` matching its key;
  - `"Surface"` is reserved;
  - `$defs` holds only shared leaves plus `anyComponent` and `anyFunction`;
  - external `$ref` targets are limited to an allowlist and are **never fetched over the network**;
  - structural links must use `ComponentId` or `ChildList` (a raw string is treated as text).
- **Mixing catalogs:** a surface can use the basic catalog by default, with individual
  components taken from our catalog through `@catalogId`.

## 9. Changes from v0.9 to v1.0 (to spot stale examples)

- The envelopes are renamed `agent_to_renderer` / `renderer_to_agent`.
- **`@` prefixes:** `@path` and `@call` (v0.9 used `path` and `call`), and the component keys
  `@catalogId`, `@accessibility`, `@metadata` and `@checks`.
- New: two-way **function RPC** (`callRendererFunction`, `callAgentFunction`,
  `rendererFunctionResponse`, `agentFunctionResponse`).
- `createSurface` can carry `components` and `dataModel`.
- **Themes are removed.** A `createSurface` with `theme` is invalid, so styling is the
  renderer's job.
- Catalog functions are object maps. UAX #31 naming is enforced.

## 10. Security model (relevant for funding decisions)

- **Who may call a function:** `allowedCallers` is `rendererOnly` (the default), `agentOnly` or
  `rendererOrAgent`.
- **User gestures:** a `requiresUserActivation: true` function (e.g. `openUrl`) must be
  `rendererOnly` and runs only from a user gesture. It is blocked in passive bindings
  (`EXPRESSION_ERROR`).
- **Renderer rejections:** renderers reject unknown or invalid calls (`INVALID_FUNCTION_CALL`),
  and must ignore, not reject, unrecognized extension keys.
- **Regex limits:** no backreferences, lookbehind or named groups; long or risky patterns are
  rejected; values over 4,096 code points don't match.
- **The design rule for us:** a UI action is only an *event* to the agent. The server (our
  funder API, with the funder's real identity) is what authorizes a write. The protocol can't
  stop an agent from emitting an action, so confirmation must be enforced on the server or in
  the agent's code, never in the prompt.

## 11. Transport

- The protocol is transport-agnostic. The transport must provide ordered, reliable delivery;
  framing (JSONL, WebSocket frames or SSE events); metadata; and optionally a return channel.
- **Named bindings:**
  - **AG-UI** (the standard binding);
  - an **A2A extension** (`specification/v1_0/extensions/a2a/docs/a2ui_extension_specification.md`;
    its extension URI is *unverified, not read*);
  - **MCP** (tool calls, outputs or resource subscriptions).
- Also usable: SSE with JSON-RPC, WebSockets, and REST (no streaming).
- **Function RPC** is correlated by `functionCallId`. Over request-response it rides in the
  request/response cycle; over a stream it is asynchronous.

## 12. Ecosystem (the repo, as of 2026-11-20)

- **Agent side:**
  - **Python:** `python/a2ui_agent` (the agent SDK), `a2ui_core`, `a2ui_a2a`, and
    **`a2ui_adk`**, the ADK integration.
  - **TypeScript:** `typescript/agents`.
  - **Dart:** `dart/a2ui_agent`.
  - **Kotlin:** only `kotlin/agent_sdk_legacy`.
  - ⚠ **There's no Java agent SDK and no ADK-Java integration**, so an A2UI agent with ADK
    means **Python**.
- **Renderers:**
  - TypeScript: **`typescript/renderers/{lit,angular,react,markdown-it}`**
  - Flutter: `dart/a2ui_flutter`
  - SwiftUI: `swift/swiftui`
  - Client samples are in `samples/client/{angular,flutter,lit,react}`.
  - *Unverified:* how fully each renderer supports v1.0. Some samples and tests are still v0.9,
    e.g. `e2e_test_v0_9` and the custom-components sample's `inline_catalog_0.9.json`.
- **ADK samples:** `samples/agent/adk/restaurant_finder` and
  `samples/agent/adk/custom-components-example`, which has an inline catalog and a "floor plan"
  custom component (images plus a server). It's a good template for our document viewer.
- **Also in the repo:** `conformance/` (core, e2e, agent formats, A2A and ADK extensions),
  `eval/` (LLM-output evals), `tools/composer`, and a CLI.

## 12a. Evals and model choice (from `eval/README.md`, read 2026-11-20)

- **Two-stage strict checks, then an optional LLM judge:**
  - Stage 1: schema validation
  - Stage 2: deterministic assertions
  - Stage 3: an LLM judge (needs a Gemini key)

  `--no-judge` grades on structure only.
- **Datasets:** `core_v1_0.yaml`, `core_v0_9_1.yaml` and a multi-turn set. They're encrypted at
  rest (transcrypt) to prevent contamination.
- **Models:** any Inspect AI model name. Gemini (`google/`, the default; CI runs only Gemini, with
  a quick check on `gemini-3.1-flash-lite`), **local Ollama** (`ollama/<model>`, no key; the README
  example is `ollama/gemma4:e4b`), and any OpenAI-compatible server
  (`openai-api/<provider>/<model>`, e.g. llama.cpp or LM Studio).
- **Profiles:** `cloud`, `local` (`--max-samples 1`, longer time limit) and `offline` (local, no
  judge).
- **Strategies and formats:**
  - **Output formats:** `direct_json` (raw A2UI JSON) or **`express`**, a compact inference
    format, presumably easier for small models.
  - **`subagent_tool`:** a specialist model call writes the payload.
  - **`skill_mode`:** the A2UI prompt isn't put in the system message. The model loads catalog
    "skills" through a tool, which saves prompt tokens.
- **Self-repair:** `--max-validation-retries N` sends a failed payload back with the validation
  error.
- **For us:** the same harness can compare a free local model, Gemini's free tier and Claude Haiku
  on *our* catalog before we choose.

## 12b. Local model: Gemma via Ollama (measured 2026-11-21 on the owner's Mac)

- **Setup:** Ollama 0.40.16 with `gemma4:e4b` (**9.5 GB**, not the "few GB" first assumed). It
  runs 100% on the GPU through the **MLX** runner with a 4096-token context. With Docker Desktop's
  7.7 GB limit, that's about 17 of 24 GB: it works, but it's snug.
- **No forced JSON:** the MLX runner returns **HTTP 501 `structured output is unavailable`** for
  `format: <schema>` and also for `format: "json"`. Plain prompting works: ask for "only a JSON
  object matching this schema", then **strip the ```` ```json ```` fence it adds**, parse, check
  against the schema in code, and retry once on failure. The same pattern applies when it writes
  A2UI.
- **The probe:** synthetic LA-1006 data (a waivable 1¢ price mismatch) in, `{summary,
  suggestions[{action, reason}]}` out, with `action` limited to the deal's allowed actions.
  ~400 prompt tokens.

  | Mode | Time per answer | Output tokens | Valid JSON | Quality |
  |---|---|---|---|---|
  | Thinking on (default) | ~8 s | ~900 (mostly hidden reasoning) | 2/2 | Good: waive, or satisfy |
  | `"think": false` | **~1–2 s** | ~110 | 2/2 | Weaker: also suggested "re-run might resolve" the mismatch, which is wrong because a re-run reads the same documents |

- **Rules:** `think: false` for summaries; thinking on (or a stronger model) for next-step
  suggestions. Never trust a suggestion's correctness. The backend should send *why* each action
  is allowed (e.g. re-run helps only for ERROR), and code should filter the model's suggestions
  against it.
- **Request shape (Ollama native API):** `POST http://localhost:11434/api/chat` with `{model,
  messages, stream: false, think: false, options: {temperature: 0}}`. From ADK it's LiteLLM
  `ollama_chat/gemma4:e4b`.
- **Verified through ADK (2026-11-21, `google-adk` 2.15.0, `litellm` 1.110.2), with a logging
  proxy between them and Ollama:**
  - `LiteLlm("ollama_chat/gemma4:e4b", reasoning_effort="none")` sends `think: false`
    (LA-1012: 2.8 s, against 6.1 s with no setting, where the field is absent and the model
    thinks). `think=False` and `extra_body={"think": False}` send it too, but they're
    Ollama-only; `reasoning_effort` is LiteLLM's portable setting, so the plan uses it.
  - `LlmAgent(output_schema=…)` makes LiteLLM send `format: <schema>`: **HTTP 501** from the MLX
    runner. So: JSON in the prompt, strip the fence (Gemma added one every time), validate in code.
  - On real seeded deals (overview, stips and results; ~2,000 prompt tokens) every summary was
    valid JSON and factually right, in 3.7–4.2 s warm with thinking off. It explains well but
    advises badly (it said LA-1006's documents must be corrected; a funder can waive 1¢).

## 12c. Owner decision: no paid LLM (2026-11-21)

Only the local model (Gemma via Ollama) is used, for building, testing and later use. What
follows from that:
- **Context window.** Ollama loads `gemma4:e4b` with a **4096-token context** by default. The A2UI
  system prompt that `a2ui-adk` adds (catalog plus examples, ~10K tokens for the basic catalog)
  **doesn't fit**. Any feature where the model writes A2UI needs a larger `num_ctx` (more memory),
  or the compact `express` format, or `skill_mode`, or a small custom catalog. Routine assistant
  answers stay structured JSON that code renders (~400-token prompts).
- **Evals:** the A2UI harness runs `--profile offline` (`--no-judge`), so it checks structure
  only, since the judge needs Gemini. Quality checks are our own deterministic assertions plus
  recorded reference answers.
- **Tests** replay recorded model responses. Nothing in CI needs a model.
- **Speed budget:** ~1–2 s with thinking off, ~8 s with it on (§12b). The UI shows the assistant
  panel as "thinking…", and the fixed screens never wait for it.

### Requirement: the model is configuration, not code (owner, 2026-11-21)

Local Gemma is the **default**, not a dependency. Replacing it with any other model, paid
(Claude, Gemini, OpenAI…) or a bigger local one, must be a configuration change only:
- **One setting names the model** as a LiteLLM model string, e.g. `ollama_chat/gemma4:e4b` (the
  default), `anthropic/claude-haiku-5-5` or `gemini/<model>`, plus the provider's credentials
  from the environment. No provider name appears in code.
- **Capabilities are declared per model in config, not assumed:**
  - **Context window:** decides whether a feature can carry the A2UI catalog prompt.
  - **Structured output:** when a model supports it, use it. Otherwise (Gemma on MLX), ask for
    JSON in the prompt, strip the fence, validate and retry. **Code-side validation runs in both
    cases.**
  - **Thinking control:** how to turn thinking on or off for that provider.
  - **Cost per token:** so a paid model gets the usage and cost meter, with an optional spend
    stop. It stays at $0 for local.
- **Prompts are provider-neutral:** plain instructions plus the JSON schema, with no
  model-specific tricks in the prompt text.
- **Tests never depend on the model:** recorded responses replay for any configured model, and
  the A2UI offline eval can be pointed at whichever model is configured, to compare before
  switching.
- **Per-feature override:** a feature can name its own model (e.g. summaries on local Gemma,
  generated UI on a stronger model) without code changes.

## 12d. Spike D0 findings (2026-11-21, measured, with code in `dashboard/`)

**What works end to end:**
1. The browser asks the agent over A2A.
2. The agent replies with A2UI v1.0, built by code with no LLM.
3. React renders it, including our custom `DataTable`.
4. A click goes back as a v1.0 `action`.
5. Agent code writes through the funder API and re-reads the deal.
6. The surface updates, and the write is in the audit trail.

**Versions that work together** (pinned by `package-lock.json` and `uv.lock`):

| Side | Packages |
|---|---|
| Web | `@a2ui/react` **0.15.0**, `@a2ui/web_core` **0.15.0** (the npm releases; 0.16.0 is unreleased), React 19.3.1, Vite 8.3.8, zod 3.25.76, `@playwright/test` 1.65.1 |
| Agent | Python 3.12.13 (`uv`), `a2a-sdk` **0.3.26** (the A2UI packages need the 0.3 line, not 1.x), `a2ui-a2a` 0.4.0, `a2ui-agent-sdk` 0.12.0, `a2ui-core` 0.7.0, uvicorn 0.54.0, httpx. `google-adk` and `a2ui-adk` aren't needed until the assistant (D8). |

**How to use v1.0 with the React renderer:** the README's examples are v0.9, but v1.0 works:
- `MessageProcessor` from `@a2ui/web_core/v1_0`;
- `basicCatalog` from `@a2ui/web_core/catalogs/basic/v1`;
- `A2uiSurface` and `createComponentImplementation` from the `@a2ui/react` **root**;
- `Catalog` and `CommonSchemas` from `web_core/v1_0`, with the catalog version `'1.0'`.

The types are declared against v0.9 models, so a few `as never` casts are needed.

**Transport, plain A2A JSON-RPC:**
- `POST /` with `{"jsonrpc":"2.0","method":"message/send","params":{"message":{…}}}`.
- The `X-A2A-Extensions: https://a2ui.org/a2a-extension/a2ui/v1.0` header.
- Renderer capabilities in `message.metadata.a2uiRendererCapabilities = {"1.0": {"supportedCatalogIds": [...]}}`.
- A2UI travels in `DataPart`s with `metadata.mimeType = "application/a2ui+json"`, a text part for requests and a data part `{version, action}` for clicks.
- The browser uses a Vite proxy (`/agent`), so no `@a2a-js/sdk` is needed.

**Routing:** our own `a2a-sdk` `AgentExecutor` (as the official sample does), not ADK's
`before_agent_callback`. `get_a2ui_messages(context.message)` yields the incoming `action`s;
`create_a2ui_parts(messages, version="1.0")` wraps the replies. ADK sits behind it only for the
assistant.

**Pitfalls:**
- **Strict validation, and one bad prop blanks the page.** For example, `Text.variant` allows
  only `caption | body` (no `h2`). Validate on the agent before sending (NFR-05), and give the web
  app an error boundary.
- **`@catalogId` on a component is rejected** by `web_core` 0.15.0 ("Unrecognized reserved
  protocol directive"), despite the v1.0 spec. So **one catalog per surface**: our catalog
  *extends* the Basic Catalog (`[...basicCatalog.components.values(), ours]`, plus its
  functions), and surfaces use our `catalogId`.
- **A duplicate `createSurface` is an integrity error** ("Surface … already exists"). React
  StrictMode runs effects twice in dev, and agents may resend, so skip `createSurface` for a
  surface that already exists (the official sample does the same).
- **`@accessibility` on a component is rejected** by `web_core` 0.15.0 ("Unrecognized key(s) in
  object: '@accessibility'"), like `@catalogId`. Use the older `accessibility` key. `a2ui-core`
  0.7.0 (Python) accepts both, so its validator can't catch this; an agent test checks for both
  reserved keys (T106, 2026-11-21).
- **`Modal`** opens on its `trigger` and closes only by its own × or the backdrop, not when a
  button inside it is clicked. The trigger button's own event is still sent. To close it after a
  confirm, rebuild the surface: `deleteSurface`, then `createSurface` (T106).
- **`weight` is layout (flex), not font weight.**
- **`Text` renders through a markdown step.** Without `@a2ui/markdown-it` it warns "no markdown
  renderer configured". `@a2ui/react` 0.15.0 pulls `@a2ui/markdown-it` 0.2.2, which declares a
  peer of `web_core` ^0.14 (an npm warning; harmless so far).
- **The renderer resolves `@path` bindings in an action's `context` before sending.** The agent
  receives plain values (e.g. `"applicationVersionId": 12`).

## 13. ADK integration (`pip install a2ui-adk`, `"a2ui-adk[a2a]"` for A2A)

- **`SendA2uiToClientToolset`:** an ADK toolset that lets the LLM send A2UI through a **tool
  call**. It adds the catalog prompt to the instructions, compiles the tool call, and
  **validates the A2UI JSON before sending**. It's configured with an `A2uiRequestProcessor`
  (catalogs, example turns, inference format, `RendererState`) or provider callbacks.
- **`A2uiInstruction`:** an instruction *provider* for an agent that writes A2UI in its text.
  ⚠ A plain-string instruction fails with `KeyError: "Context variable not found: 'expression'"`,
  because ADK treats the `${…}` braces in the A2UI prompt as state templates. Use the provider.
- **`A2uiProcessorStore`:** keeps each session's `A2uiRequestProcessor` in memory, because ADK's
  session state is JSON.
- **`A2uiRendererCallsStore`:** keeps each session's pending `callRendererFunction` calls.
- **With the `a2a` extra:**
  - `A2uiEventConverter` / `A2uiPartConverter` turn ADK events into A2A parts carrying A2UI;
    pass them to ADK's `A2aAgentExecutor`.
  - `a2ui_rpc_callback` (a `before_agent_callback`) answers `callAgentFunction` **without running
    the LLM**.
  - `a2ui_rpc_interceptor` completes tasks that carry only RPC messages.
  - `exclude_a2ui_rpc_from_prompt` keeps RPC messages out of the model's context.
  - `A2uiSubagentMap` tracks which subagent owns which surface.

## 14. What this means for the funder dashboard (my reading)

1. **An agent service in Python with ADK and `a2ui-adk`**, in front of the existing Java funder
   API. Its tools are the funder API endpoints. The backend stays Java.
2. **Fixed core screens don't need the LLM.** A2UI is just JSON messages, so plain code can build
   `createSurface` + `updateComponents` + `updateDataModel` from API data. That makes them
   deterministic and testable. Use `SendA2uiToClientToolset` only for the assistant panel
   (summary, suggestions, drafted stips).
3. **A custom catalog** (our own `catalogId`) for what the basic one lacks:
   - `DataTable` for expected vs actual, stips and the audit trail;
   - `DocumentPageViewer` for a page image with bounding-box overlays;
   - `StatusBadge` / `ConfidenceTag`.

   We'd implement each in the chosen renderer (React or Angular look like the obvious web
   choices).
4. **Writes:** a `Button` action event goes to the agent, the agent calls the funder API with the
   funder's identity, and the result comes back as an `updateDataModel`. The funder API stays
   the only place that authorizes a write. The assistant can *propose* an action, but only the
   funder's click sends it.
5. **Use underscores in every component ID** (UAX #31). Don't send themes; style in the renderer.
6. **Watch the version.** Pin to v1.0, and check that the chosen renderer package implements
   v1.0, because some samples are v0.9.

## 15. Not read yet (do when needed)

- ~~The A2A extension spec~~: read in the spike; see §12d.

- `specification/v1_0/docs/a2ui_custom_functions.md` and `evolution_guide.md`
- The A2A extension spec (its URI and A2A part format)
- `catalogs/basic/v1/basic_catalog_implementation_guide.md`
- The React and Angular renderer READMEs (v1.0 status, how to register custom components)
- `samples/agent/adk/custom-components-example` (to model the document viewer on)
