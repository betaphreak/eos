// End-to-end check of the plots.pack range-fetch path in a real browser.
// Serves a folder over HTTP with Range support, deep-links to a province at deep
// zoom, and confirms: plots.pack served 206, no console errors, plots populated.
//   node verify-pack.mjs <webDir> <provId> <outPng>
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';

const [, , webDirArg, provId, out, zoom = '40'] = process.argv;
const webDir = path.resolve(webDirArg);
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.png': 'image/png', '.json': 'application/json', '.pack': 'application/octet-stream',
  '.ico': 'image/x-icon' };

// static server WITH Range support (mirrors what SWA does for plots.pack)
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0].split('#')[0];
  const file = path.join(webDir, url === '/' ? 'index.html' : decodeURIComponent(url));
  if (!file.startsWith(webDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404); return res.end('not found');
  }
  const size = fs.statSync(file).size;
  const type = MIME[path.extname(file)] || 'application/octet-stream';
  const range = req.headers.range;
  if (range) {
    const m = /bytes=(\d+)-(\d+)?/.exec(range);
    const start = +m[1], end = m[2] ? +m[2] : size - 1;
    res.writeHead(206, { 'Content-Type': type, 'Accept-Ranges': 'bytes',
      'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else {
    res.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': size });
    fs.createReadStream(file).pipe(res);
  }
});
await new Promise(r => server.listen(0, r));
const port = server.address().port;
const base = `http://localhost:${port}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
const errors = [];
const packHits = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
const notFound = [];
page.on('response', r => {
  if (r.url().includes('plots.pack')) packHits.push(r.status());
  if (r.status() === 404) notFound.push(r.url());
});

await page.goto(`${base}/index.html#p=${provId}&z=${zoom}`, { waitUntil: 'networkidle' });
await page.waitForTimeout(3000);

const state = await page.evaluate((id) => {
  const B = window.BUNDLE;
  if (!B) return { bundleLoaded: false };
  const p = (B.provinces || []).find(q => q.id === +id);
  return { bundleLoaded: true, provinceFound: !!p,
    plots: p && p._plots ? p._plots.length : 0,
    plotIndexEntries: B.plotIndex ? Object.keys(B.plotIndex).length : 0,
    camK: window.cam ? window.cam.k : 'n/a' };
}, provId);

await page.screenshot({ path: out });
const s206 = packHits.filter(s => s === 206).length;
console.log(JSON.stringify({
  packRangeRequests: packHits.length, pack206: s206, packOther: packHits.filter(s => s !== 206),
  plotsLoaded: state, notFoundUrls: notFound, consoleErrors: errors
}, null, 2));
await browser.close();
server.close();