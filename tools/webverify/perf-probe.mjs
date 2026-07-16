// Measure paint() cost at Atlas zoom (worst case: several world copies, every polygon layer live).
import { chromium } from 'playwright-core';
const b = await chromium.launch({ channel: 'msedge', headless: true });
const page = await b.newPage({ viewport: { width: 1600, height: 900 } });
const errs = []; page.on('pageerror', e => errs.push(e.message));
await page.goto('http://localhost:3000/?live=' + encodeURIComponent('http://localhost:8080') + '#nation', { waitUntil: 'domcontentloaded' });
await page.waitForSelector('#zoomLevel', { timeout: 45000 });
await page.waitForTimeout(6000);
const r = await page.evaluate(async () => {
  const core = await import('./js/core.mjs');
  const main = await import('./js/main.mjs');
  const out = {};
  for (const k of [1, 4, 16]) {
    core.cam.k = k; core.clampPan();
    const times = [];
    for (let i = 0; i < 40; i++) {
      core.S.baseVersion++;              // force a real repaint each sample (no cache short-circuit)
      const t0 = performance.now();
      main.draw();
      await new Promise(requestAnimationFrame);
      times.push(performance.now() - t0);
    }
    times.sort((a, b) => a - b);
    out[k + 'x'] = { median: +times[20].toFixed(2), p90: +times[36].toFixed(2) };
  }
  return out;
});
console.log(JSON.stringify(r));
console.log('errors:', errs.length ? errs : 'none');
await b.close();
