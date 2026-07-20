// Diagnostic: dump the admin's nav + settings links and anything marked as a locked/EE upsell, so
// the hide rules in studio/src/admin/app.tsx target real selectors instead of guesses.
//
// Usage: node admin-locked-items.mjs [strapiBase]
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const base = process.argv[2] || DEFAULT_BASE;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
await page.addInitScript(() => { try { localStorage.setItem('STRAPI_THEME', 'dark'); } catch {} });
await loginAdmin(page, { base });

const dumpLinks = (label) => page.evaluate((lbl) => {
  const out = [];
  for (const a of document.querySelectorAll('a[href]')) {
    const text = (a.textContent || '').trim().replace(/\s+/g, ' ');
    if (!text) continue;
    const style = getComputedStyle(a);
    out.push({ where: lbl, text, href: a.getAttribute('href'), hidden: style.display === 'none' });
  }
  return out;
}, label);

await page.goto(`${base}/admin/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('nav', { timeout: 60000 });
await page.waitForTimeout(4000);
const nav = await dumpLinks('main-nav');

await page.goto(`${base}/admin/settings`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(5000);
const settings = await dumpLinks('settings');

console.log('=== MAIN NAV ===');
for (const l of nav) console.log(`${l.hidden ? '[hidden] ' : '         '}${l.text}  ->  ${l.href}`);
console.log('\n=== SETTINGS ===');
for (const l of settings) console.log(`${l.hidden ? '[hidden] ' : '         '}${l.text}  ->  ${l.href}`);

// anything the UI itself marks as an upsell / locked feature
const locked = await page.evaluate(() => {
  const hits = [];
  for (const el of document.querySelectorAll('*')) {
    const t = (el.textContent || '').trim();
    if (el.children.length === 0 && /^(Upgrade|Locked|Enterprise|Growth|Premium)$/i.test(t))
      hits.push({ tag: el.tagName, text: t, parent: (el.parentElement?.textContent || '').trim().slice(0, 80) });
  }
  return hits;
});
console.log('\n=== LOCKED/UPSELL MARKERS (settings page) ===');
console.log(locked.length ? JSON.stringify(locked, null, 1) : '(none found on this page)');

await page.screenshot({ path: 'admin-settings.png', fullPage: true });
await browser.close();
