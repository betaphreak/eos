// Verifies index.html's server-gated bootstrap: with the map server UP the page should poll
// /actuator/health, fetch the bundle, and boot the app (loading screen clears, window.BUNDLE set);
// with the server DOWN it should hold the "Maintenance Mode" splash and retry. Usage:
//   node boot-check.mjs <liveBase> [waitMs]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '../../web');
const base = process.argv[2] || 'http://localhost:8080';
const waitMs = +(process.argv[3] || 9000);
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.webp': 'image/webp', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.css': 'text/css', '.pack': 'application/octet-stream', '.svg': 'image/svg+xml' };

const srv = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  const f = path.join(WEB, p);
  fs.readFile(f, (e, data) => {
    if (e) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream' });
    res.end(data);
  });
});
await new Promise(r => srv.listen(0, r));
const port = srv.address().port;
const url = `http://localhost:${port}/index.html?live=${base}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(waitMs);
const state = await page.evaluate(() => ({
  maintenance: !!document.querySelector('.ld-maint'),
  loadingPresent: !!document.getElementById('loading'),
  hasBundle: !!window.BUNDLE,
  provinces: (window.BUNDLE && window.BUNDLE.provinces && window.BUNDLE.provinces.length) || 0,
}));
console.log(JSON.stringify({ base, state, errors }, null, 2));
await browser.close();
srv.close();
