// Drives the admin console's Taxation panel for real, as an admin: it must prefill from the session's
// current rates and Apply must actually move the ruler's lever. This is the control that moved off the
// spectator page's live HUD, so "it renders" is not enough — it has to work where it landed.
//
// Needs a server started with the dev auth header trusted, e.g.
//   mvn -pl civstudio-server spring-boot:run \
//     -Dspring-boot.run.arguments="--civstudio.auth.trust-dev-user-header=true --civstudio.auth.admins=tester"
// Usage: node admin-tax-verify.mjs <serverBase> [user]
import { chromium } from 'playwright-core';

const [, , base, user = 'tester'] = process.argv;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({
  viewport: { width: 1200, height: 1000 },
  extraHTTPHeaders: { 'X-CivStudio-User': user },   // the dev-only identity the server trusts
});
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(String(e)));

await page.goto(`${base}/`, { waitUntil: 'networkidle' }).catch(() => {});
await page.waitForTimeout(3000);

const before = await page.evaluate(() => ({
  gated: !document.getElementById('gate').hidden,
  session: document.getElementById('taxSession').value,
  bank: document.getElementById('taxBank').value,
  noble: document.getElementById('taxNoble').value,
  stats: [...document.querySelectorAll('#taxStats .stat')].map(s => s.textContent.trim()),
}));

// move the bank lever to a value it cannot already be at, and apply
await page.fill('#taxBank', '0.42');
await page.click('#btnTax');
await page.waitForTimeout(3000);
const toast = await page.evaluate(() => (document.getElementById('toast') || {}).textContent || '');

// Read the truth from the server, not from the page that just claimed success. Commands are
// tick-stamped and this session runs at ~3s/tick, so poll rather than assume the toast's "applied at
// tick N" has actually happened yet — the ack means accepted, not applied.
const sid = before.session;
const rate = async () => {
  const s = await fetch(`${base}/api/sessions/${sid}/snapshot`).then(r => r.json()).catch(() => null);
  return s && s.colonies && s.colonies[0] ? s.colonies[0].bankProfitTax : null;
};
let applied = null;
for (let i = 0; i < 30; i++) {
  applied = await rate();
  if (applied === 0.42) break;
  await new Promise(r => setTimeout(r, 1000));
}

console.log(JSON.stringify({
  before,
  toast,
  bankProfitTaxOnServer: applied,
  verdict: applied === 0.42 ? 'APPLIED' : `NOT APPLIED (server says ${applied})`,
  errors: errors.slice(0, 5),
}, null, 2));
await browser.close();
