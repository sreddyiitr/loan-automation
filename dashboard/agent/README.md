# funder-agent (spec 002, spike D0)

An A2A server that answers with A2UI v1.0, routed in code with no LLM. It reads and writes
through spec 001's funder API on `localhost:8080`.

```bash
uv run python main.py   # port 8091
```

See [`ui_summary.md`](../../ui_summary.md) for how to run the whole spike, and
[`a2ui-notes.md`](../../specs/002-funder-dashboard/a2ui-notes.md) §12d for the findings.
