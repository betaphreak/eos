// Screenshot the Strapi admin "create Province" edit form — visual check of the generated
// content-manager view layout (scripts/gen-view-configs.mjs). Usage: node province-form-shot.mjs
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const uid = process.argv[2] || 'api::province.province';
const out = process.argv[3] || 'province-form.png';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1200 }, deviceScaleFactor: 1.5 });
const errs = [];
page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));

await loginAdmin(page, { base: DEFAULT_BASE });
await page.goto(`${DEFAULT_BASE}/admin/content-manager/collection-types/${uid}/create`, { waitUntil: 'domcontentloaded', timeout: 90000 });
// the edit form is ready once a field input and the Save button are on screen
await page.getByRole('button', { name: /^save$/i }).waitFor({ timeout: 60000 }).catch(() => {});
await page.waitForTimeout(2500);
await page.screenshot({ path: out, fullPage: true });
console.log(`wrote ${out}${errs.length ? ' (page errors: ' + errs.join('; ') + ')' : ''}`);
await browser.close();
