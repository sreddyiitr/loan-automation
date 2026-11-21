import {defineConfig} from '@playwright/test';

// Browser tests (plan §9). The dev server is started for the run and stopped after it.
export default defineConfig({
  testDir: 'e2e',
  use: {baseURL: 'http://localhost:5173', headless: true},
  webServer: {command: 'npm run dev -- --port 5173 --strictPort', url: 'http://localhost:5173', reuseExistingServer: false, timeout: 60_000},
});
