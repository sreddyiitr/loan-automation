// make demo, step 1 (scripts/demo.sh): records the funder-dashboard demo with Playwright.
// Each scene lasts as long as its narration line (<outDir>/durations.txt), shows a caption bar and a
// visible cursor, and logs when it started (<outDir>/timeline.json), so scripts/demo_assemble.py can
// lay the voice clips at exactly those moments. Needs `make dashboard` running.
// Usage: node dashboard/web/demo/record.mjs <outDir>
import {chromium} from '@playwright/test';
import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';

const OUT = process.argv[2];
const W = 1280, H = 720;
const durations = Object.fromEntries(readFileSync(`${OUT}/durations.txt`, 'utf8').trim().split('\n')
  .map((l) => l.split(' ')).map(([n, d]) => [Number(n), Number(d)]));
const NARRATION = JSON.parse(readFileSync(new URL('./narration.json', import.meta.url), 'utf8')).lines;
const CAPTIONS = Object.fromEntries(NARRATION.map((l) => [l.n, l.caption]));
const BASE = process.env.DEMO_URL ?? 'http://localhost:5173';
const USER = process.env.DEMO_USER ?? 'risc-1';   // LA-1002, the demo's main deal, is RISC work

// A visible cursor, a click ripple and a caption bar, on every page (the app and the login page).
const overlay = () => {
  const install = () => {
    if (document.getElementById('demo-cursor') || !document.body) return;
    const css = document.createElement('style');
    css.textContent = `
      #demo-cursor{position:fixed;z-index:2147483647;width:22px;height:22px;left:-40px;top:-40px;pointer-events:none;
        transform:translate(-3px,-2px);transition:left .35s cubic-bezier(.2,.8,.2,1),top .35s cubic-bezier(.2,.8,.2,1)}
      #demo-cursor svg{filter:drop-shadow(0 2px 3px rgba(0,0,0,.35))}
      .demo-ripple{position:fixed;z-index:2147483646;width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;
        border:3px solid rgba(99,102,241,.9);pointer-events:none;animation:demo-r .55s ease-out forwards}
      @keyframes demo-r{to{transform:scale(3.4);opacity:0}}
      #demo-caption{position:fixed;z-index:2147483645;left:50%;bottom:26px;transform:translateX(-50%);max-width:86%;
        padding:12px 22px;border-radius:12px;background:rgba(11,19,43,.86);color:#fff;backdrop-filter:blur(6px);
        font:600 20px/1.35 Inter,-apple-system,'Segoe UI',sans-serif;text-align:center;box-shadow:0 8px 24px rgba(0,0,0,.25);
        transition:opacity .25s}
      #demo-caption:empty{opacity:0}`;
    document.head.appendChild(css);
    const c = document.createElement('div');
    c.id = 'demo-cursor';
    c.innerHTML = '<svg width="22" height="22" viewBox="0 0 24 24"><path d="M4 2l15 9-6.5 1.5L16 20l-3 1.5-3.5-7.5L4 18z" fill="#fff" stroke="#111" stroke-width="1.6" stroke-linejoin="round"/></svg>';
    document.body.appendChild(c);
    const cap = document.createElement('div');
    cap.id = 'demo-caption';
    cap.textContent = sessionStorage.getItem('demo-caption') || '';
    document.body.appendChild(cap);
    addEventListener('mousemove', (e) => { c.style.left = e.clientX + 'px'; c.style.top = e.clientY + 'px'; }, true);
    addEventListener('mousedown', (e) => {
      const r = document.createElement('div'); r.className = 'demo-ripple';
      r.style.left = e.clientX + 'px'; r.style.top = e.clientY + 'px';
      document.body.appendChild(r); setTimeout(() => r.remove(), 600);
    }, true);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install); else install();
};

const browser = await chromium.launch();
const context = await browser.newContext({viewport: {width: W, height: H}, colorScheme: 'light',
  recordVideo: {dir: `${OUT}/raw`, size: {width: W, height: H}}});
await context.addInitScript(overlay);
const page = await context.newPage();
const t0 = Date.now();
const timeline = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function caption(n) {
  await page.evaluate((t) => {
    sessionStorage.setItem('demo-caption', t);
    const el = document.getElementById('demo-caption'); if (el) el.textContent = t;
  }, CAPTIONS[n]).catch(() => {});
}

/** Runs a scene: caption, the actions, then holds until the narration line (plus a breath) is over. */
async function scene(n, actions) {
  const start = Date.now();
  timeline.push({line: n, at: (start - t0) / 1000, caption: CAPTIONS[n]});
  await caption(n);
  await actions();
  const left = durations[n] * 1000 + 450 - (Date.now() - start);
  if (left > 0) await sleep(left);
}

async function glideTo(locator) {                        // move the visible cursor first, then act
  const b = await locator.boundingBox();
  if (b) { await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, {steps: 12}); await sleep(380); }
}
async function click(locator) { await glideTo(locator); await locator.click(); }
async function smoothScroll(y) {
  await page.evaluate((top) => window.scrollTo({top, behavior: 'smooth'}), y); await sleep(900);
}

await page.goto(`${BASE}/`);
await page.mouse.move(W / 2, H / 2);
await sleep(600);

await scene(1, async () => {
  await sleep(1200);
  await glideTo(page.getByRole('button', {name: 'Sign in'}));
});

await scene(2, async () => {
  await click(page.getByRole('button', {name: 'Sign in'}));
  const user = page.locator('input[name="username"]');
  await user.waitFor();
  await caption(2);
  await click(user);
  await page.keyboard.type(USER, {delay: 70});
  await click(page.getByRole('button', {name: 'Sign-in'}));
  await page.getByTestId('user').waitFor();
  await caption(2);
  await click(page.getByLabel('Loan id'));
  await page.keyboard.type('LA-1002', {delay: 70});
  await click(page.getByRole('button', {name: 'Open', exact: true}));
  await page.getByText("What's been done").waitFor();
});

await scene(3, async () => {
  await sleep(800);
  const checks = page.locator('.check-list');
  const y = await checks.evaluate((el) => el.getBoundingClientRect().top + window.scrollY - 90);
  await smoothScroll(y);
  await glideTo(page.locator('.check[data-check="TILA math"] .comparisons tr.bad').first());
});

await scene(4, async () => {
  await click(page.locator('.check[data-check="Income"] .check-head'));
  await sleep(1400);
  const t = page.locator('.timeline');
  const y = await t.evaluate((el) => el.getBoundingClientRect().top + window.scrollY - 260);
  await smoothScroll(y);
});

await scene(5, async () => {
  await smoothScroll(0);
  await click(page.getByRole('button', {name: 'Summarize'}));
  await page.getByText(/^Written by the assistant/).waitFor({timeout: 30000});
});

await scene(6, async () => {
  await click(page.getByLabel('Loan id'));
  await page.keyboard.press('Meta+A');
  await page.keyboard.type('LA-1001', {delay: 70});
  await click(page.getByRole('button', {name: 'Open', exact: true}));
  await page.getByText(/^LA-1001 · v/).waitFor();
  await sleep(700);
  const y = await page.locator('.check-list').evaluate((el) => el.getBoundingClientRect().top + window.scrollY - 160);
  await smoothScroll(y);
});

await sleep(1200);
const total = (Date.now() - t0) / 1000;
await context.close();
await browser.close();
mkdirSync(OUT, {recursive: true});
writeFileSync(`${OUT}/timeline.json`, JSON.stringify({total, scenes: timeline}, null, 1));
console.log(JSON.stringify({total, scenes: timeline.map((s) => [s.line, s.at.toFixed(2)])}));
