# A2UI overview: how the dashboard code works

An introduction to A2UI for newcomers, using the funder dashboard code in `dashboard/` as the
worked example. It started as spike D0; T104 split the agent into the `funder_agent` package, so
the names below are where the code lives now. How the user's token travels is explained in
[`oidc_authn_authz_summary.md`](oidc_authn_authz_summary.md). For the protocol details see
[`a2ui-notes.md`](../specs/002-funder-dashboard/a2ui-notes.md). For what the spike found see §12d of
that file, and for what the dashboard will do see [`ui_summary.md`](ui_summary.md).

## The idea behind A2UI

Normally the front end decides what each screen looks like. With A2UI, the **agent** (a server)
decides. It sends JSON that says "a Card containing a Column, which contains these Texts and a
Button". The browser turns that JSON into real widgets. It only builds widgets it already has code
for, so no code travels over the wire, only a description.

You need five words to follow the code:

| Term | Meaning | In this spike |
|---|---|---|
| **Surface** | One screen area the agent controls | `deal_12_overview` (`deal_<versionId>_overview`) |
| **Component** | One widget in a surface, with an `id` | `title`, `history`, `take_button`, … |
| **Catalog** | The widgets the browser knows how to draw | Basic Catalog (Text, Card, Button, …) plus our `DataTable` |
| **Data model** | A JSON document attached to the surface | `/deal` → `{status, dealershipName, versions: […]}` |
| **Action** | A click, sent back to the agent as JSON | `deal.take` |

The key point: **layout and data are separate.** Components say *where* to show something, using
a path like `{"@path": "/deal/status"}`. The data model holds the *value*. To change what's on
screen you usually just send new data; the layout stays.

## The pieces

```
Browser (dashboard/web)                  Agent (dashboard/agent/funder_agent)            Funder API (Java, spec 001)
  App.tsx       – holds the screen state   server.py   route() – routing                 GET  /applications/LA-1006
  a2a.ts        – sends/receives JSON-RPC ⇄ surfaces/overview.py – builds JSON       ⇄   GET  /applications/LA-1006/versions
  auth.ts       – sign-in, token in memory  actions/deal.py – open_deal, take             PUT  /versions/{id}/assignee
  funderCatalog – our DataTable widget      api/client.py – HTTP calls, relays the token
```

**A2A** is only the envelope: a JSON-RPC `POST` with `method: "message/send"`. A2UI messages ride
inside it as "data parts" tagged `application/a2ui+json`. In development the browser reaches the
agent through a Vite proxy (`/agent` → port 8091, see `dashboard/web/vite.config.ts`).

## Flow 1: opening the page

**1. The browser asks.** After you sign in and type a loan id, `App.tsx` calls `sendToAgent('open LA-1006')`. In
`a2a.ts`, that string goes in as a **text part**. The metadata tells the agent which catalogs the
browser can draw (`a2uiRendererCapabilities` → `supportedCatalogIds`), and the
`X-A2A-Extensions` header turns on A2UI v1.0.

**2. The agent routes.** In `funder_agent/server.py`, `FunderAgentExecutor.execute()` reads the
browser's bearer token and hands the request to `route()`, which checks for incoming actions. There are none, so this is a text request. It pulls `LA-1006` out with a regex and calls:
- `actions/deal.py` `load()`, which makes two GETs to the funder API (the overview and the
  version history) with the user's own token, then `deal_model()` shapes them into the data the
  screen needs: "29 days left", "Paid: DSB-42, $25,000.00" and so on.
- `surfaces/overview.py` `overview_surface(deal)`, a **pure function**: deal in, A2UI JSON out.
  No LLM is involved.

**3. The agent replies with three A2UI messages**, always in this order:

