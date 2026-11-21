// Our custom catalog (plan §6): the Basic Catalog has no table or status badge, so we add them.
// Keep it in step with dashboard/agent/funder_agent/catalog.py.
// A component = an API (Zod schema built from CommonSchemas, so bindings resolve) + a React implementation.
import {z} from 'zod';
import {Catalog, CommonSchemas} from '@a2ui/web_core/v1_0';
import {createComponentImplementation} from '@a2ui/react';
import {basicCatalog} from '@a2ui/web_core/catalogs/basic/v1';

export const FUNDER_CATALOG_ID = 'https://funding.local/a2ui/catalogs/funder/v1/catalog.json';

export const DataTableApi = {
  name: 'DataTable',
  schema: z.object({
    columns: z.array(z.object({key: z.string(), title: z.string()})),
    rows: CommonSchemas.DynamicValue, // usually {"@path": "/deal/versions"}: resolved to the list itself
    emptyText: CommonSchemas.DynamicString.optional(),
  }),
};

const DataTable = createComponentImplementation(DataTableApi as never, ({props}) => {
  const p = props as {columns: {key: string; title: string}[]; rows?: unknown; emptyText?: string};
  const rows = Array.isArray(p.rows) ? (p.rows as Record<string, unknown>[]) : [];
  if (rows.length === 0) return <p>{p.emptyText ?? 'Nothing to show'}</p>;
  return (
    <table style={{borderCollapse: 'collapse', width: '100%'}}>
      <thead>
        <tr>{p.columns.map((c) => <th key={c.key} style={{textAlign: 'left', borderBottom: '1px solid #ccc'}}>{c.title}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>{p.columns.map((c) => <td key={c.key}>{String(r[c.key] ?? '')}</td>)}</tr>
        ))}
      </tbody>
    </table>
  );
});

// StatusBadge (plan §6, NFR-04): the version status as a badge. The status is always written out,
// so it's never shown by colour alone; the colour only groups (waiting, done, refused).
export const StatusBadgeApi = {
  name: 'StatusBadge',
  schema: z.object({status: CommonSchemas.DynamicString}),
};

const TONE: Record<string, string> = {
  DRAFT: 'neutral', PENDING_REVIEW: 'busy', STIPULATED: 'waiting', APPROVED: 'busy',
  FUNDED: 'done', REJECTED: 'refused', EXPIRED: 'refused',
};

const StatusBadge = createComponentImplementation(StatusBadgeApi as never, ({props}) => {
  const status = String((props as {status?: unknown}).status ?? '');
  const label = status.replaceAll('_', ' ').toLowerCase();
  return <span className={`status-badge status-${TONE[status] ?? 'neutral'}`} data-status={status}>Status: {label}</span>;
});

// The published web_core (0.15.0) rejects a per-component "@catalogId", so a surface can use only one
// catalog. Our catalog therefore EXTENDS the Basic Catalog: all 18 basic components and 15 functions,
// plus ours. Surfaces are created with this catalog's id.
export const funderCatalog = new Catalog(
  FUNDER_CATALOG_ID,
  '1.0' as never,
  [...basicCatalog.components.values(), DataTable, StatusBadge] as never,
  [...basicCatalog.functions.values()] as never,
);
