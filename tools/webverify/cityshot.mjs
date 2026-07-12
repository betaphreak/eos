// cityshot — verify the urban city sprite + the city info panel for a city province.
// Boots web/ against a live bundle server (default localhost:8080), leaves live mode, deep-zooms
// the province so its plots (and the baked city sprite) render, screenshots the viewport, then
// selects the province (opening the city rail) and screenshots the panel. See docs/urban-plots.md.
//
//   node cityshot.mjs <provId> [zoom=220] [outPrefix=city] [--live=<base>]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const pos = args.filter(a => !a.startsWith('--'));
const flags = Object.fromEntries(args.filter(a => a.startsWith('--')).map(a => a.replace(/^--/, '').split('=')));
const provId = +(pos[0] || 4411);
const zoom = +(pos[1] || 220);
const prefix = pos[2] || 'city';
const liveBase = flags.live || process.env.LIVE || 'http://localhost:8080';
const webDir = path.resolve(flags.web || path.join(here, '..', '..', 'web'));

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.png': 'image/png', '.webp': 'image/webp', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0].split('#')[0];
  const file = path.join(webDir, url === '/' ? 'index.html' : decodeURIComponent(url));
  if (!file.startsWith(webDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('nf'); }
  const size = fs.statSync(file).size, type = MIME[path.extname(file)] || 'application/octet-stream', range = req.headers.range;
  if (range) { const m = /bytes=(\d+)-(\d+)?/.exec(range), start = +m[1], end = m[2] ? +m[2] : size - 1;
    res.writeHead(206, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else { res.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': size }); fs.createReadStream(file).pipe(res); }
});
await new Promise(r => server.listen(0, r));
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}`, { waitUntil: 'domcontentloaded' });
await page.waitForFunction(() => window.BUNDLE && document.getElementById('rail'), null, { timeout: 30000 }).catch(() => {});
await page.waitForTimeout(6000);
// dismiss the cookie banner + leave live mode (the caravan demo would re-frame the camera)
await page.evaluate(() => {
  const c = [...document.querySelectorAll('button')].find(x => /got it/i.test(x.textContent)); if (c) c.click();
  const none = document.querySelector('[data-ov="none"]'); if (none) none.click();
});
await page.waitForTimeout(1000);
// deep-zoom the province (its plots + the baked city sprite render past the texture zoom)
await page.evaluate(({ pid, z }) => { location.hash = `#p=${pid}&z=${z}`; window.dispatchEvent(new HashChangeEvent('hashchange')); }, { pid: provId, z: zoom });
await page.waitForTimeout(7000); // let the per-plot terrain bake
await page.screenshot({ path: path.join(here, `${prefix}-sprite.png`) });

// select the province → the city info panel opens in the rail
await page.evaluate(() => { const s = document.getElementById('stage'); const r = s.getBoundingClientRect();
  s.dispatchEvent(new MouseEvent('click', { clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, detail: 1 })); });
await page.waitForTimeout(3000);
await page.screenshot({ path: path.join(here, `${prefix}-panel.png`) });

const diag = await page.evaluate(() => ({
  railOpen: document.getElementById('railwrap')?.classList.contains('open'),
  railText: (document.getElementById('rail')?.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 240),
}));
console.log(JSON.stringify({ province: provId, zoom, sprite: `${prefix}-sprite.png`, panel: `${prefix}-panel.png`, diag, errors }, null, 2));
await browser.close();
server.close();
