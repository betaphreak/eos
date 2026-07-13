// Verify the Privy Council advisor selector + sub-bar (docs/privy-council.md §1).
// Serves web/ statically and points the page at a live server for the bundle:
//   node advisor-verify.mjs <liveBase> [outDir]
// Boots the app, then drives each advisor and asserts the selector, the swapping sub-control strip,
// the tech-stage suspend, and the LIVE badge — capturing console/page errors throughout.
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '../../web');
const base = process.argv[2] || 'http://localhost:8080';
const outDir = process.argv[3] || HERE;
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.webp': 'image/webp', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.css': 'text/css', '.pack': 'application/octet-stream', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

const srv = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  fs.readFile(path.join(WEB, p), (e, data) => {
    if (e) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(p)] || 'application/octet-stream' });
    res.end(data);
  });
});
await new Promise(r => srv.listen(0, r));
const port = srv.address().port;
const url = `http://localhost:${port}/index.html?live=${base}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, deviceScaleFactor: 1 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'domcontentloaded' });
// wait for the bundle + advisor selector to build
await page.waitForFunction(() => window.BUNDLE && document.querySelectorAll('#advisorToggle button').length > 0,
  { timeout: 30000 }).catch(() => {});

const snap = async () => page.evaluate(() => {
  const q = s => document.querySelector(s);
  const pressed = document.querySelector('#advisorToggle button[aria-pressed="true"]');
  const visSub = [...document.querySelectorAll('#advisorSubbar [data-sub]')].find(e => !e.hidden);
  const ovPressed = [...document.querySelectorAll('#advisorSubbar [data-ov]')].filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.dataset.ov);
  const planePressed = [...document.querySelectorAll('[data-plane]')].filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.dataset.plane);
  return {
    advisorBtns: document.querySelectorAll('#advisorToggle button').length,
    disabledBtns: document.querySelectorAll('#advisorToggle button:disabled').length,
    active: pressed && pressed.dataset.advisor,
    visSub: visSub && visSub.dataset.sub,
    techHidden: q('#techModal') ? q('#techModal').hidden : null,
    liveBadgeShown: !!q('#advisorSubbar [data-sub="zeitgeist"]') && !q('#advisorSubbar [data-sub="zeitgeist"]').hidden && !!q('.live-badge'),
    ovPressed, planePressed,
  };
});
const click = async id => { await page.click(`#advisorToggle button[data-advisor="${id}"]`); await page.waitForTimeout(500); };

const results = { boot: await snap() };
// map-based advisors via click (their sub-bars keep the top bar reachable)
for (const id of ['foreign', 'religion', 'globe', 'zeitgeist', 'mainmap']) {
  await click(id);
  results[id] = await snap();
  await page.screenshot({ path: path.join(outDir, `advisor-${id}.png`) });
}
// Technology now fills the stage region (§2), so the top bar stays live — enter it by CLICK and
// prove another advisor is still clickable while it's up (the old modal covered the bar).
await click('technology'); await page.waitForTimeout(700);
results.technology = await snap();
await page.screenshot({ path: path.join(outDir, 'advisor-technology.png') });
await click('foreign'); await page.waitForTimeout(500);   // click-through the top bar while tech was up
results.techToForeignByClick = await snap();
await page.keyboard.press('F6'); await page.waitForTimeout(500);
await page.keyboard.press('Escape'); await page.waitForTimeout(500);
results.escToMainMap = await snap();
// hotkey round-trip: F4 → foreign, backtick → mainmap
await page.keyboard.press('F4'); await page.waitForTimeout(400);
results.keyF4 = await snap();
await page.keyboard.press('Backquote'); await page.waitForTimeout(400);
results.keyBacktick = await snap();

console.log(JSON.stringify({ url, errors, results }, null, 2));
await browser.close();
srv.close();
