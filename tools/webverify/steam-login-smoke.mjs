// Smoke-test the Steam "Sign in through Steam" flow against the live server, in a real browser.
// Drives the same-origin lobby page (dev.civstudio.com/ = web/lobby.html), which calls
// /api/auth/me on load and renders the sign-in control, then clicks it and follows the redirect
// up to Steam's real OpenID login page. Completing the login (Steam credentials + Steam Guard) is
// the human's to do — this verifies everything up to that point.
// Usage: node steam-login-smoke.mjs [base] [outDir]
import { chromium } from 'playwright-core';

const base = (process.argv[2] || 'https://dev.civstudio.com').replace(/\/$/, '');
const outDir = process.argv[3] || '.';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1200, height: 800 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

// 1) load the page; refreshAuth() runs on load and populates the #auth control
await page.goto(base + '/', { waitUntil: 'load' });
await page.waitForTimeout(1500);

// what /api/auth/me reports to an anonymous browser, and whether the sign-in button rendered
const me = await page.evaluate(async b => {
  try { return await (await fetch(b + '/api/auth/me', { credentials: 'include' })).json(); }
  catch (e) { return { error: String(e) }; }
}, base);
const btn = await page.evaluate(() => {
  const el = document.querySelector('#auth button.steam');
  return el ? el.textContent.trim() : null;
});
await page.screenshot({ path: `${outDir}/steam-1-page.png` });
console.log('GET /api/auth/me  ->', JSON.stringify(me));
console.log('sign-in button    ->', btn ? JSON.stringify(btn) : 'NOT FOUND');

// 2) click it and follow the redirect chain to Steam's login page
let landed = '(no navigation)';
if (btn) {
  await Promise.all([
    page.waitForURL(/steamcommunity\.com/, { timeout: 20000 }).catch(() => {}),
    page.evaluate(() => document.querySelector('#auth button.steam').click()),
  ]);
  await page.waitForTimeout(2500);
  landed = page.url();
  await page.screenshot({ path: `${outDir}/steam-2-login.png` });
  // is this actually Steam's OpenID sign-in form?
  const hasSteamForm = await page.evaluate(() =>
    !!document.querySelector('#loginForm, form[action*="login"], .newlogindialog, [class*="login"]'));
  console.log('landed on         ->', landed);
  console.log('steam login form  ->', hasSteamForm ? 'present' : 'not detected');
}
console.log('console errors    ->', errors.length ? errors : 'none');
await browser.close();
