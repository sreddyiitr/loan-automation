// Spike D0: the overview surface now comes from the agent over A2A, and clicks go back to it.
import {useEffect, useState} from 'react';
import {MessageProcessor} from '@a2ui/web_core/v1_0';
import {basicCatalog} from '@a2ui/web_core/catalogs/basic/v1';
import {A2uiSurface} from '@a2ui/react';
import {sendToAgent} from './a2a';
import {funderCatalog} from './catalog/funderCatalog';

/**
 * Applies agent messages, skipping a createSurface for a surface that already exists. A2UI treats a
 * duplicate as an integrity error, and the agent may resend a surface (React's StrictMode also
 * double-runs effects in development). The official React sample guards the same way.
 */
function apply(p: MessageProcessor, messages: Record<string, unknown>[]) {
  const fresh = messages.filter((m) => {
    const cs = m.createSurface as {surfaceId?: string} | undefined;
    return !cs?.surfaceId || !p.model.surfacesMap.has(cs.surfaceId);
  });
  p.processMessages(fresh as never);
}

export default function App() {
  const [events, setEvents] = useState<string[]>([]);
  const [error, setError] = useState<string>();
  const [processor] = useState(() => {
    const p: MessageProcessor = new MessageProcessor([basicCatalog, funderCatalog] as never, (action: unknown) => {
      // A click: forward it to the agent as an A2UI v1.0 renderer_to_agent "action", apply its reply.
      setEvents((e) => [...e, JSON.stringify(action)]);
      sendToAgent({version: '1.0', action: action as Record<string, unknown>})
        .then((reply) => apply(p, reply))
        .catch((err) => setError(String(err)));
    });
    return p;
  });
  const [surfaces, setSurfaces] = useState(() => Array.from(processor.model.surfacesMap.values()));
  useEffect(() => {
    const sync = () => setSurfaces(Array.from(processor.model.surfacesMap.values()));
    const created = processor.onSurfaceCreated(sync);
    const deleted = processor.onSurfaceDeleted(sync);
    sendToAgent('open LA-1006')
      .then((messages) => apply(processor, messages))
      .catch((err) => setError(String(err)));
    return () => {
      created.unsubscribe();
      deleted.unsubscribe();
    };
  }, [processor]);

  return (
    <main style={{fontFamily: 'system-ui', maxWidth: 640, margin: '2rem auto'}}>
      <h1>A2UI spike</h1>
      {error && <p role="alert">{error}</p>}
      {surfaces.map((s) => (
        <A2uiSurface key={s.id} surface={s as never} />
      ))}
      <h3>Events sent back to the agent</h3>
      <pre data-testid="events">{events.join('\n') || '(none yet)'}</pre>
    </main>
  );
}
