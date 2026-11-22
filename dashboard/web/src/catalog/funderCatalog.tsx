// Our custom catalog (plan §6): the Basic Catalog has no table, status badge, heading or fact list, so we add them.
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
    title: CommonSchemas.DynamicString.optional(),
    emptyText: CommonSchemas.DynamicString.optional(),
  }),
};

const DataTable = createComponentImplementation(DataTableApi as never, ({props}) => {
  const p = props as {columns: {key: string; title: string}[]; rows?: unknown; title?: string; emptyText?: string};
  const rows = Array.isArray(p.rows) ? (p.rows as Record<string, unknown>[]) : [];
  return (
    <section className="data-table">
      {p.title && <h3 className="section-title">{p.title}</h3>}
      {rows.length === 0 ? <p className="muted">{p.emptyText ?? 'Nothing to show'}</p> : (
        <table>
          <thead>
            <tr>{p.columns.map((c) => <th key={c.key}>{c.title}</th>)}</tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>{p.columns.map((c) => <td key={c.key}>{String(r[c.key] ?? '')}</td>)}</tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
});

// Heading (D1c): the basic Text allows only caption and body, so titles need their own component.
export const HeadingApi = {
  name: 'Heading',
  schema: z.object({text: CommonSchemas.DynamicString, level: z.number().int().min(1).max(3).optional()}),
};

const Heading = createComponentImplementation(HeadingApi as never, ({props}) => {
  const p = props as {text?: unknown; level?: number};
  const Tag = (['h1', 'h2', 'h3'] as const)[(p.level ?? 2) - 1];
  return <Tag className={`heading heading-${p.level ?? 2}`}>{String(p.text ?? '')}</Tag>;
});

// FactList (D1c): label/value pairs as a two-column grid, from a list of {label, value}.
export const FactListApi = {
  name: 'FactList',
  schema: z.object({items: CommonSchemas.DynamicValue}),
};

const FactList = createComponentImplementation(FactListApi as never, ({props}) => {
  const items = Array.isArray((props as {items?: unknown}).items)
    ? ((props as {items: {label: string; value: string}[]}).items) : [];
  return (
    <dl className="fact-list">
      {items.map((f) => (
        <div className="fact" key={f.label} data-label={f.label}>
          <dt>{f.label}</dt>
          <dd>{f.value}</dd>
        </div>
      ))}
    </dl>
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
  return (
    <span className={`status-badge status-${TONE[status] ?? 'neutral'}`} data-status={status}>
      <span className="status-dot" aria-hidden="true" />Status: {label}
    </span>
  );
});

// CheckList and Timeline (AS-2.7, "What's been done"): what the system and people already handled.
// The outcome or tone is always written out; colour and icon only group (NFR-04).
const listSchema = z.object({
  title: CommonSchemas.DynamicString.optional(),
  items: CommonSchemas.DynamicValue,
  emptyText: CommonSchemas.DynamicString.optional(),
});
const asList = <T,>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);
const ICON: Record<string, string> = {done: '✓', refused: '✕', waiting: '!', neutral: '•'};

export const CheckListApi = {name: 'CheckList', schema: listSchema};

const CheckList = createComponentImplementation(CheckListApi as never, ({props}) => {
  const p = props as {title?: string; items?: unknown};
  const items = asList<{check: string; outcome: string; label: string; detail: string}>(p.items);
  return (
    <section className="check-list">
      {p.title && <h4 className="mini-title">{p.title}</h4>}
      <ul>
        {items.map((c) => (
          <li key={c.check} className={`check tone-${c.outcome}`} data-check={c.check}>
            <span className="check-icon" aria-hidden="true">{ICON[c.outcome] ?? '•'}</span>
            <span className="check-name">{c.check}</span>
            <span className="check-label">{c.label}</span>
            {c.detail && <span className="check-detail">{c.detail}</span>}
          </li>
        ))}
      </ul>
    </section>
  );
});

export const TimelineApi = {name: 'Timeline', schema: listSchema};

const Timeline = createComponentImplementation(TimelineApi as never, ({props}) => {
  const p = props as {title?: string; items?: unknown; emptyText?: string};
  const items = asList<{when: string; title: string; actor: string; tone: string}>(p.items);
  return (
    <section className="timeline">
      {p.title && <h4 className="mini-title">{p.title}</h4>}
      {items.length === 0 ? <p className="muted">{p.emptyText ?? 'Nothing yet'}</p> : (
        <ol>
          {items.map((e, i) => (
            <li key={i} className={`event tone-${e.tone}`}>
              <span className="event-dot" aria-hidden="true" />
              <span className="event-title">{e.title}</span>
              <span className="event-meta">{e.actor} · {e.when}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
});

// The published web_core (0.15.0) rejects a per-component "@catalogId", so a surface can use only one
// catalog. Our catalog therefore EXTENDS the Basic Catalog: all 18 basic components and 15 functions,
// plus ours. Surfaces are created with this catalog's id.
export const funderCatalog = new Catalog(
  FUNDER_CATALOG_ID,
  '1.0' as never,
  [...basicCatalog.components.values(), DataTable, StatusBadge, Heading, FactList, CheckList, Timeline] as never,
  [...basicCatalog.functions.values()] as never,
);
