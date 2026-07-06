// Headless screenshot of a local page via system Edge (msedge). Usage:
//   node verify.mjs <url> <outPng> [waitMs] [w] [h]
import { chromium } from 'playwright-core';
const [, , url, out, waitMs = '1600', w = '1400', h = '900'] = process.argv;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: 1 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'networkidle' });
await page.waitForTimeout(+waitMs);
await page.screenshot({ path: out });
console.log('screenshot:', out, '| console errors:', errors.length ? errors : 'none');
await browser.close();
