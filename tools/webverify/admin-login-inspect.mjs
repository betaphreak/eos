// Screenshot the deployed Strapi login + dump its auth-region DOM (tags/classes/roles) so we can
// target stable selectors for restyling. Usage: node admin-login-inspect.mjs [url] [theme]
import { chromium } from 'playwright-core';
const url = process.argv[2] || 'https://civstudio.com/admin/auth/login';
const theme = process.argv[3] || 'dark';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 1.5 });
await page.addInitScript((t) => { try { localStorage.setItem('STRAPI_THEME', t); } catch {} }, theme);
const errors = [];
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 });
await page.waitForTimeout(2500);
await page.screenshot({ path: `login-${theme}.png` });

// dump a shallow structural tree of the auth region (tag.class[role] up to depth 6)
const tree = await page.evaluate(() => {
  const desc = (el) => {
    const cls = (el.className && typeof el.className === 'string') ? '.' + el.className.trim().split(/\s+/).join('.') : '';
    const role = el.getAttribute?.('role') ? `[role=${el.getAttribute('role')}]` : '';
    const tag = el.tagName.toLowerCase();
    const id = el.id ? '#' + el.id : '';
    return `${tag}${id}${cls}${role}`.slice(0, 140);
  };
  const walk = (el, d, max) => {
    if (d > max) return '';
    let out = '  '.repeat(d) + desc(el) + '\n';
    for (const c of el.children) out += walk(c, d + 1, max);
    return out;
  };
  const main = document.querySelector('main') || document.body;
  return walk(main, 0, 5);
});
console.log(`theme=${theme}  errors=${errors.length ? errors : 'none'}`);
console.log('=== auth DOM tree ===\n' + tree);
// also the computed bg of body + the card
const bg = await page.evaluate(() => {
  const cs = getComputedStyle(document.body);
  return { bodyBg: cs.backgroundColor, font: cs.fontFamily };
});
console.log('body:', JSON.stringify(bg));
await browser.close();
