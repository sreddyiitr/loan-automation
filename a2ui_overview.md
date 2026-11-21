# A2UI overview: how the spike D0 code works

An introduction to A2UI for newcomers, using the spec 002 spike in `dashboard/` as the worked
example. For the protocol details see
[`a2ui-notes.md`](specs/002-funder-dashboard/a2ui-notes.md). For what the spike found see §12d of
that file, and for what the dashboard will do see [`ui_summary.md`](ui_summary.md).

## The idea behind A2UI

Normally the front end decides what each screen looks like. With A2UI, the **agent** (a server)
decides. It sends JSON that says "a Card containing a Column, which contains these Texts and a
Button". The browser turns that JSON into real widgets. It only builds widgets it already has code
for, so no code travels over the wire, only a description.

You need five words to follow the code:

| Term | Meaning | In this spike |
|---|---|---|
| **Surface** | One screen area the agent controls | `deal_overview` |
| **Component** | One widget in a surface, with an `id` | `title`, `stips`, `take_button`, … |
| **Catalog** | The widgets the browser knows how to draw | Basic Catalog (Text, Card, Button, …) plus our `DataTable` |
| **Data model** | A JSON document attached to the surface | `/deal` → `{status, dealershipName, stips: […]}` |
| **Action** | A click, sent back to the agent as JSON | `deal.take` |

The key point: **layout and data are separate.** Components say *where* to show something, using
a path like `{"@path": "/deal/status"}`. The data model holds the *value*. To change what's on
screen you usually just send new data; the layout stays.

## The pieces

```
Browser (dashboard/web)                       Agent (dashboard/agent/main.py)        Funder API (Java, spec 001)
  App.tsx       – holds the screen state        FunderAgentExecutor – routing          GET  /applications/LA-1006
  a2a.ts        – sends/receives JSON-RPC  ⇄    overview_surface()  – builds JSON  ⇄   GET  /versions/{id}/stipulations
  funderCatalog – our DataTable widget          load_deal / take_deal – HTTP calls     PUT  /versions/{id}/assignee
```

**A2A** is only the envelope: a JSON-RPC `POST` with `method: "message/send"`. A2UI messages ride
inside it as "data parts" tagged `application/a2ui+json`. In development the browser reaches the
agent through a Vite proxy (`/agent` → port 8091, see `dashboard/web/vite.config.ts`).

## Flow 1: opening the page

**1. The browser asks.** When the page loads, `App.tsx` calls `sendToAgent('open LA-1006')`. In
`a2a.ts`, that string goes in as a **text part**. The metadata tells the agent which catalogs the
browser can draw (`a2uiRendererCapabilities` → `supportedCatalogIds`), and the
`X-A2A-Extensions` header turns on A2UI v1.0.

**2. The agent routes.** In `main.py`, `FunderAgentExecutor.execute()` checks for incoming
actions. There are none, so this is a text request. It pulls `LA-1006` out with a regex and calls:
- `load_deal()`, which makes two GETs to the funder API and shapes the result into the data the
  screen needs.
- `overview_surface(deal)`, a **pure function**: deal in, A2UI JSON out. No LLM is involved.

**3. The agent replies with three A2UI messages**, always in this order:

```jsonc
// a) "There is a surface called deal_overview; draw it with the funder catalog."
{"version":"1.0","createSurface":{"surfaceId":"deal_overview","catalogId":"https://funding.local/…/funder/v1/catalog.json"}}

// b) "Here is its layout": a flat list of components that point to each other by id
{"version":"1.0","updateComponents":{"surfaceId":"deal_overview","components":[
  {"id":"root",  "component":"Card",   "child":"body"},
  {"id":"body",  "component":"Column", "children":["title","status","dealer","assignee","stips","take_button"]},
  {"id":"title", "component":"Text",   "text":{"@path":"/deal/loanApplicationId"}},              // bound to data
  {"id":"status","component":"Text",   "text":{"@call":"formatString","args":{"value":"Status: ${/deal/status}"}}},
  {"id":"stips", "component":"DataTable", "rows":{"@path":"/deal/stips"}, "columns":[…]},         // OUR widget
  {"id":"take_button","component":"Button","child":"take_label",
     "action":{"event":{"name":"deal.take","context":{"applicationVersionId":{"@path":"/deal/applicationVersionId"}, …}}}}
]}}

// c) "Here is its data"
{"version":"1.0","updateDataModel":{"surfaceId":"deal_overview","path":"/deal",
  "value":{"loanApplicationId":"LA-1006","status":"STIPULATED","stips":[{"code":"STIP_PRICE_MISMATCH",…}], …}}}
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

**1. The renderer builds the action.** The Button's `action.context` contains `@path` bindings.
The renderer **fills them in before sending**, so the agent gets plain values:

```json
{"name":"deal.take","surfaceId":"deal_overview","context":{"loanApplicationId":"LA-1006","applicationVersionId":12}}
```

**2. The browser forwards it.** The callback given to `MessageProcessor` in `App.tsx` gets that
action and sends it as `{version: "1.0", action}`. This time `a2a.ts` sends it as a **data part**,
not text. The page also shows it under "Events sent back to the agent" so the test can check it.

**3. The agent handles it in code.** `execute()` finds the action and checks
`name == "deal.take"`. Then it:
- Calls `take_deal()`, which does `PUT /assignee` on the real funder API. That's a real write,
  and it appears in the audit trail.
- Calls `load_deal()` again to read back what's actually stored.
- Replies with **only** an `updateDataModel` for `/deal`, with no new layout.

**4. The screen updates.** The processor merges the new data, and the "Assigned to:" text
re-renders as `spike-funder`, because it was bound to `/deal/assignedFunder`. The layout never
changed; only the data did.

This is the pattern the whole dashboard will use: **the funder clicks, code writes through the
API, code re-reads, and the agent sends updated data.** The LLM is never involved in a write.

## The custom component (`dashboard/web/src/catalog/funderCatalog.tsx`)

The Basic Catalog has no table, so we add one. A component has two halves:

1. **Its API:** a name (`DataTable`) and a Zod schema for its props. Using
   `CommonSchemas.DynamicValue` for `rows` is what lets `{"@path": "/deal/stips"}` be resolved
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

## The test (`dashboard/web/e2e/spike.spec.ts`)

One Playwright test covers both flows against the real stack:
- The surface renders with real LA-1006 data, including the `DataTable` rows.
- Clicking the button sends a `deal.take` action with resolved context.
- The written assignee shows up after the re-read.
- The browser console has no errors.

How to run it is in [`ui_summary.md`](ui_summary.md) under "Run the spike".

## Suggested reading order

1. **`dashboard/agent/main.py`, `overview_surface()`:** the A2UI JSON itself. Compare each
   component with what you see on screen.
2. **`execute()`, just below it:** the two routes (text request vs. action).
3. **`dashboard/web/src/App.tsx`:** processor, surface and action callback.
4. **`dashboard/web/src/a2a.ts`:** only the envelope, so skim it.
5. **`dashboard/web/src/catalog/funderCatalog.tsx`:** the custom widget.
6. **[`a2ui-notes.md`](specs/002-funder-dashboard/a2ui-notes.md):** start with §12d (what the
   spike learned), then the earlier sections on the protocol.

## A first experiment

In `overview_surface()`, change `"Status: ${/deal/status}"` to some other text, or add a `Text`
component with a new id to `body`'s `children`. Restart the agent and reload the page. The screen
changes without touching any React code, which is the point of A2UI.
