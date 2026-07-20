// Local check: the Sessions detail page's §C3 panels — click each tab and dump what it renders,
// so a panel that silently fails its fetch shows up as an empty tab rather than passing quietly.
//
// Talks to the real dev server cross-origin (CORS allows http://localhost:*). Note the Commands tab
// is EXPECTED to show the sign-in gate: it is the one gated session read, and a local admin has no
// game-server login.
//
// Usage: node sessions-panels-verify.mjs [base]
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const base = process.argv[2] || DEFAULT_BASE;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1200 }, deviceScaleFactor: 1 });
await page.addInitScript(() => { try { localStorage.setItem('STRAPI_THEME', 'dark'); } catch {} });
const errors = [];
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

await loginAdmin(page, { base });
await page.goto(`${base}/admin/civstudio-sessions`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('main a[href*="/civstudio-sessions/"]', { timeout: 60000 });
await page.click('main a[href*="/civstudio-sessions/"]');
await page.waitForTimeout(7000);
console.log('detail url:', page.url());

const TABS = ['Colony', 'Court', 'Bands', 'Events', 'Commands'];
const seen = {};
for (const name of TABS) {
  const tab = await page.$(`button[role="tab"]:has-text("${name}")`);
  if (!tab) { console.log(`\n### ${name}: TAB NOT FOUND`); seen[name] = ''; continue; }
  await tab.click();
  await page.waitForTimeout(3500);
  const text = await page.evaluate(() => {
    const panel = document.querySelector('[role="tabpanel"]:not([hidden])');
    return (panel ?? document.querySelector('main'))?.innerText ?? '';
  });
  seen[name] = text;
  console.log(`\n### ${name} ###`);
  console.log(text.slice(0, 700));
  await page.screenshot({ path: `sessions-panel-${name.toLowerCase()}.png`, fullPage: true });
}

console.log('\npage errors:', errors.length ? errors : 'none');

// each tab must render SOMETHING recognisable — either its content or an honest empty/gate state
const ok =
  /Population|no colony/i.test(seen.Colony) &&
  /Court|advisors|No advisors/i.test(seen.Court) &&
  /Bands|No bands/i.test(seen.Bands) &&
  /Events|No lines/i.test(seen.Events) &&
  /Replay log|sign|admin|No commands/i.test(seen.Commands);
console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL');
await browser.close();
process.exit(ok ? 0 : 1);