```jsonc
// a) "There is a surface called deal_12_overview; draw it with the funder catalog."
{"version":"1.0","createSurface":{"surfaceId":"deal_12_overview","catalogId":"https://funding.local/…/funder/v1/catalog.json"}}

// b) "Here is its layout": a flat list of components that point to each other by id
{"version":"1.0","updateComponents":{"surfaceId":"deal_12_overview","components":[
  {"id":"root",  "component":"Card",   "child":"body"},
  {"id":"body",  "component":"Column", "children":["title","status","dealer", …,"actions","history"]},
  {"id":"title", "component":"Text",   "text":{"@call":"formatString","args":{"value":"${/deal/loanApplicationId} · v${/deal/versionNumber}"}}},
  {"id":"status","component":"Text",   "text":{"@call":"formatString","args":{"value":"Status: ${/deal/status}"}}},
  {"id":"dealer","component":"Text",   "text":{"@call":"formatString","args":{"value":"Dealer: ${/deal/dealershipName}"}}}, // bound to data
  {"id":"history","component":"DataTable", "rows":{"@path":"/deal/versions"}, "columns":[…]},      // OUR widget
  {"id":"take_button","component":"Button","child":"take_label",
     "action":{"event":{"name":"deal.take","context":{"applicationVersionId":{"@path":"/deal/applicationVersionId"}, …}}}}
]}}

// c) "Here is its data"
{"version":"1.0","updateDataModel":{"surfaceId":"deal_12_overview","path":"/deal",
  "value":{"loanApplicationId":"LA-1006","status":"STIPULATED","dealershipName":"Lone Star Motors",
           "expiry":"29 days left (2026-12-21)","versions":[{"version":"v1","note":"latest",…}], …}}}
```

The component list is **flat**: a tree built from ids (`root` → `body` → its children), not
nested JSON. That lets the agent later update one component without resending the whole tree.

There are two kinds of binding:
- `{"@path": "/deal/x"}` reads a value from the data model.
- `{"@call": "formatString", …}` calls a catalog function. Here it fills `${/deal/status}` into
  a string.

**4. The browser draws it.** `App.tsx` passes the messages to a `MessageProcessor`, A2UI's state
engine from `@a2ui/web_core`. It holds every surface's components and data. `<A2uiSurface>` from
`@a2ui/react` then renders each surface:
- It looks up each `component` name in the catalog.
- It resolves the `@path` and `@call` bindings against the data model.
- It draws real React elements.

`apply()` skips a `createSurface` for a surface that already exists, because A2UI treats a
duplicate as an error. React's StrictMode runs effects twice in development, and an agent may
resend a surface.

## Flow 2: clicking "Take this deal"

Take is offered only while the deal is unassigned (AS-2.6), and it asks before it acts.

**1. The click opens a confirmation.** "Take this deal" is the `trigger` of a Basic Catalog
`Modal`, and the renderer opens the dialog on its own. The trigger button still sends its own
event, `ui.confirm_take`. The agent ignores every `ui.` event, because only the confirm button
should act.

**2. The confirm button builds the real action.** Its `action.context` contains `@path`
bindings, and the renderer **fills them in before sending**, so the agent gets plain values:

```json
{"name":"deal.take","surfaceId":"deal_12_overview","context":{"loanApplicationId":"LA-1006","applicationVersionId":12}}
```

**3. The browser forwards it.** The callback given to `MessageProcessor` in `App.tsx` gets that
action and sends it as `{version: "1.0", action}`. This time `a2a.ts` sends it as a **data part**,
not text. The page also shows it under "Events sent back to the agent" so the test can check it.

**4. The agent handles it in code.** `route()` looks up the handler for `deal.take` in
`actions/deal.py`. The handler:
- Calls the funder API's `POST /versions/12/take` with the user's own token. The API assigns the
  deal to the token's `sub`, but only if nobody has it, checked under a database lock. That's a
  real write, and it appears in the audit trail.
