// unit-verify — verify the Technology advisor's tech-tree UNIT row (docs/c2c-unit-import.md Phase 3)
// against a live server. Serves web/ over HTTP (like tech-verify), points the bundle + /api/units at
// a live base via ?live=, enters the Technology advisor, and reports:
//   - console/page errors
//   - a node with a unit spectrum bar → its unit grid (cells/groups) in the rail
//   - a unit cell → the unit inspector (.unit-sheet: name, role, art, combat class)
//   - unified search → unit rows (kind chip) and picking one opens the unit inspector
// plus a screenshot.
//
// Usage: node unit-verify.mjs [out.png] [--live=http://localhost:8080] [--q=swordsman] [--wait=2500]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flags = {}; const pos = [];
for (const a of argv) { const m = /^--([^=]+)=(.*)$/.exec(a); if (m) flags[m[1]] = m[2]; else pos.push(a); }
const out = pos[0] || 'units.png';
const liveBase = flags.live || process.env.LIVE || 'http://localhost:8080';
const webDir = path.resolve(flags.web || path.join(here, '..', '..', 'web'));
const waitMs = +(flags.wait || 2500);

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.png': 'image/png', '.webp': 'image/webp', '.jpg': 'image/jpeg', '.json': 'application/json',
  '.pack': 'application/octet-stream', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0].split('#')[0];
  const file = path.join(webDir, url === '/' ? 'index.html' : decodeURIComponent(url));
  if (!file.startsWith(webDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('nf'); }
  const size = fs.statSync(file).size, type = MIME[path.extname(file)] || 'application/octet-stream', range = req.headers.range;
  if (range) {
    const m = /bytes=(\d+)-(\d+)?/.exec(range), start = +m[1], end = m[2] ? +m[2] : size - 1;
    res.writeHead(206, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else { res.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': size }); fs.createReadStream(file).pipe(res); }
});
await new Promise(r => server.listen(0, r));
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 950 }, deviceScaleFactor: 1.5 });
page.setDefaultTimeout(6000);   // fail fast on any missing element rather than the 30s default
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}#none`, { waitUntil: 'domcontentloaded' });
await page.waitForFunction(() => !!window.BUNDLE, { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1200);

// the Spectator Lobby opens during load and (with no live session to auto-join) stays up, covering
// the map — dismiss it so the top bar is reachable (mirrors a spectator closing the lobby to browse)
await page.locator('#lobbyClose').click({ timeout: 3000 }).catch(() => {});
await page.keyboard.press('Escape').catch(() => {});
await page.waitForTimeout(500);

// enter the Technology advisor (the tree is its full-canvas map-mode)
await page.click('#advisorToggle button[data-advisor="technology"]').catch(e => errors.push('advisor click: ' + e.message));
await page.waitForTimeout(waitMs);

// confirm /api/units actually loaded (the pack has units + combats)
const packInfo = await page.evaluate(async (liveBase) => {
  try {
    const res = await fetch(liveBase + '/api/units');
    const buf = await res.arrayBuffer();
    const s = new Response(buf).body.pipeThrough(new DecompressionStream('gzip'));
    const pack = JSON.parse(await new Response(s).text());
    return { ok: res.ok, units: pack.units?.length ?? 0, combats: pack.combats?.length ?? 0 };
  } catch (e) { return { ok: false, err: String(e) }; }
}, liveBase);

// click a node that unlocks UNITS (has the unit spectrum bar) to exercise the rail unit grid
const node = page.locator('.tech-node:has(.tech-uspec)').first();
await node.hover().catch(() => {});
await page.waitForTimeout(300);
await node.click().catch(() => {});
await page.waitForTimeout(700);
const gridInfo = await page.evaluate(() => ({
  unitGridCells: document.querySelectorAll('#rail .tech-ugrid .tech-bcell').length,
  unitGroups: document.querySelectorAll('#rail .tech-ugrid .tech-bgroup').length,
  buildingGridCells: document.querySelectorAll('#rail .tech-grid:not(.tech-ugrid) .tech-bcell').length,
  railTechName: document.querySelector('#rail .tech-d-name')?.textContent || null,
}));

// click the first unit cell → the unit inspector
await page.locator('#rail .tech-ugrid .tech-bcell').first().click().catch(e => errors.push('unit cell click: ' + e.message));
await page.waitForTimeout(500);
const inspInfo = await page.evaluate(() => ({
  unitSheet: !!document.querySelector('#rail .unit-sheet'),
  unitName: document.querySelector('#rail .unit-sheet .tech-d-name')?.textContent || null,
  role: document.querySelector('#rail .unit-sheet .bld-id .tech-d-tag')?.textContent || null,
  hasArt: !!document.querySelector('#rail .unit-sheet .bld-art'),
  classLine: document.querySelector('#rail .unit-sheet .unit-class')?.textContent || null,
  statTags: document.querySelectorAll('#rail .unit-sheet .tech-d-meta .tech-d-tag').length,
}));
await page.screenshot({ path: out });   // the inspector shot

// back to the tech, then unified search for a unit
await page.locator('#rail [data-bld-back]').click().catch(() => {});
await page.waitForTimeout(400);
await page.fill('#search', flags.q || 'swordsman').catch(e => errors.push('search fill: ' + e.message));
await page.waitForTimeout(500);
const searchInfo = await page.evaluate(() => ({
  rows: document.querySelectorAll('#searchResults .search-row').length,
  unitRows: document.querySelectorAll('#searchResults .sr-kind-unit').length,
  techRows: document.querySelectorAll('#searchResults .sr-kind-tech').length,
}));
await page.locator('#searchResults .search-row:has(.sr-kind-unit)').first().click().catch(() => {});
await page.waitForTimeout(500);
searchInfo.pickedUnit = await page.evaluate(() =>
  document.querySelector('#rail .unit-sheet .tech-d-name')?.textContent || null);

console.log(JSON.stringify({ shot: out, live: liveBase, packInfo, gridInfo, inspInfo, searchInfo, errors }, null, 2));
await browser.close();
server.close();
