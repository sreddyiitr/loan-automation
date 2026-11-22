// The dashboard shell (spec 002 T105): sign in (OIDC, auth.ts), then open a deal by its loan id.
// Everything inside a deal is an A2UI surface from the agent; clicks go back to it as actions.
import {useEffect, useState, type FormEvent} from 'react';
import {MessageProcessor} from '@a2ui/web_core/v1_0';
import {basicCatalog} from '@a2ui/web_core/catalogs/basic/v1';
import {A2uiSurface} from '@a2ui/react';
import {sendToAgent, streamToAgent} from './a2a';
import {CALLBACK_PATH, completeSignIn, signIn, signOut, userId} from './auth';
import {funderCatalog} from './catalog/funderCatalog';

type Message = Record<string, unknown>;

/**
 * Applies agent messages, skipping a createSurface for a surface that already exists. A2UI treats a
 * duplicate as an integrity error, and the agent may resend a surface (React's StrictMode also
 * double-runs effects in development). The official React sample guards the same way.
 * A createSurface after a deleteSurface of the same id in this reply is kept: that's the agent
 * rebuilding a surface whose controls changed (plan §3), e.g. after Take.
 * Returns the ids of the surfaces the reply created or recreated.
 */
function apply(p: MessageProcessor, messages: Message[]): string[] {
  const exists = new Set<string>(p.model.surfacesMap.keys());
  const created: string[] = [];
  const kept = messages.filter((m) => {
    const deleted = (m.deleteSurface as {surfaceId?: string} | undefined)?.surfaceId;
    if (deleted) exists.delete(deleted);
    const id = (m.createSurface as {surfaceId?: string} | undefined)?.surfaceId;
    if (!id) return true;
    created.push(id);
    if (exists.has(id)) return false;
    exists.add(id);
    return true;
  });
  p.processMessages(kept as never);
  return created;
}

function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark" aria-hidden="true">F</span>
      <span className="brand-name">Funder dashboard</span>
    </span>
  );
}

function SignIn({error}: {error?: string}) {
  return (
    <div className="sign-in-page">
      <section className="sign-in">
        <Brand />
        <h1>Sign in to work your deals</h1>
        <p className="muted">Review funding exceptions, clear stips and decide, with every change audited under your name.</p>
        {error && <p role="alert" className="alert">{error}</p>}
        <button type="button" className="btn btn-primary btn-block" onClick={() => void signIn()}>Sign in</button>
        <p className="fine-print">Local development: the sign-in page takes any user id from the directory, with no password.</p>
      </section>
    </div>
  );
}

