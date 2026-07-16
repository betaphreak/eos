// Where does paint() actually spend its time at Atlas zoom? Wrap every registry layer's draw fn
// and attribute cost per layer, per frame.
import { chromium } from 'playwright-core';
const b = await chromium.launch({ channel: 'msedge', headless: true });
const page = await b.newPage({ viewport: { width: 1600, height: 900 } });
await page.goto('http://localhost:3000/?live=' + encodeURIComponent('http://localhost:8080') + '#none', { waitUntil: 'domcontentloaded' });
await page.waitForSelector('#zoomLevel', { timeout: 45000 });
await page.waitForTimeout(6000);
for (const k of [1, 4]) {
  const r = await page.evaluate(async z => {
    const core = await import('./js/core.mjs');
    const main = await import('./js/main.mjs');
    const layers = await import('./js/layers.mjs');
    const acc = {};
    for (const L of layers.LAYERS) {
      if (L._orig) L.draw = L._orig;
      const orig = L.draw; L._orig = orig;
      L.draw = () => { const t = performance.now(); orig(); acc[L.id] = (acc[L.id] || 0) + (performance.now() - t); };
    }
    core.cam.k = z; core.clampPan();
    const N = 20;
    for (let i = 0; i < N; i++) { core.S.baseVersion++; main.draw(); await new Promise(requestAnimationFrame); }
    for (const L of layers.LAYERS) L.draw = L._orig;
    return Object.entries(acc).map(([id, ms]) => [id, +(ms / N).toFixed(2)])
      .sort((a, b) => b[1] - a[1]).slice(0, 7);
  }, k);
  console.log(`${k}x  ms/frame per layer:`, JSON.stringify(r));
}
await b.close();
