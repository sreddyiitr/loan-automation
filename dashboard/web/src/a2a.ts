// Spike D0 / T001-T002: the browser talks A2A JSON-RPC (message/send) to the agent, as the
// A2UI v1.0 A2A extension describes. Vite proxies /agent to the agent service (vite.config.ts).
const EXTENSION = 'https://a2ui.org/a2a-extension/a2ui/v1.0';
const MIME = 'application/a2ui+json';
export const BASIC_CATALOG = 'https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json';

type Part = {kind: string; text?: string; data?: Record<string, unknown>; metadata?: Record<string, unknown>};

/** Sends text (a request) or an A2UI message (e.g. an action) and returns the A2UI messages the agent replies with. */
export async function sendToAgent(payload: string | Record<string, unknown>): Promise<Record<string, unknown>[]> {
  const part: Part = typeof payload === 'string'
    ? {kind: 'text', text: payload}
    : {kind: 'data', data: payload, metadata: {mimeType: MIME}};
  const body = {
    jsonrpc: '2.0', id: crypto.randomUUID(), method: 'message/send',
    params: {
      message: {
        messageId: crypto.randomUUID(), role: 'user', kind: 'message', parts: [part],
        // v1.0: renderer capabilities ride on message metadata, keyed by protocol version.
        metadata: {a2uiRendererCapabilities: {'1.0': {supportedCatalogIds: [BASIC_CATALOG]}}},
      },
    },
  };
  const res = await fetch('/agent/', {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'X-A2A-Extensions': EXTENSION},
    body: JSON.stringify(body),
  });
  const json = await res.json();
  if (json.error) throw new Error(json.error.message);
  const parts: Part[] = json.result?.parts ?? [];
  return parts.filter((p) => p.kind === 'data' && p.metadata?.mimeType === MIME).map((p) => p.data!);
}
