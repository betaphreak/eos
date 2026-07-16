// Screenshot the WorldMap focused on a province via URL query params (?p=<id>&z=<zoom>).
// Usage: node shot.mjs <baseUrl> <p> <z> <outPng> [waitMs] [serverUrl]
//   e.g. node shot.mjs http://localhost:3000/ 4411 150 out.png
//        node shot.mjs http://localhost:3000/ 4411 150 out.png 8000 http://localhost:8080
// `baseUrl` serves web/ (the dev frontend on :3000, or a static server); `serverUrl` is the
// CivStudio server the page talks to — defaults to the usual local :8080.
import { chromium } from 'playwright-core';
const [, , base, pid, z, out, waitMs = '2200', server = 'http://localhost:8080'] = process.argv;
// Two opt-outs are needed to land on the map, and BOTH are load-bearing:
//   #none    → the plain physical overlay. The site DEFAULTS to the live "Spectate" overlay.
//   ?live=   → names the server directly, which skips the "Choose a server" splash. Without it the
//              page parks on the picker and the shot is of the splash, not the map (index.html
//              reads `live` and prepends it as a one-off entry; see also core.mjs SERVER_BASE).
const url = `${base.replace(/\/$/, '')}/?p=${pid}&z=${z}&live=${encodeURIComponent(server)}#none`;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'networkidle' });
// dismiss the cookie banner if it's up — it sits over the bottom-left of the map, and a shot of a
// province is not helped by a consent card on top of it
await page.getByRole('button', { name: /got it/i }).click({ timeout: 1500 }).catch(() => {});
await page.waitForTimeout(+waitMs);
await page.screenshot({ path: out });
console.log('shot:', out, '| url:', url, '| errors:', errors.length ? errors : 'none');
await browser.close();
