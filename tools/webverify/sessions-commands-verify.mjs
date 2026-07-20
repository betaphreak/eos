// Local check: the Commands panel rendering a SUCCESSFUL payload (plan §C1 + §C3).
//
// Two things make this awkward, and both are handled at the network layer rather than by
// reconfiguring the app:
//   1. the admin bundle's serverBase points at the deployed dev server, which predates
//      GET /api/sessions/{id}/commands and answers 405 — so requests are rewritten to the LOCAL
//      server (start it with tools/dev-local.ps1, or the mvn spring-boot:run equivalent);
//   2. the command log is the one GATED session read, so an anonymous browser gets 401 — the dev
//      identity header is injected, which the local server trusts when started with
//      --civstudio.auth.trust-dev-user-header=true.
//
// Usage: node sessions-commands-verify.mjs [strapiBase] [gameServer]
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const base = process.argv[2] || DEFAULT_BASE;
const server = process.argv[3] || 'http://localhost:8080';
const DEV_USER_HEADER = 'X-CivStudio-User';

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 });
await page.addInitScript(() => { try { localStorage.setItem('STRAPI_THEME', 'dark'); } catch {} });

// Point every game-server call at the local server, signed in as an admin.
await page.route('**/api/sessions**', async (route) => {
  const req = route.request();
  const url = new URL(req.url());
  if (url.origin === new URL(base).origin) return route.continue(); // Strapi's own API, leave alone

  // route.continue() cannot change protocol (https -> http), so fetch the local server here and
  // fulfil the response ourselves. The CORS headers must be echoed back explicitly: the page still
  // believes it is talking to the https origin, and the request rides credentials:'include', which
  // forbids a wildcard allow-origin.
  const target = server.replace(/\/+$/, '') + url.pathname + url.search;
  const origin = req.headers()['origin'] ?? new URL(base).origin;
  try {
    const res = await fetch(target, {
      method: req.method(),
      headers: { [DEV_USER_HEADER]: 'dev-admin', Accept: 'application/json' },
    });
    await route.fulfill({
      status: res.status,
      headers: {
        'content-type': res.headers.get('content-type') ?? 'application/json',
        'access-control-allow-origin': origin,
        'access-control-allow-credentials': 'true',
      },
      body: await res.text(),
    });
  } catch (e) {
    await route.fulfill({ status: 502, body: String(e) });
  }
});

const errors = [];
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

await loginAdmin(page, { base });
await page.goto(`${base}/admin/civstudio-sessions`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('main a[href*="/civstudio-sessions/"]', { timeout: 60000 });
await page.click('main a[href*="/civstudio-sessions/"]');
await page.waitForTimeout(6000);

const tab = await page.$('button[role="tab"]:has-text("Commands")');
if (!tab) { console.log('Commands tab not found'); await browser.close(); process.exit(1); }
await tab.click();
await page.waitForTimeout(4000);

const text = await page.evaluate(() => {
  const panel = document.querySelector('[role="tabpanel"]:not([hidden])');
  return (panel ?? document.querySelector('main'))?.innerText ?? '';
});
console.log('### Commands ###');
console.log(text.slice(0, 800));
await page.screenshot({ path: 'sessions-panel-commands.png', fullPage: true });
console.log('page errors:', errors.length ? errors : 'none');

// PASS only on a real render: the replay-log card with its counters. An error or a gate is a fail
// here — this script exists precisely to see the success path.
const ok = /Replay log/.test(text) && /Applied/.test(text) && !/Could not read/.test(text)
  && !/sign in|not an admin/i.test(text);
console.log(ok ? 'RESULT: PASS (commands panel rendered)' : 'RESULT: FAIL');
await browser.close();
process.exit(ok ? 0 : 1);
