// Screenshot the WorldMap focused on a province via URL query params (?p=<id>&z=<zoom>).
// Usage: node shot.mjs <baseUrl> <p> <z> <outPng> [waitMs]
//   e.g. node shot.mjs http://localhost:8099/ 104 150 out.png
import { chromium } from 'playwright-core';
const [, , base, pid, z, out, waitMs = '2200'] = process.argv;
// #none forces the plain physical overlay: the site now DEFAULTS to the live "Spectate" overlay,
// which shows the server-picker splash (no map) unless a server is chosen — so a province/plot
// screenshot must opt out of it. See web/js/core.mjs (overlay default) + panel.mjs.
const url = `${base.replace(/\/$/, '')}/?p=${pid}&z=${z}#none`;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'networkidle' });
await page.waitForTimeout(+waitMs);
await page.screenshot({ path: out });
console.log('shot:', out, '| url:', url, '| errors:', errors.length ? errors : 'none');
await browser.close();
