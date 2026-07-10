// mapshot — screenshot the WorldMap viewport at a province/zoom, against a live server, without
// writing a new script each time. Serves web/ over HTTP (with Range, like the deployed SWA), points
// the page's bundle fetch at a live base via ?live=, deep-links to the province, waits for the
// terrain to bake, screenshots (optionally a centred crop), and prints console errors + a short
// province diagnostic (plot count, offscreen w/h, computed tpp, terrain/layer counts, snowy plots).
//
// Usage:
//   node mapshot.mjs <provId> [zoom=64] [out=shot.png] [WxH]
//   flags: --live=<base>   bundle server           (default http://localhost:8080, or $LIVE)
//          --web=<dir>     static site to serve     (default ../../web next to this script)
//          --wait=<ms>     settle time before shot  (default 7000)
//   e.g. node mapshot.mjs 412 256 p412.png 380x280
//        node mapshot.mjs 4411 256 --live=https://live.civstudio.com
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flags = {};
const pos = [];
for (const a of argv) { const m = /^--([^=]+)=(.*)$/.exec(a); if (m) flags[m[1]] = m[2]; else pos.push(a); }

const provId = pos[0];
if (!provId) { console.error('usage: node mapshot.mjs <provId> [zoom] [out.png] [WxH] [--live=] [--web=] [--wait=]'); process.exit(1); }
const zoom = pos[1] || '64';
const out = pos.find((p, i) => i >= 1 && /\.png$/i.test(p)) || 'shot.png';
const cropArg = pos.find(p => /^\d+x\d+$/.test(p));
const liveBase = flags.live || process.env.LIVE || 'http://localhost:8080';
const webDir = path.resolve(flags.web || path.join(here, '..', '..', 'web'));
const waitMs = +(flags.wait || 7000);

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
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

const url = `${base}/index.html?live=${encodeURIComponent(liveBase)}&p=${provId}&z=${zoom}`;
await page.goto(url, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(waitMs);

const info = await page.evaluate((pid) => {
  const B = window.BUNDLE; if (!B) return { bundle: false };
  const p = (B.provinces || []).find(q => q.id === +pid);
  if (!p) return { found: false };
  if (!p._plots) return { found: true, plotsLoaded: false, type: p.type };
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9; const terr = {}; let snow = 0;
  for (const q of p._plots) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; terr[q.terrain] = (terr[q.terrain] || 0) + 1; if ((q.elevation | 0) >= 165) snow++; }
  const PAD = 2, w = (x1 - x0 + 1) + 2 * PAD, h = (y1 - y0 + 1) + 2 * PAD;
  let tpp = 32; while (tpp > 4 && Math.max(w, h) * tpp > 2600) tpp = Math.max(4, tpp - 4);
  const TT = B.terrainTiles, LY = B.terrainLayer || {}, layers = {};
  for (const t of Object.keys(terr)) layers[t] = LY[t] || 0;
  // ASCII terrain map (one char per plot) + a "speck" metric: fraction of plots whose 4-neighbours
  // are ALL a different terrain (isolated 1-plot specks → a salt-and-pepper distribution no blend fixes)
  const keys = Object.keys(terr).sort((a, b) => terr[b] - terr[a]);
  const glyph = {}; keys.forEach((t, i) => glyph[t] = '#@%*+=:.'[i] || '?');
  const at = new Map(); for (const q of p._plots) at.set(q.x * 1e5 + q.y, q.terrain);
  const rows = []; let specks = 0, land = 0;
  for (let gy = y0; gy <= y1; gy++) { let row = '';
    for (let gx = x0; gx <= x1; gx++) { const t = at.get(gx * 1e5 + gy); row += t ? glyph[t] : ' '; } rows.push(row); }
  for (const q of p._plots) { land++; let diff = 0, n = 0;
    for (const [dx, dy] of [[1,0],[-1,0],[0,1],[0,-1]]) { const t = at.get((q.x+dx)*1e5+(q.y+dy)); if (t) { n++; if (t !== q.terrain) diff++; } }
    if (n >= 2 && diff === n) specks++; }
  return { found: true, plotsLoaded: true, type: p.type, plots: p._plots.length, w, h, tpp,
    textured: tpp >= 12 && !!TT, snowyPlots: snow, terrainCounts: terr, layers,
    speckFraction: +(specks / land).toFixed(2), glyph, terrainMap: rows,
    missingTiles: Object.keys(terr).filter(t => !(TT && TT.cols && (t in TT.cols))) };
}, provId);

const crop = cropArg ? (() => { const [w, h] = cropArg.split('x').map(Number); return { x: (1400 - w) / 2, y: (900 - h) / 2, width: w, height: h }; })() : undefined;
await page.screenshot({ path: out, clip: crop });
console.log(JSON.stringify({ shot: out, province: provId, zoom, live: liveBase, info, errors }, null, 2));
await browser.close();
server.close();
