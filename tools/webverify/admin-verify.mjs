// Smoke-checks the admin console: the page's inline script must parse and run (a syntax error there
// takes the whole console down, gate included), and the Taxation panel must exist with its controls.
// Auth is not exercised — an unauthenticated load should land on the gate, which is itself the proof
// the script ran. Point this at the SERVER (the console is served at /, see PageController), not at
// the static dev-server, or every /api/admin call 404s into the page's error path.
// Usage: node admin-verify.mjs <serverBase>
import { chromium } from 'playwright-core';

const [, , base] = process.argv;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(String(e)));
await page.goto(`${base}/`, { waitUntil: 'networkidle' }).catch(() => {});
await page.waitForTimeout(2500);
const state = await page.evaluate(() => ({
  taxPanel: !!document.getElementById('taxSession'),
  taxInputs: !!document.getElementById('taxBank') && !!document.getElementById('taxNoble'),
  taxButton: !!document.getElementById('btnTax'),
  panels: [...document.querySelectorAll('#console .panel h2')].map(h => h.textContent),
  gateShown: !document.getElementById('gate').hidden,
  // proof the inline script executed rather than dying on a parse error
  scriptRan: typeof window.ctrl === 'function',
}));
console.log(JSON.stringify({ state, errors: errors.filter(e => !/401|403|Failed to load resource/.test(e)) }, null, 2));
await browser.close();
