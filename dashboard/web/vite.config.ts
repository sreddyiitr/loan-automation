import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// /agent is proxied to the funder agent service (A2A JSON-RPC), so the browser stays same-origin.
// AGENT_URL points it elsewhere: the Playwright tests run their own replaying agent (T155).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/agent': { target: process.env.AGENT_URL ?? 'http://localhost:8091', rewrite: (p) => p.replace(/^\/agent/, '') },
    },
  },
})
