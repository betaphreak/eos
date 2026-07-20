// Local check: log into the Strapi admin homepage and dump what the "Live sessions" widget
// actually renders, to confirm the full session detail (clock/outcome/date/tick/seed/realm/
// scenario/watching/endReason) reaches the DOM rather than the old blank id+tick row.
//
// The widget calls the game server cross-origin (default https://dev.civstudio.com); the server's
// CORS allows http://localhost:* (WebConfig#originPatterns), and GET /api/sessions is public, so a
// locally-served admin shows the REAL live rows.
//
// Usage: node sessions-widget-verify.mjs [base]
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const base = process.argv[2] || DEFAULT_BASE;
const UID = 'global::civstudio-sessions';

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 });
await page.addInitScript(() => { try { localStorage.setItem('STRAPI_THEME', 'dark'); } catch {} });
const errors = [];
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

await loginAdmin(page, { base });
// The widgets poll the game server, so networkidle never settles — wait on the grid instead.
await page.goto(`${base}/admin/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('[data-strapi-grid-container]', { timeout: 60000 });
await page.waitForSelector(`[data-strapi-widget-id="${UID}"]`, { timeout: 60000 });
// give the cross-origin /api/sessions fetch time to land and repaint
await page.waitForTimeout(6000);

const text = await page.evaluate((uid) => {
  const el = document.querySelector(`[data-strapi-widget-id="${uid}"]`);
  return el ? el.innerText : null;
}, UID);

console.log('--- Live sessions widget text ---');
console.log(text ?? '(widget not found)');
console.log('---------------------------------');

// The labels the reworked widget must show for a live row.
const want = ['In-game date', 'Tick', 'Watching', 'Scenario', 'Seed', 'Realm'];
const missing = want.filter((w) => !(text || '').includes(w));
const gated = /Not signed in|not an admin/i.test(text || '');

const el = await page.$(`[data-strapi-widget-id="${UID}"]`);
if (el) await el.screenshot({ path: 'sessions-widget.png' });

console.log('page errors:', errors.length ? errors : 'none');
if (gated) {
  console.log('RESULT: GATED (server returned 401/403 — cannot verify detail rendering)');
  await browser.close();
  process.exit(2);
}
console.log(missing.length ? `RESULT: FAIL — missing labels: ${missing.join(', ')}` : 'RESULT: PASS (full detail rendered)');
await browser.close();
process.exit(missing.length ? 1 : 0);
