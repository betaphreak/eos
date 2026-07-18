// Local check: log into the Strapi admin homepage and measure the two CivStudio ops widgets'
// widths relative to the widget grid, to confirm the half-width (span 6) CSS rule took.
// Usage: node widget-width-verify.mjs [base]
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const base = process.argv[2] || DEFAULT_BASE;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 }, deviceScaleFactor: 1 });
await page.addInitScript(() => { try { localStorage.setItem('STRAPI_THEME', 'dark'); } catch {} });
const errors = [];
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

await loginAdmin(page, { base });
// The homepage widgets poll the (absent) game server, so networkidle never settles — wait on the grid.
await page.goto(`${base}/admin/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('[data-strapi-grid-container]', { timeout: 60000 });
await page.waitForSelector('[data-strapi-widget-id="global::civstudio-sessions"]', { timeout: 60000 });
await page.waitForTimeout(2000);

const result = await page.evaluate(() => {
  const uids = ['global::civstudio-server-ops', 'global::civstudio-sessions'];
  const rows = uids.map((uid) => {
    const el = document.querySelector(`[data-strapi-widget-id="${uid}"]`);
    if (!el) return { uid, found: false };
    const item = el.parentElement; // Grid.Item — the grid child carrying grid-column
    const grid = item?.parentElement; // the display:grid track owner
    const gw = grid?.getBoundingClientRect().width || 0;
    const w = item.getBoundingClientRect().width;
    return {
      uid, found: true,
      widthPx: Math.round(w),
      pctOfGrid: gw ? Math.round((w / gw) * 100) : 0,
      gridColumn: getComputedStyle(item).gridColumn,
    };
  });
  return { rows };
});

for (const r of result.rows) {
  console.log(`  ${r.uid}: found=${r.found} width=${r.widthPx}px (${r.pctOfGrid}% of grid) grid-column="${r.gridColumn}"`);
}
console.log('page errors:', errors.length ? errors : 'none');
await page.screenshot({ path: 'widget-width.png', fullPage: true });

const ok = result.rows.every((r) => r.found && r.pctOfGrid >= 40 && r.pctOfGrid <= 62);
console.log(ok ? 'RESULT: PASS (half-width)' : 'RESULT: FAIL');
await browser.close();
process.exit(ok ? 0 : 1);
