// tech-verify — verify the Technology advisor's full-canvas map-mode against a live server.
// Serves web/ over HTTP (with Range, like the deployed SWA), points the bundle fetch at a live base
// via ?live=, boots the map, enters the Technology advisor (data-advisor="technology"), and reports:
//   - console/page errors
//   - whether the tree rendered in the stage region (node count, era-tab count in the top-bar sub-bar)
//   - whether the top bar + right rail stay live (the whole point of the map-mode refactor)
//   - node click → the detail renders in the shared right rail (.tech-sheet)
// plus a screenshot.
//
// Usage: node tech-verify.mjs [out.png] [--live=http://localhost:8080] [--web=../../web] [--wait=2500]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flags = {}; const pos = [];
for (const a of argv) { const m = /^--([^=]+)=(.*)$/.exec(a); if (m) flags[m[1]] = m[2]; else pos.push(a); }
const out = pos[0] || 'tech.png';
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
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}#none`, { waitUntil: 'domcontentloaded' });
await page.waitForFunction(() => !!window.BUNDLE, { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1200);

// enter the Technology advisor
await page.click('#advisorToggle button[data-advisor="technology"]').catch(e => errors.push('advisor click: ' + e.message));
await page.waitForTimeout(waitMs);

// click a node that unlocks buildings (has a spectrum bar) to exercise the rail grid
const node = page.locator('.tech-node:has(.tech-spec)').first();
await node.hover().catch(() => {});
await page.waitForTimeout(300);
await node.click().catch(() => {});
await page.waitForTimeout(700);
const gridInfo = await page.evaluate(() => ({
  gridCells: document.querySelectorAll('#rail .tech-grid .tech-bcell').length,
  groups: document.querySelectorAll('#rail .tech-grid .tech-bgroup').length,
}));
// click the first building cell → the inspector
await page.locator('#rail .tech-bcell').first().click().catch(e => errors.push('bcell click: ' + e.message));
await page.waitForTimeout(500);
const bldInfo = await page.evaluate(() => ({
  buildingSheet: !!document.querySelector('#rail .building-sheet'),
  buildingName: document.querySelector('#rail .building-sheet .tech-d-name')?.textContent || null,
  hasArt: !!document.querySelector('#rail .bld-art'),
}));
if (flags.inspector) { await page.screenshot({ path: out }); await browser.close(); server.close(); process.exit(0); }
// back to the tech
await page.locator('#rail [data-bld-back]').click().catch(() => {});
await page.waitForTimeout(400);

const info = await page.evaluate(() => {
  const modal = document.getElementById('techModal');
  const cr = modal?.getBoundingClientRect();
  const topbar = document.querySelector('.topbar')?.getBoundingClientRect();
  const rail = document.getElementById('railwrap');
  const subbarTech = document.querySelector('#advisorSubbar .advisor-sub[data-sub="technology"]');
  return {
    modalHidden: modal?.hidden,
    modalTop: cr ? Math.round(cr.top) : null,
    coversBelowTopbar: cr && topbar ? cr.top >= Math.round(topbar.bottom) - 2 : null,
    nodeCount: document.querySelectorAll('.tech-node').length,
    spectrumBars: document.querySelectorAll('.tech-spec').length,
    gridPop: !!document.querySelector('.tech-grid'),
    eraTabCount: document.querySelectorAll('#advisorSubbar #techEras button').length,
    eraSubbarVisible: subbarTech ? !subbarTech.hidden : null,
    topbarVisible: topbar ? topbar.height > 0 : null,
    railOpen: rail?.classList.contains('open'),
    railHasTechSheet: !!document.querySelector('#rail .tech-sheet'),
    railTechName: document.querySelector('#rail .tech-d-name')?.textContent || null,
  };
});
await page.screenshot({ path: out });
console.log(JSON.stringify({ shot: out, live: liveBase, info, gridInfo, bldInfo, errors }, null, 2));
await browser.close();
server.close();
