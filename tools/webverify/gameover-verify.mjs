// gameover-verify — drive the client against a session that ENDED ITSELF and check the three
// things docs/game-over.md promises: the terminal screen shows with the server's reason, the
// never-give-up reconnect gives up, and the cached final frame's log delta is not re-posted to the
// notification board on every re-subscribe (the reported "departure keeps repeating" bug).
//
// Needs a server whose session is GAME_OVER, e.g.:
//   mvn -o -pl civstudio-server spring-boot:run \
//     -Dspring-boot.run.arguments="--civstudio.demo.tick-rate-millis=0 --civstudio.demo.seed=555"
// (seed 555, uncapped — it collapses in seconds and never touches the 7654321 world.)
//
//   node gameover-verify.mjs [--live=<base>] [watchMs=20000]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const pos = args.filter(a => !a.startsWith('--'));
const flags = Object.fromEntries(args.filter(a => a.startsWith('--')).map(a => a.replace(/^--/, '').split('=')));
const liveBase = flags.live || process.env.LIVE || 'http://localhost:8080';
const watchMs = +(pos[0] || 20000);
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

// what the server says the run's end was — the client must show exactly this
const sessions = await (await fetch(`${liveBase}/api/sessions`)).json();
const s0 = sessions[0] || {};
if (s0.state !== 'GAME_OVER') {
  console.error(`FATAL: session ${s0.id} is ${s0.state}, not GAME_OVER — nothing to verify.`);
  console.error('Start the server with --civstudio.demo.tick-rate-millis=0 and let it collapse.');
  server.close();
  process.exit(2);
}

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

// count the client's re-subscribes: a reconnect loop shows up as repeated /stream requests
let streamRequests = 0;
page.on('request', r => { if (/\/stream$/.test(r.url().split('?')[0])) streamRequests++; });

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);
await page.evaluate(() => {
  const c = [...document.querySelectorAll('button')].find(x => /got it/i.test(x.textContent)); if (c) c.click();
});
await page.waitForTimeout(2000);

const shown = await page.evaluate(() => {
  const el = document.getElementById('gameover');
  return {
    visible: !!el && !el.hidden,
    reason: (document.getElementById('goReason') || {}).textContent || '',
    date: (document.getElementById('goDate') || {}).textContent || '',
  };
});
await page.screenshot({ path: path.join(here, 'gameover.png') });

// Watch: a client that keeps chasing a dead session re-subscribes on a backoff, and each cached
// frame re-posts the departure card. Both must stay flat.
const streamsAtRest = streamRequests;
const cardsBefore = await page.evaluate(() => document.querySelectorAll('#notify .note, #notify > *').length);
await page.waitForTimeout(watchMs);
const streamsAfter = streamRequests;
const cardsAfter = await page.evaluate(() => document.querySelectorAll('#notify .note, #notify > *').length);

const checks = {
  terminalScreenShown: shown.visible,
  showsTheServersReason: shown.reason.trim() === String(s0.endReason).trim(),
  stoppedReconnecting: streamsAfter === streamsAtRest,
  subscribedAtMostOnce: streamsAtRest <= 1,
  boardStoppedGrowing: cardsAfter === cardsBefore,
  noErrors: errors.length === 0,
};
const pass = Object.values(checks).every(Boolean);
console.log(JSON.stringify({
  session: s0.id, state: s0.state, serverReason: s0.endReason,
  shown, streams: { atRest: streamsAtRest, afterWatch: streamsAfter },
  cards: { before: cardsBefore, after: cardsAfter }, watchedMs: watchMs,
  checks, pass, errors,
}, null, 2));
if (!pass) process.exitCode = 1;
await browser.close();
server.close();
