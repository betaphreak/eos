// Verify the header sign-in control (js/auth.mjs) renders on the map site. Serves the local web/
// dir and connects it to a live server (?server=dev), so app.js boots and initSiteAuth() runs.
// Usage: node site-auth-shot.mjs <outDir>
import { chromium } from 'playwright-core';
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const outDir = process.argv[2] || '.';
const webRoot = path.resolve(process.cwd(), '..', '..', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.webp': 'image/webp', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

const server = http.createServer(async (req, res) => {
  try {
    let rel = decodeURIComponent(req.url.split('?')[0]);
    if (rel === '/') rel = '/index.html';
    const file = path.join(webRoot, rel);
    const body = await readFile(file);
    res.setHeader('Content-Type', MIME[path.extname(file).toLowerCase()] || 'application/octet-stream');
    res.end(body);
  } catch { res.statusCode = 404; res.end('not found'); }
});
await new Promise(r => server.listen(0, r));
const port = server.address().port;
const url = `http://localhost:${port}/?server=dev`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 860 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(url, { waitUntil: 'load' });
// wait for the app to connect + initSiteAuth() to fill the control
await page.waitForFunction(() => {
  const el = document.getElementById('siteAuth');
  return el && el.querySelector('.site-auth-btn');
}, { timeout: 25000 }).catch(() => {});

const btnText = await page.evaluate(() => {
  const b = document.querySelector('#siteAuth .site-auth-btn');
  return b ? b.textContent.trim() : null;
});
// open the dropdown and read the provider options
let menu = [];
if (btnText) {
  await page.click('#siteAuth .site-auth-btn');
  await page.waitForTimeout(400);
  menu = await page.evaluate(() =>
    [...document.querySelectorAll('#siteAuth .site-auth-menu button')].map(b => b.textContent.trim()));
}
await page.screenshot({ path: `${outDir}/site-auth.png` });
console.log('siteAuth button ->', btnText ? JSON.stringify(btnText) : 'NOT RENDERED');
console.log('menu options    ->', JSON.stringify(menu));
console.log('console errors  ->', errors.length ? errors.slice(0, 5) : 'none');
await browser.close();
server.close();
