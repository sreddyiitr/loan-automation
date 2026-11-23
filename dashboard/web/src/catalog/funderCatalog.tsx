// Our custom catalog (plan §6): the Basic Catalog has no table, status badge, heading or fact list, so we add them.
// Keep it in step with dashboard/agent/funder_agent/catalog.py.
// A component = an API (Zod schema built from CommonSchemas, so bindings resolve) + a React implementation.
import {useState} from 'react';
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
  PENDING: 'waiting', SATISFIED: 'done', WAIVED: 'done',             // stips (D3)
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

type Comparison = {what: string; expected: string; found: string; ok: boolean};
type CheckItem = {check: string; outcome: string; label: string; detail: string; reason?: string; open?: boolean;
  comparisons?: Comparison[]};

// One check: a button that opens and closes what it compared. Opening is local UI state (no round
// trip to the agent); `open` is only where it starts (checks that didn't pass start open).
function CheckRow({c}: {c: CheckItem}) {
  const [open, setOpen] = useState(Boolean(c.open));
  const comparisons = c.comparisons ?? [];
  const canOpen = comparisons.length > 0 || Boolean(c.reason);
  return (
    <li className={`check tone-${c.outcome}${open ? ' is-open' : ''}`} data-check={c.check}>
      <button type="button" className="check-head" aria-expanded={open} disabled={!canOpen}
              onClick={() => setOpen((o) => !o)}>
        <span className="check-icon" aria-hidden="true">{ICON[c.outcome] ?? '•'}</span>
        <span className="check-text">
          <span className="check-name">{c.check}</span>
          {c.reason && <span className="check-reason">{c.reason}</span>}
        </span>
        <span className="check-label">{c.label}</span>
        {canOpen && <span className="check-chevron" aria-hidden="true" />}
      </button>
      {open && (
        <div className="check-body">
          {comparisons.length > 0 && (
            <table className="comparisons">
              <thead><tr><th aria-label="Matched" /><th>What was compared</th><th>Expected</th><th>Found</th></tr></thead>
              <tbody>
                {comparisons.map((x, i) => (
                  <tr key={i} className={x.ok ? 'ok' : 'bad'}>
                    <td className="cmp-mark">{x.ok ? '✓' : '✕'}<span className="sr-only">{x.ok ? 'matches' : "doesn't match"}</span></td>
                    <td>{x.what}</td><td className="num">{x.expected}</td><td className="num">{x.found}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {c.detail && <p className="check-detail">{c.detail}</p>}
        </div>
      )}
    </li>
  );
}

const CheckList = createComponentImplementation(CheckListApi as never, ({props}) => {
  const p = props as {title?: string; items?: unknown};
  const items = asList<CheckItem>(p.items);
  return (
    <section className="check-list">
      {p.title && <h4 className="mini-title">{p.title}</h4>}
      <ul>{items.map((c) => <CheckRow key={c.check} c={c} />)}</ul>
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

// WorkList (AS-1.8, "My work"): deals as clickable cards. A click sends the A2UI action named by
// openAction, with the row's loan id, through the component's context, the same path a Button takes.
export const WorkListApi = {
  name: 'WorkList',
  schema: z.object({
    title: CommonSchemas.DynamicString.optional(),
    items: CommonSchemas.DynamicValue,
    emptyText: CommonSchemas.DynamicString.optional(),
    openAction: z.string(),
  }),
};

type WorkItem = {loanApplicationId: string; versionNumber?: number; dealer: string; status: string; missing: string[];
  routedTo: string; expiry: string};

const WorkList = createComponentImplementation(WorkListApi as never, ({props, context}) => {
  const p = props as {title?: string; items?: unknown; emptyText?: string; openAction: string};
  const items = asList<WorkItem>(p.items);
  const open = (loanApplicationId: string) =>
    void (context as {dispatchAction: (a: unknown) => Promise<void>}).dispatchAction(
      {event: {name: p.openAction, context: {loanApplicationId}}});
  return (
    <section className="work-list">
      {p.title && <h3 className="section-title">{p.title} <span className="count">{items.length}</span></h3>}
      {items.length === 0 ? <p className="work-empty">{p.emptyText ?? 'Nothing here'}</p> : (
        <ul>
          {items.map((w) => (
            <li key={w.loanApplicationId}>
              <button type="button" className="work-card" data-loan={w.loanApplicationId}
                      aria-label={`Open ${w.loanApplicationId}`} onClick={() => open(w.loanApplicationId)}>
                <span className="work-head">
                  <span className="work-id">{w.loanApplicationId}{w.versionNumber ? <span className="work-version"> · v{w.versionNumber}</span> : null}</span>
                  <span className={`work-status status-${TONE[w.status] ?? 'neutral'}`} data-status={w.status}>
                    <span className="status-dot" aria-hidden="true" />{w.status.replaceAll('_', ' ').toLowerCase()}
                  </span>
                </span>
                <span className="work-dealer">{w.dealer} · {w.expiry}</span>
                <span className="work-missing">
                  <span className="work-missing-label">What's missing</span>
                  {w.missing.map((m, i) => <span key={i} className="work-chip">{m}</span>)}
                </span>
                <span className="work-foot">Routed to {w.routedTo}<span className="work-open" aria-hidden="true">Open →</span></span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
});

// SuggestionList (US-12): the assistant's assignment suggestions for the supervisor. Each row has its
// own Assign, confirmed in place (AS-12.4): nothing is assigned without that second click, and there is
// no "assign all". Confirming sends assignAction with the loan, its version and the assignee.
export const SuggestionListApi = {
  name: 'SuggestionList',
  schema: z.object({
    items: CommonSchemas.DynamicValue,
    emptyText: CommonSchemas.DynamicString.optional(),
    assignAction: z.string(),
  }),
};

type Suggestion = {loanApplicationId: string; applicationVersionId: number; assignee: string; assigneeRole: string;
  reason: string; source: string; sourceText: string; daysToExpiry?: number | null};

const SuggestionList = createComponentImplementation(SuggestionListApi as never, ({props, context}) => {
  const p = props as {items?: unknown; emptyText?: string; assignAction: string};
  const items = asList<Suggestion>(p.items);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [sent, setSent] = useState<string[]>([]);       // assigned from this panel: the notice says how it went
  const assign = (s: Suggestion) =>
    void (context as {dispatchAction: (a: unknown) => Promise<void>}).dispatchAction({event: {name: p.assignAction,
      context: {loanApplicationId: s.loanApplicationId, applicationVersionId: s.applicationVersionId, assignee: s.assignee}}});
  if (items.length === 0) return p.emptyText ? <p className="work-empty">{p.emptyText}</p> : null;
  return (
    <ul className="suggestion-list">
      {items.map((s) => (
        <li key={s.loanApplicationId} className="suggestion" data-loan={s.loanApplicationId} data-source={s.source}>
          <div className="suggestion-main">
            <span className="suggestion-head">
              <span className="work-id">{s.loanApplicationId}</span>
              <span className="suggestion-arrow" aria-hidden="true">→</span>
              <span className="suggestion-assignee">{s.assignee}</span>
              {s.assigneeRole && <span className="suggestion-role">{s.assigneeRole}</span>}
              {s.daysToExpiry != null && <span className="suggestion-days">{s.daysToExpiry} days left</span>}
            </span>
            <span className="suggestion-reason"><span className={`suggestion-source source-${s.source}`}>{s.sourceText}</span>{s.reason}</span>
          </div>
          {sent.includes(s.loanApplicationId) ? (
            <span className="suggestion-sent">Assigned ✓</span>
          ) : confirming === s.loanApplicationId ? (
            <span className="suggestion-confirm" role="group" aria-label={`Confirm assigning ${s.loanApplicationId}`}>
              <span>Assign {s.loanApplicationId} to {s.assignee}?</span>
              <button type="button" className="suggestion-yes" onClick={() => { setConfirming(null); setSent((d) => [...d, s.loanApplicationId]); assign(s); }}>Yes, assign</button>
              <button type="button" className="suggestion-no" onClick={() => setConfirming(null)}>Cancel</button>
            </span>
          ) : (
            <button type="button" className="suggestion-assign" aria-label={`Assign ${s.loanApplicationId} to ${s.assignee}`}
                    onClick={() => setConfirming(s.loanApplicationId)}>Assign</button>
          )}
        </li>
      ))}
    </ul>
  );
});

// StipList (D3, AS-3.1–3.5): each stip with who raised it and how it was resolved. Satisfy and Waive
// (Waive only when the API says the stip is waivable) each ask for a reason in place; Add stip picks a
// code from GET /stip-codes. Every write is the agent's code, after this click (FR-03).
export const StipListApi = {
  name: 'StipList',
  schema: z.object({
    title: CommonSchemas.DynamicString.optional(),
    items: CommonSchemas.DynamicValue,
    codes: CommonSchemas.DynamicValue.optional(),
    canAdd: CommonSchemas.DynamicValue.optional(),
    addReason: CommonSchemas.DynamicString.optional(),
    emptyText: CommonSchemas.DynamicString.optional(),
    loanApplicationId: CommonSchemas.DynamicValue,
    applicationVersionId: CommonSchemas.DynamicValue,
    resolveAction: z.string(),
    addAction: z.string(),
  }),
};

type Stip = {stipId: number; code: string; title: string; description: string; raisedBy: string; status: string;
  resolved: string; canSatisfy: boolean; canWaive: boolean; note: string};

const StipList = createComponentImplementation(StipListApi as never, ({props, context}) => {
  const p = props as {title?: string; items?: unknown; codes?: unknown; canAdd?: boolean; addReason?: string;
    emptyText?: string; loanApplicationId: string; applicationVersionId: number; resolveAction: string; addAction: string};
  const items = asList<Stip>(p.items);
  const codes = asList<{value: string; label: string}>(p.codes);
  const [open, setOpen] = useState<{stipId: number; status: string} | null>(null);
  const [text, setText] = useState('');
  const [adding, setAdding] = useState(false);
  const [code, setCode] = useState('');
  const send = (name: string, extra: Record<string, unknown>) =>
    void (context as {dispatchAction: (a: unknown) => Promise<void>}).dispatchAction({event: {name, context:
      {loanApplicationId: p.loanApplicationId, applicationVersionId: p.applicationVersionId, ...extra}}});
  const done = () => { setOpen(null); setAdding(false); setText(''); setCode(''); };
  const reasonBox = (label: string, onYes: () => void) => (
    <span className="suggestion-confirm" role="group" aria-label={label}>
      <input className="stip-reason" aria-label="Reason" placeholder="Reason (required)" value={text}
             onChange={(e) => setText(e.target.value)} autoFocus />
      <button type="button" className="suggestion-yes" disabled={!text.trim()} onClick={() => { onYes(); done(); }}>Confirm</button>
      <button type="button" className="suggestion-no" onClick={done}>Cancel</button>
    </span>
  );
  return (
    <section className="stip-list">
      {p.title && <h3 className="section-title">{p.title} <span className="count">{items.length}</span></h3>}
      {items.length === 0 && <p className="work-empty">{p.emptyText ?? 'No stips'}</p>}
      <ul className="suggestion-list">
        {items.map((s) => (
          <li key={s.stipId} className="suggestion" data-stip={s.code} data-status={s.status}>
            <div className="suggestion-main">
              <span className="suggestion-head">
                <span className="work-id">{s.title}</span>
                <span className={`work-status status-${TONE[s.status] ?? 'neutral'}`}>
                  <span className="status-dot" aria-hidden="true" />{s.status.toLowerCase()}</span>
              </span>
              <span className="suggestion-reason">{s.description}</span>
              <span className="suggestion-reason">{s.raisedBy}{s.resolved && <> · {s.resolved}</>}</span>
              {s.note && <span className="suggestion-reason stip-note">{s.note}</span>}
            </div>
            {open?.stipId === s.stipId ? reasonBox(`${open.status === 'WAIVED' ? 'Waive' : 'Satisfy'} ${s.code}`,
              () => send(p.resolveAction, {stipId: s.stipId, status: open.status, reason: text.trim()})) : (
              <span className="suggestion-confirm">
                {s.canSatisfy && <button type="button" className="suggestion-assign" aria-label={`Satisfy ${s.code}`}
                  onClick={() => setOpen({stipId: s.stipId, status: 'SATISFIED'})}>Satisfy</button>}
                {s.canWaive && <button type="button" className="suggestion-no" aria-label={`Waive ${s.code}`}
                  onClick={() => setOpen({stipId: s.stipId, status: 'WAIVED'})}>Waive</button>}
              </span>
            )}
          </li>
        ))}
      </ul>
      {adding ? (
        <div className="suggestion-confirm" role="group" aria-label="Add stip">
          <select aria-label="Stip code" value={code} onChange={(e) => setCode(e.target.value)}>
            <option value="">Pick a code…</option>
            {codes.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
          <input className="stip-reason" aria-label="Description" placeholder="Description (required)" value={text}
                 onChange={(e) => setText(e.target.value)} />
          <button type="button" className="suggestion-yes" disabled={!code || !text.trim()}
                  onClick={() => { send(p.addAction, {code, description: text.trim()}); done(); }}>Add</button>
          <button type="button" className="suggestion-no" onClick={done}>Cancel</button>
        </div>
      ) : (
        <button type="button" className="suggestion-assign" disabled={!p.canAdd} title={p.canAdd ? undefined : p.addReason}
                onClick={() => setAdding(true)}>Add stip</button>
      )}
      {!p.canAdd && p.addReason && <p className="work-empty">{p.addReason}</p>}
    </section>
  );
});

// The published web_core (0.15.0) rejects a per-component "@catalogId", so a surface can use only one
// catalog. Our catalog therefore EXTENDS the Basic Catalog: all 18 basic components and 15 functions,
// plus ours. Surfaces are created with this catalog's id.
export const funderCatalog = new Catalog(
  FUNDER_CATALOG_ID,
  '1.0' as never,
  [...basicCatalog.components.values(), DataTable, StatusBadge, Heading, FactList, CheckList, Timeline, WorkList, SuggestionList, StipList] as never,
  [...basicCatalog.functions.values()] as never,
);
