import {defineConfig} from '@playwright/test';

// The browser tests (spec 002) start their own agent and web app on their own ports, so they always
// run the current code and never touch the ones `make dashboard` runs on 8091 and 5173. The agent
// replays recorded model answers (ASSISTANT_REPLAY, AS-10.5), so no model is needed.
// They still need the funding API (with the seeds submitted) and the OIDC provider: `make dashboard`.
const AGENT_PORT = 8092;
const WEB_PORT = 5174;

export default defineConfig({
  testDir: 'e2e',
  use: {baseURL: `http://localhost:${WEB_PORT}`, headless: true},
  webServer: [
    {
      command: `cd ../agent && ASSISTANT_REPLAY=recordings AGENT_PORT=${AGENT_PORT} uv run python main.py`,
      url: `http://localhost:${AGENT_PORT}/.well-known/agent-card.json`,
      reuseExistingServer: false, timeout: 60_000, stdout: 'ignore', stderr: 'pipe',
    },
    {
      command: `AGENT_URL=http://localhost:${AGENT_PORT} npm run dev -- --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      reuseExistingServer: false, timeout: 60_000,
    },
  ],
});
