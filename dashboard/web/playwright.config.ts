import {defineConfig} from '@playwright/test';

// The browser tests (spec 002) start their own agent and web app on their own ports, so they always
// run the current code and never touch the ones `make dashboard` runs on 8091 and 5173. The agent
// replays recorded model answers (ASSISTANT_REPLAY, AS-10.5), so no model is needed.
// They still need the funding API (with the seeds submitted) and the OIDC provider: `make dashboard`.
const AGENT_PORT = 8092;
const WEB_PORT = 5174;

export default defineConfig({
  testDir: 'e2e',
  // One file at a time (T304): the stip tests fund LA-1006 and re-stipulate LA-1030, then put them back
  // as seeded (the dev reseed). Run beside the other files, they'd race the tests that read those deals
  // (AS-10.3 reads every seeded status; My work and the assistant read LA-1006 and LA-1030).
  workers: 1,
  use: {baseURL: `http://localhost:${WEB_PORT}`, headless: true},
  webServer: [
    {
      command: `cd ../agent && ASSISTANT_REPLAY=recordings AGENT_PORT=${AGENT_PORT} uv run python main.py`,
      url: `http://localhost:${AGENT_PORT}/.well-known/agent-card.json`,
      reuseExistingServer: false, timeout: 120_000, stdout: 'ignore', stderr: 'pipe',
    },
    {
      command: `AGENT_URL=http://localhost:${AGENT_PORT} npm run dev -- --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      reuseExistingServer: false, timeout: 120_000,
    },
  ],
});
