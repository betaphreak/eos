// Drive the WorldMap's Live overlay against a running spectator server.
// Usage: node live-shot.mjs <siteBase> <liveServer> <outPng> [waitMs]
//   e.g. node live-shot.mjs http://localhost:8000 http://localhost:8097 live.png 6000
import { chromium } from 'playwright-core';
const [, , site, live, out, waitMs = '6000'] = process.argv;
const url = `${site.replace(/\/$/, '')}/?live=${encodeURIComponent(live)}`;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(url, { waitUntil: 'load' });
await page.waitForTimeout(1500);
// the spectator lobby opens over the map during load — hide it by default so a live shot sees the
// map, not the modal + its backdrop blur (Esc → map). Harmless if no lobby is showing.
await page.keyboard.press('Escape');
await page.waitForTimeout(300);
// switch to Live mode (dispatch the button's own click — reliable in headless where the
// segmented-toggle geometry can defeat a pointer click)
await page.evaluate(() => document.querySelector('#overlayToggle button[data-ov="live"]').click());
await page.waitForTimeout(+waitMs);
const hud = await page.evaluate(() => {
  const t = id => (document.getElementById(id) || {}).textContent || '';
  const cav = document.querySelectorAll('#liveStats tr').length;
  return { state: t('liveState'), tick: t('liveTick'), date: t('liveDate'),
           sid: t('liveSid'), bankTax: t('liveBankTaxCur'), rows: cav };
});
// exercise play/pause routing to the server (togglePlay → /control), then read state again
await page.evaluate(() => document.getElementById('playBtn').click());
await page.waitForTimeout(2500);
const afterPause = await page.evaluate(() => (document.getElementById('liveState') || {}).textContent || '');
await page.screenshot({ path: out });
console.log('LIVE HUD:', JSON.stringify(hud));
console.log('state after play/pause click:', afterPause);
console.log('errors:', errors.length ? errors : 'none');
await browser.close();