export default function App() {
  const [user, setUser] = useState<string | undefined>(userId());
  const [signInError, setSignInError] = useState<string>();
  const [loanId, setLoanId] = useState('');
  const [visible, setVisible] = useState<string[]>([]);
  const [events, setEvents] = useState<string[]>([]);
  const [error, setError] = useState<string>();
  const [processor] = useState(() => {
    const p: MessageProcessor = new MessageProcessor([basicCatalog, funderCatalog] as never, (action: unknown) => {
      // A click: forward it to the agent as an A2UI v1.0 renderer_to_agent "action" over
      // message/stream, and apply each reply as it arrives (the assistant's panel says
      // "Summarizing…" before its answer; other actions reply once).
      setEvents((e) => [...e, JSON.stringify(action)]);
      streamToAgent({version: '1.0', action: action as Message}, (reply) => {
        const created = apply(p, reply);
        if (!created.length) return;
        // Opening a deal from My work replaces the page; anything else (a notice, the assistant's
        // panel) joins what's there.
        if (created.some((id) => id.endsWith('_overview')) && !created.includes('notice')) setVisible(created);
        else setVisible((v) => [...new Set([...v, ...created])]);
      }).catch((err) => setError(String(err)));
    });
    return p;
  });
  const [, setTick] = useState(0);

  useEffect(() => {
    const sync = () => setTick((t) => t + 1);
    const created = processor.onSurfaceCreated(sync);
    const deleted = processor.onSurfaceDeleted(sync);
    return () => {
      created.unsubscribe();
      deleted.unsubscribe();
    };
  }, [processor]);

  useEffect(() => {
    if (window.location.pathname !== CALLBACK_PATH) return;
    completeSignIn()
      .then((u) => setUser(u.profile.sub))
      .catch((err) => {
        window.history.replaceState(null, '', '/');
        setSignInError(`Sign-in failed: ${err instanceof Error ? err.message : String(err)}`);
      });
  }, []);

  // AS-1.8: signing in lands on My work.
  useEffect(() => {
    if (user && window.location.pathname !== CALLBACK_PATH) loadMyWork();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  function loadMyWork() {
    setError(undefined);
    sendToAgent('my work')
      .then((messages) => setVisible(apply(processor, messages)))
      .catch((err) => setError(String(err)));
  }

  if (!user) {
    return window.location.pathname === CALLBACK_PATH && !signInError
      ? <p>Signing in…</p>
      : <SignIn error={signInError} />;
  }

  // AS-1.5: open a deal by its loan id; an unknown id gets the agent's "not found" notice.
  function open(e: FormEvent) {
    e.preventDefault();
    const id = loanId.trim().toUpperCase();
    if (!id) return;
    setError(undefined);
    sendToAgent(`open ${id}`)
      .then((messages) => setVisible(apply(processor, messages)))
      .catch((err) => setError(String(err)));
  }

  const surfaces = visible.flatMap((id) => {
    const s = processor.model.surfacesMap.get(id);
    return s ? [s] : [];
  });
  // D1c: the assistant's panel sits beside the deal on wide screens; everything else is the main column.
  const side = surfaces.filter((s) => s.id.endsWith('_assistant'));
  const mainSurfaces = surfaces.filter((s) => !s.id.endsWith('_assistant'));

  return (
    <div className="app">
      <header className="topbar">
        <Brand />
        <div className="topbar-user">
          <span className="user-chip">
            <span className="avatar" aria-hidden="true">{user.slice(0, 1).toUpperCase()}</span>
            <span>Signed in as <span data-testid="user">{user}</span></span>
          </span>
          <button type="button" className="btn btn-ghost" onClick={() => void signOut().then(() => { setUser(undefined); setVisible([]); })}>Sign out</button>
        </div>
      </header>
      <section className="hero">
        <div className="hero-inner">
          <div className="hero-text">
            <p className="hero-kicker">Funding exceptions</p>
            <h1 className="hero-title">{visible.includes('worklist') ? 'Your work' : 'Deal'}</h1>
          </div>
          <div className="hero-actions">
            <button type="button" className="btn btn-hero" onClick={loadMyWork}>My work</button>
          <form className="open-deal" onSubmit={open}>
            <label htmlFor="loan-id">Loan id</label>
            <input id="loan-id" value={loanId} onChange={(e) => setLoanId(e.target.value)} placeholder="e.g. LA-1006" autoComplete="off" />
            <button type="submit" className="btn btn-primary">Open</button>
          </form>
          </div>
        </div>
      </section>
      <main className="page">
        {error && <p role="alert" className="alert">{error}</p>}
        {surfaces.length === 0 && !error && (
          <section className="empty-state">
            <h2>No deal open yet</h2>
            <p className="muted">Type a loan id above to see its overview, take it, or ask the assistant to summarize it.</p>
          </section>
        )}
        <div className={side.length ? 'workspace has-side' : 'workspace'}>
          <div className="workspace-main">
            {mainSurfaces.map((s) => <A2uiSurface key={s.id} surface={s as never} />)}
          </div>
          {side.length > 0 && (
            <aside className="workspace-side">
              {side.map((s) => <A2uiSurface key={s.id} surface={s as never} />)}
            </aside>
          )}
        </div>
        <details className="debug">
          <summary>Events sent back to the agent</summary>
          <pre data-testid="events">{events.join('\n') || '(none yet)'}</pre>
        </details>
      </main>
    </div>
  );
}
