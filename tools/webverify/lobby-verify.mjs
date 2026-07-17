// lobby-verify — drive the Spectator Lobby in a real browser (docs/spectator-lobby.md Phase 5):
// open it from "home", read the session list, post to the lobby chat and see it arrive, walk the
// new-run setup panel, and get back to the map with Esc.
//
// This verifies the SIGNED-OUT lobby — list, spectate, the live chat feed, and the sign-in prompt —
// which is exactly what an anonymous visitor gets. (The page cannot sign itself in: the dev user
// header is a request header a browser will not add. The signed-in paths are covered by the server
// suite + lobby-rows.test.mjs.)
//
// It posts one chat message server-side to prove the feed pushes, so the server must TRUST the dev
// header — start it with:
//   mvn -o -pl civstudio-server spring-boot:run \n//     "-Dspring-boot.run.arguments=--civstudio.auth.trust-dev-user-header=true --civstudio.dev.frontend.enabled=false"
// (this script serves web/ itself, so the node frontend is not needed).
//
//   node lobby-verify.mjs [--live=<base>]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const flags = Object.fromEntries(process.argv.slice(2).filter(a => a.startsWith('--'))
  .map(a => a.replace(/^--/, '').split('=')));
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
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}`, { waitUntil: 'domcontentloaded' });
await page.waitForFunction(() => window.BUNDLE && document.getElementById('rail'), null, { timeout: 30000 }).catch(() => {});
await page.waitForTimeout(6000);
await page.evaluate(() => {
  const c = [...document.querySelectorAll('button')].find(x => /got it/i.test(x.textContent)); if (c) c.click();
});

// "home" — the brand — is how you get to the lobby
// dispatch the element's own click: headless pointer-events let the topbar intercept a real
// click on the brand (the same reason live-shot.mjs clicks its toggle this way)
await page.evaluate(() => document.getElementById('brand').click());
await page.waitForTimeout(1500);

const opened = await page.evaluate(() => {
  const el = document.getElementById('lobby');
  return {
    // computed style, not the hidden attribute — see the setup panel below for why that matters
    visible: !!el && getComputedStyle(el).display !== 'none',
    server: (document.getElementById('lobbyServer') || {}).textContent || '',
    rows: [...document.querySelectorAll('#lobbySessions .lb-item')].map(r => ({
      title: r.querySelector('.lb-item-title')?.textContent || '',
      sub: r.querySelector('.lb-item-sub')?.textContent || '',
      deletable: !!r.querySelector('.lb-del'),
    })),
    // the composer must be GONE when signed out, not merely marked hidden — computed style again
    composerShown: (() => { const f = document.getElementById('lobbySay'); return !!f && getComputedStyle(f).display !== 'none'; })(),
    signInControl: !!document.querySelector('#lobby #siteAuth .site-auth-btn'),
    solo: { label: document.getElementById('lobbySolo')?.textContent,
            disabled: document.getElementById('lobbySolo')?.disabled,
            hint: document.getElementById('lobbySolo')?.title },
    ranked: { label: document.getElementById('lobbyRanked')?.textContent,
              disabled: document.getElementById('lobbyRanked')?.disabled,
              hint: document.getElementById('lobbyRanked')?.title },
  };
});
await page.screenshot({ path: path.join(here, 'lobby.png') });

// the chat: post one server-side (the page is signed out) and watch it arrive over the feed
await fetch(`${liveBase}/api/lobby/chat`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-CivStudio-User': 'webverify' },
  body: JSON.stringify({ text: 'pushed while the lobby was open' }),
}).catch(() => {});
await page.waitForTimeout(2500);
const chat = await page.evaluate(() =>
  [...document.querySelectorAll('#lobbyChat .lb-line')].map(l => l.textContent));

// Signed out, Single Player is (correctly) disabled — so clicking it must do NOTHING. The setup
// panel itself is only reachable signed in, which this page cannot be (the dev user header is a
// request header the browser will not add); its behaviour is covered by SaveSlotTest + the
// lobby-rows unit tests.
await page.evaluate(() => document.getElementById('lobbySolo').click());
await page.waitForTimeout(600);
const setup = await page.evaluate(() => {
  const el = document.getElementById('lobbySetup');
  // the COMPUTED style, not the hidden attribute: a class that sets display overrides [hidden], so
  // the attribute can say hidden while the panel is plainly on screen (it did)
  const shown = !!el && getComputedStyle(el).display !== 'none';
  return { visible: shown,
    seed: (document.getElementById('setupSeed') || {}).value || '',
    province: (document.getElementById('setupProvince') || {}).value || '' };
});
await page.screenshot({ path: path.join(here, 'lobby-setup.png') });

// Esc backs out of setup first, then to the map — a step inside the lobby, not another screen
await page.keyboard.press('Escape');
await page.waitForTimeout(400);
const afterEsc1 = await page.evaluate(() => ({
  setup: !document.getElementById('lobbySetup').hidden,
  lobby: !document.getElementById('lobby').hidden }));
await page.keyboard.press('Escape');
await page.waitForTimeout(400);
const afterEsc2 = await page.evaluate(() => !document.getElementById('lobby').hidden);

const checks = {
  lobbyOpensFromHome: opened.visible,
  listsWhatIsRunning: opened.rows.length > 0,
  chatArrivedLive: chat.some(l => l.includes('pushed while the lobby was open')),
  signedOutCannotPlay: opened.solo.disabled === true && /Sign in/i.test(opened.solo.hint || ''),
  signInLivesInTheLobby: opened.signInControl === true,
  composerHiddenWhenSignedOut: opened.composerShown === false,
  rankedSaysThereIsNoTimeline: opened.ranked.disabled === true,
  setupStaysShutForTheSignedOut: setup.visible === false,
  escLeavesTheLobby: afterEsc1.lobby === false || afterEsc2 === false,
  noErrors: errors.length === 0,
};
const pass = Object.values(checks).every(Boolean);
console.log(JSON.stringify({ opened, chat, setup, checks, pass, errors }, null, 2));
if (!pass) process.exitCode = 1;
await browser.close();
server.close();
