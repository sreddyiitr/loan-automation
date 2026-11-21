// Spike D0 / T001-T002: the browser talks A2A JSON-RPC (message/send) to the agent, as the
// A2UI v1.0 A2A extension describes. Vite proxies /agent to the agent service (vite.config.ts).
import {accessToken} from './auth';

const EXTENSION = 'https://a2ui.org/a2a-extension/a2ui/v1.0';
const MIME = 'application/a2ui+json';
export const BASIC_CATALOG = 'https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json';
export const FUNDER_CATALOG = 'https://funding.local/a2ui/catalogs/funder/v1/catalog.json';

type Part = {kind: string; text?: string; data?: Record<string, unknown>; metadata?: Record<string, unknown>};

type Messages = Record<string, unknown>[];

function request(method: 'message/send' | 'message/stream', payload: string | Record<string, unknown>) {
  const part: Part = typeof payload === 'string'
    ? {kind: 'text', text: payload}
    : {kind: 'data', data: payload, metadata: {mimeType: MIME}};
  const body = {
    jsonrpc: '2.0', id: crypto.randomUUID(), method,
    params: {
      message: {
        messageId: crypto.randomUUID(), role: 'user', kind: 'message', parts: [part],
        // v1.0: renderer capabilities ride on message metadata, keyed by protocol version.
        metadata: {a2uiRendererCapabilities: {'1.0': {supportedCatalogIds: [BASIC_CATALOG, FUNDER_CATALOG]}}},
      },
    },
  };
  const token = accessToken();
  return fetch('/agent/', {
    method: 'POST',
    // FR-11: the user's own token goes to the agent, which relays it to the funder API.
    headers: {'Content-Type': 'application/json', 'X-A2A-Extensions': EXTENSION,
      ...(token ? {Authorization: `Bearer ${token}`} : {})},
    body: JSON.stringify(body),
  });
}

/** The A2UI messages in an A2A result: a message's parts, or a task's (or task update's) status message. */
function a2uiOf(result: {parts?: Part[]; status?: {message?: {parts?: Part[]}}} | undefined): Messages {
  const parts = result?.parts ?? result?.status?.message?.parts ?? [];
  return parts.filter((p) => p.kind === 'data' && p.metadata?.mimeType === MIME).map((p) => p.data!);
}

/** Sends text (a request) or an A2UI message (e.g. an action) and returns the A2UI messages the agent replies with. */
export async function sendToAgent(payload: string | Record<string, unknown>): Promise<Messages> {
  const json = await (await request('message/send', payload)).json();
  if (json.error) throw new Error(json.error.message);
  return a2uiOf(json.result);
}

/**
 * The same over `message/stream` (SSE): `onMessages` runs for each reply as it arrives. A plain
 * reply is one event; the assistant's (T154) is "Summarizing…" first, then the answer.
 */
export async function streamToAgent(payload: Record<string, unknown>, onMessages: (m: Messages) => void): Promise<void> {
  const res = await request('message/stream', payload);
  if (!res.body) throw new Error('the agent sent no stream');
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const {value, done} = await reader.read();
    if (done) break;
    buffer += value;
    let end: number;
    while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {       // one SSE event per blank line
      const event = buffer.slice(0, end);
      buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '');
      const data = event.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n');
      if (!data) continue;
      const json = JSON.parse(data);
      if (json.error) throw new Error(json.error.message);
      const messages = a2uiOf(json.result);
      if (messages.length) onMessages(messages);
    }
  }
}
