// Load the deployed Strapi admin and confirm the SPA mounts (no bootstrap/register crash).
// Usage: node admin-console-check.mjs [url]
import { chromium } from 'playwright-core';
const url = process.argv[2] || 'https://civstudio.com/admin';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 });
await page.waitForTimeout(3500);
const mounted = await page.evaluate(() => document.querySelector('#strapi')?.children.length || 0);
const bodyText = (await page.evaluate(() => document.body.innerText || '')).slice(0, 120).replace(/\s+/g, ' ');
console.log('url:', url);
console.log('title:', await page.title());
console.log('#strapi children:', mounted, mounted > 0 ? '(MOUNTED)' : '(BLANK — still broken)');
console.log('body:', bodyText);
console.log('console errors:', errors.length ? errors : 'none');
await browser.close();
process.exit(mounted > 0 && !errors.some((e) => /reading 'register'/.test(e)) ? 0 : 1);
