// Live-preview candidate login CSS against the deployed Strapi login WITHOUT deploying: inject a CSS
// file via addStyleTag and screenshot (both themes). Usage: node login-preview.mjs [cssFile]
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';
const css = readFileSync(process.argv[2] || 'login-candidate.css', 'utf8');
const url = 'https://civstudio.com/admin/auth/login';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
for (const theme of ['dark', 'light']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 1.5 });
  await page.addInitScript((t) => { try { localStorage.setItem('STRAPI_THEME', t); } catch {} }, theme);
  await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 });
  await page.waitForTimeout(1500);
  await page.addStyleTag({ content: css });
  await page.waitForTimeout(800);
  await page.screenshot({ path: `login-preview-${theme}.png` });
  console.log(`wrote login-preview-${theme}.png`);
  await page.close();
}
await browser.close();
