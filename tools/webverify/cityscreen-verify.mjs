// cityscreen-verify — the city screen + buildings-on-the-map check (docs/city-screen-plan.md).
// Serves web/ statically, points it at a running spectator server, and:
//   1. deep-zooms the colony's province so the district layer draws → map shot (icon band)
//   2. zooms deeper → map shot (band-6 footprint band)
//   3. opens the settlement (window.__city.open) → city-screen shot, and reports what it found
//      (plots listed, buildings, constructions under way, the queue, whether the verbs show)
//
//   node cityscreen-verify.mjs [--live=http://localhost:8080] [--prov=4411] [--out=cityscreen]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const flags = Object.fromEntries(process.argv.slice(2).filter(a => a.startsWith('--'))
  .map(a => a.replace(/^--/, '').split('=')));
const liveBase = flags.live || 'http://localhost:8080';
const prov = +(flags.prov || 4411);
const out = flags.out || 'cityscreen';
const webDir = path.resolve(path.join(here, '..', '..', 'web'));

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.png': 'image/png', '.webp': 'image/webp', '.json': 'application/json',
  '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0].split('#')[0];
  const file = path.join(webDir, url === '/' ? 'index.html' : decodeURIComponent(url));
  if (!file.startsWith(webDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404); return res.end('nf');
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(r => server.listen(0, r));
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
// --user=<id> sends the dev identity header on every request, so the owner-gated write controls
// (reorder / cancel / decree) show. The server only trusts it under
// --civstudio.auth.trust-dev-user-header=true; without the flag this check sees a spectator's
// read-only screen, which is itself worth verifying.
const page = await browser.newPage({ viewport: { width: 1500, height: 950 }, deviceScaleFactor: 2 });
// The header rides ONLY on /api/** calls: those allow any header cross-origin (WebConfig), while
// the Actuator polls do not — sending it on everything turns the health poll into a preflight the
// server refuses, and the page spends the run shouting CORS at a problem that isn't ours.
if (flags.user) await page.route('**/api/**', route => route.continue({
  headers: { ...route.request().headers(), 'X-CivStudio-User': flags.user },
}));
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(`${base}/?p=${prov}&z=60&live=${encodeURIComponent(liveBase)}`, { waitUntil: 'load' });
await page.waitForTimeout(2000);
await page.keyboard.press('Escape');           // the lobby opens over the map during load
await page.waitForTimeout(400);
await page.evaluate(() => document.querySelector('#overlayToggle button[data-ov="live"]')?.click());
await page.waitForTimeout(4000);               // let a snapshot or two land

await page.screenshot({ path: path.join(here, `${out}-icons.png`) });

// deeper: past band 6, where the icons hand over to real footprints
await page.goto(`${base}/?p=${prov}&z=160&live=${encodeURIComponent(liveBase)}`, { waitUntil: 'load' });
await page.waitForTimeout(2000);
await page.keyboard.press('Escape');
await page.evaluate(() => document.querySelector('#overlayToggle button[data-ov="live"]')?.click());
await page.waitForTimeout(4000);
await page.screenshot({ path: path.join(here, `${out}-footprints.png`) });

// the settlement itself
await page.evaluate(() => window.__city && window.__city.open());
await page.waitForTimeout(1800);
await page.screenshot({ path: path.join(here, `${out}-screen.png`) });

const found = await page.evaluate(() => {
  const txt = id => (document.getElementById(id) || {}).textContent || '';
  return {
    name: txt('cityName'),
    sub: txt('citySub'),
    plots: document.querySelectorAll('#cityPlots .city-plot').length,
    buildings: document.querySelectorAll('#cityPlots .city-b').length,
    rising: document.querySelectorAll('#cityPlots .city-rising').length,
    active: txt('cityActive').trim().slice(0, 90),
    queued: document.querySelectorAll('#cityQueue .city-q').length,
    decree: !document.getElementById('cityDecree').hidden,
    readonly: document.getElementById('cityQueue').classList.contains('readonly'),
  };
});
console.log('city screen:', JSON.stringify(found, null, 1));

// the decree menu
if (found.decree) {
  await page.evaluate(() => document.getElementById('cityDecree').click());
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(here, `${out}-decree.png`) });
  const rows = await page.evaluate(() => document.querySelectorAll('#cityPickList .bc-item').length);
  console.log('decree menu rows:', rows);

  // the write path, end to end through the UI: pick two, add them, and see the queue come back
  // from the server on the next snapshot
  await page.evaluate(() => {
    const r = document.querySelectorAll('#cityPickList .bc-item');
    r[0].click(); r[1].click();
  });
  await page.evaluate(() => document.getElementById('cityPickAdd').click());
  await page.waitForTimeout(9000);   // the order applies at the top of the NEXT tick, then a snapshot carries it back
  const after = await page.evaluate(() => ({
    queued: document.querySelectorAll('#cityQueue .city-q').length,
    names: [...document.querySelectorAll('#cityQueue .city-q-name')].map(e => e.textContent),
    active: (document.getElementById('cityActive') || {}).textContent.trim().slice(0, 70),
  }));
  console.log('after decree:', JSON.stringify(after));
  await page.screenshot({ path: path.join(here, `${out}-queued.png`) });
}

console.log(errors.length ? 'CONSOLE ERRORS:\n' + errors.join('\n') : 'no console errors');
await browser.close();
server.close();
