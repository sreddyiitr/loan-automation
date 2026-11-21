# Funder dashboard web app (spec 002)

React 19 + Vite + `@a2ui/react` 0.15.0. It renders the A2UI v1.0 surfaces that the agent
(`../agent`, port 8091) sends, using our catalog (`src/catalog`), which extends the Basic Catalog
with `DataTable`. `/agent` is proxied to the agent (`vite.config.ts`). `src/auth.ts` signs the user in
(OIDC Authorization Code with PKCE via `oidc-client-ts`, client `funder-dashboard`; override the
issuer with `VITE_OIDC_ISSUER`) and holds the token in memory; `a2a.ts` sends it to the agent.
`src/catalog/funderCatalog.tsx` adds `DataTable` and `StatusBadge`.

```bash
npm install
npm run dev            # http://localhost:5173
npx playwright test    # e2e/spike.spec.ts; needs the agent, `make run` with LA-1006 submitted, and the OIDC provider with scripts/oidc-config.json
```

See [`learnings/ui_summary.md`](../../learnings/ui_summary.md) and
[`a2ui-notes.md`](../../specs/002-funder-dashboard/a2ui-notes.md) §12d.
