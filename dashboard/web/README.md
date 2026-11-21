# Funder dashboard web app (spec 002)

React 19 + Vite + `@a2ui/react` 0.15.0. It renders the A2UI v1.0 surfaces that the agent
(`../agent`, port 8091) sends, using our catalog (`src/catalog`), which extends the Basic Catalog
with `DataTable`. `/agent` is proxied to the agent (`vite.config.ts`). `src/auth.ts` holds the
user's token in memory and `a2a.ts` sends it to the agent. Until T105 adds a sign-in screen, open
the page with `#access_token=<token>` (dev server only; see `ui_summary.md`).

```bash
npm install
npm run dev            # http://localhost:5173
npx playwright test    # e2e/spike.spec.ts; needs the agent, `make run` with LA-1006 submitted, and the OIDC provider
```

See [`ui_summary.md`](../../ui_summary.md) and
[`a2ui-notes.md`](../../specs/002-funder-dashboard/a2ui-notes.md) §12d.
