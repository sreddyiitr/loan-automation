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

function SignIn({error}: {error?: string}) {
  return (
    <section className="sign-in">
      <h1>Funder dashboard</h1>
      <p>Sign in to work your deals.</p>
      {error && <p role="alert">{error}</p>}
      <button type="button" onClick={() => void signIn()}>Sign in</button>
    </section>
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
        if (created.length) setVisible((v) => [...new Set([...v, ...created])]);   // e.g. a notice, the assistant panel
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

  return (
    <main>
      <header className="shell-header">
        <strong>Funder dashboard</strong>
        <span>
          Signed in as <span data-testid="user">{user}</span>{' '}
          <button type="button" onClick={() => void signOut().then(() => { setUser(undefined); setVisible([]); })}>Sign out</button>
        </span>
      </header>
      <form className="open-deal" onSubmit={open}>
        <label htmlFor="loan-id">Loan id</label>
        <input id="loan-id" value={loanId} onChange={(e) => setLoanId(e.target.value)} placeholder="LA-1006" autoComplete="off" />
        <button type="submit">Open</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {surfaces.map((s) => (
        <A2uiSurface key={s.id} surface={s as never} />
      ))}
      <details>
        <summary>Events sent back to the agent</summary>
        <pre data-testid="events">{events.join('\n') || '(none yet)'}</pre>
      </details>
    </main>
  );
}
