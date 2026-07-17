// lobby-load-verify — the lobby DURING the load (docs/spectator-lobby.md Phase 5).
//
// The claim: a visitor lands in the lobby while the world is still downloading, so the wait becomes
// the choosing rather than a progress bar. This proves it by watching the page from the first byte:
// the lobby must be up BEFORE /api/bundle has finished, and the map must still arrive afterwards.
//
// Needs a server (the node frontend is not used — this script serves web/ itself):
//   mvn -o -pl civstudio-server spring-boot:run \
//     "-Dspring-boot.run.arguments=--civstudio.dev.frontend.enabled=false"
//
//   node lobby-load-verify.mjs [--live=<base>]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const flags = Object.fromEntries(process.argv.slice(2).filter(a => a.startsWith('--'))
  .map(a => a.replace(/^--/, '').split('=')));
const liveBase = flags.live || process.env.LIVE || 'http://localhost:8080';
const webDir = path.resolve(path.join(here, '..', '..', 'web'));

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
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

// Timeline of the load, from the browser's own clock: when did the bundle finish, and when did the
// lobby appear? The whole claim is the ORDER of those two.
const t0 = Date.now();
const marks = { bundleDone: null, lobbyUp: null, mapUp: null };
page.on('response', r => {
  if (r.url().endsWith('/api/bundle') && marks.bundleDone === null) marks.bundleDone = Date.now() - t0;
});

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}`, { waitUntil: 'commit' });

// poll fast: we are racing the bundle on purpose
const deadline = Date.now() + 40000;
while (Date.now() < deadline && (marks.lobbyUp === null || marks.mapUp === null)) {
  const state = await page.evaluate(() => ({
    lobby: (() => { const el = document.getElementById('lobby'); return !!el && getComputedStyle(el).display !== 'none'; })(),
    map: !!window.BUNDLE,
  })).catch(() => ({ lobby: false, map: false }));
  if (state.lobby && marks.lobbyUp === null) marks.lobbyUp = Date.now() - t0;
  if (state.map && marks.mapUp === null) marks.mapUp = Date.now() - t0;
  await page.waitForTimeout(25);
}

await page.waitForTimeout(3000);
const after = await page.evaluate(() => ({
  lobbyStillUp: (() => { const el = document.getElementById('lobby'); return !!el && getComputedStyle(el).display !== 'none'; })(),
  rows: [...document.querySelectorAll('#lobbySessions .lb-item .lb-item-title')].map(e => e.textContent),
  server: (document.getElementById('lobbyServer') || {}).textContent || '',
}));
await page.screenshot({ path: path.join(here, 'lobby-during-load.png') });

// picking a session during the load must survive into the app that boots afterwards
await page.evaluate(() => {
  const first = document.querySelector('#lobbySessions .lb-item-main');
  if (first) first.click();
});
await page.waitForTimeout(4000);
const picked = await page.evaluate(() => ({
  stashed: window.__spectate || null,
  lobbyClosed: (() => { const el = document.getElementById('lobby'); return !el || getComputedStyle(el).display === 'none'; })(),
}));

const checks = {
  lobbyAppeared: marks.lobbyUp !== null,
  // the point of the whole exercise: the lobby is up while the world is still coming down the wire
  lobbyBeatTheBundle: marks.lobbyUp !== null && marks.bundleDone !== null && marks.lobbyUp < marks.bundleDone,
  listedSessionsDuringLoad: after.rows.length > 0,
  mapStillArrived: marks.mapUp !== null,
  choiceSurvivedTheLoad: !!picked.stashed,
  lobbyClosedOnPick: picked.lobbyClosed,
  noErrors: errors.length === 0,
};
const pass = Object.values(checks).every(Boolean);
console.log(JSON.stringify({ msSinceNavigation: marks, after, picked, checks, pass, errors }, null, 2));
if (!pass) process.exitCode = 1;
await browser.close();
server.close();
