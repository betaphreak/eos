// Local check: the Sessions admin PAGE (plan §C2) — the menu link resolves, the list renders live
// rows from the game server, and clicking a row routes to the detail page.
//
// Like sessions-widget-verify.mjs this talks to the real dev server cross-origin (CORS allows
// http://localhost:*, GET /api/sessions is public), so the rows are genuine.
//
// Usage: node sessions-page-verify.mjs [base]
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const base = process.argv[2] || DEFAULT_BASE;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 });
await page.addInitScript(() => { try { localStorage.setItem('STRAPI_THEME', 'dark'); } catch {} });
const errors = [];
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

await loginAdmin(page, { base });

// 1. the menu link exists (registered via app.addMenuLink)
await page.goto(`${base}/admin/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('nav', { timeout: 60000 });
const menuLink = await page.$('nav a[href$="/civstudio-sessions"]');
console.log('menu link present:', !!menuLink);

// 2. the list page renders
await page.goto(`${base}/admin/civstudio-sessions`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('h1', { timeout: 60000 });
await page.waitForTimeout(6000);
const listText = await page.evaluate(() => document.querySelector('main')?.innerText ?? '');
console.log('--- list page ---');
console.log(listText.slice(0, 900));
await page.screenshot({ path: 'sessions-page-list.png', fullPage: true });

// 3. clicking a row routes to the detail page
const row = await page.$('main a[href*="/civstudio-sessions/"]');
let detailText = '';
if (row) {
  await row.click();
  await page.waitForTimeout(6000);
  detailText = await page.evaluate(() => document.querySelector('main')?.innerText ?? '');
  console.log('--- detail page (url:', page.url(), ') ---');
  console.log(detailText.slice(0, 900));
  await page.screenshot({ path: 'sessions-page-detail.png', fullPage: true });
} else {
  console.log('(no session row to click — is the dev server reachable?)');
}

console.log('page errors:', errors.length ? errors : 'none');

const listOk = /Sessions/.test(listText);
const detailOk = /In-game date|No session/.test(detailText);
const ok = !!menuLink && listOk && detailOk;
console.log(ok ? 'RESULT: PASS' : `RESULT: FAIL (menu=${!!menuLink} list=${listOk} detail=${detailOk})`);
await browser.close();
process.exit(ok ? 0 : 1);