- Calls `load()` again to read back what's actually stored.
- **Rebuilds** the surface: `deleteSurface`, then `createSurface`, `updateComponents` and
  `updateDataModel`. A plain data update isn't enough this time, for two reasons. Which controls
  exist changed (no more Take), and the `Modal` closes only by its × or the backdrop, not when a
  button inside it is clicked. Deleting the surface closes it.
- If the API refused (say someone else took it first: 409 `ALREADY_ASSIGNED`), it still rebuilds
  from the re-read, so the screen shows who has it, and adds a `notice` saying why.

**5. The screen updates.** `apply()` in `App.tsx` keeps a `createSurface` that follows a
`deleteSurface` of the same id. Otherwise it skips duplicate creates. The new surface shows
"Assigned to: docs-1 (you)" and no Take button.

This is the pattern the whole dashboard will use: **the funder clicks, code writes through the
API, code re-reads, and the agent sends the updated screen.** When only values change, that's a
data update; when the controls change, it's a rebuild. The LLM is never involved in a write.

## The custom component (`dashboard/web/src/catalog/funderCatalog.tsx`)

The Basic Catalog has no table, so we add one. A component has two halves:

1. **Its API:** a name (`DataTable`) and a Zod schema for its props. Using
   `CommonSchemas.DynamicValue` for `rows` is what lets `{"@path": "/deal/versions"}` be resolved
   into the actual list before our code sees it.
2. **Its implementation:** an ordinary React function (`createComponentImplementation`) that
   receives the resolved props and returns a `<table>`.

Then we build our catalog as **all of the Basic Catalog plus `DataTable`**. That's because
`web_core` 0.15.0 only lets a surface use one catalog, even though the v1.0 spec says components
can come from more than one. It's why `createSurface` uses our catalog's id, not the basic one.

The catalog is also the **security boundary**: the agent can only ask for widgets the browser has
registered. An unknown component, or an invalid prop like `Text.variant: "h2"`, is rejected. In
0.15.0 that blanks the whole surface, which is why the plan adds validation on the agent side and
an error boundary in the web app.

## The tests

- **Agent unit tests** (`cd dashboard/agent && uv run pytest`, no services needed). Every surface
  is run through `a2ui-core`'s strict `MessageProcessor` with our catalog: the same checks the
  browser renderer makes, so a bad prop fails here and not as a blank page. Also tested: the data
  model's text, the token relay, the reason messages and the routing, against a fake funder API.
- **Playwright** (`dashboard/web/e2e/spike.spec.ts`), against the real stack, each test signing
  in on the provider's login page:
  - The overview renders with real LA-1006 data, including the version history `DataTable`.
  - Take opens the confirmation; confirming assigns the deal to you and removes Take.
  - If someone else takes it first, the screen says so and shows who has it.
  - An unknown loan id, an unknown user and the signed-out page each get a plain message.
  - The browser console has no errors.

How to run them is in [`ui_summary.md`](ui_summary.md) under "Run the spike".

## Suggested reading order

1. **`dashboard/agent/funder_agent/surfaces/overview.py`, `overview_surface()`:** the A2UI JSON itself. Compare each
   component with what you see on screen.
2. **`funder_agent/server.py`, `route()`:** the two routes (text request vs. action), and what
   happens with no token or a refusal (the `notice` surface).
3. **`dashboard/web/src/App.tsx`:** processor, surface and action callback.
4. **`dashboard/web/src/a2a.ts`:** only the envelope, so skim it.
5. **`dashboard/web/src/catalog/funderCatalog.tsx`:** the custom widget.
6. **[`a2ui-notes.md`](../specs/002-funder-dashboard/a2ui-notes.md):** start with §12d (what the
   spike learned), then the earlier sections on the protocol.

## A first experiment

In `overview_surface()`, change `"Status: ${/deal/status}"` to some other text, or add a `Text`
component with a new id to `body`'s `children`. Restart the agent and reload the page. The screen
changes without touching any React code, which is the point of A2UI.
