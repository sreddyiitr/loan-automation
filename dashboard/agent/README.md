# funder-agent (spec 002)

An A2A server that answers with A2UI v1.0 surfaces built by code (no LLM). It relays the browser's
OIDC bearer token to spec 001's funder API and never creates a token of its own (FR-11).

```bash
uv run python main.py   # port 8091
uv run pytest           # unit tests, no services needed
```

| Path | What it is |
|---|---|
| `funder_agent/server.py` | The A2A app and our `AgentExecutor`. `route()` opens a screen for a text request, or runs the handler for an A2UI action. |
| `funder_agent/api/` | The funder API client (`client.py`), and reason → message (`messages.py`, from `messages.yaml`, FR-06) |
| `funder_agent/surfaces/` | Pure builders: data in, A2UI messages out. `overview.py` (`deal_<versionId>_overview`) and `notice.py` |
| `funder_agent/actions/` | Action handlers: `deal.take` |
| `funder_agent/catalog.py` | Our catalog (the Basic Catalog plus `DataTable`) for validation. Keep it in step with `web/src/catalog/funderCatalog.tsx`. |
| `funder_agent/config.py` | `FUNDER_API_URL`, `TEMPORAL_UI_URL`, `TEMPORAL_NAMESPACE` and `AGENT_PORT`, each overridable by environment variable |
| `tests/` | Every surface is run through `a2ui-core`'s strict `MessageProcessor` with our catalog (NFR-05). The rest run against a fake funder API. |

See [`learnings/ui_summary.md`](../../learnings/ui_summary.md) for running the whole dashboard, and
[`learnings/a2ui_overview.md`](../../learnings/a2ui_overview.md) for how the code works.
