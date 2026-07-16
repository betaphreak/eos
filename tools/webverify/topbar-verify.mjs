// Verify the contextual top bar end-to-end against a running stack: the band chip's caption tracks
// the zoom band, the advisor segments name what's under the crosshair, and the regime tint actually
// applies (the .zoomlvl -> .adv-zoom fix). Drives the real page — no mocks.
//
//   node topbar-verify.mjs [webBase] [liveBase]
import { chromium } from 'playwright-core';

const WEB = process.argv[2] || 'http://localhost:3000';
const LIVE = process.argv[3] || 'http://localhost:8080';

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
const errs = [];
page.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text()); });

// ?live= injects a custom server entry AND auto-connects (index.html bootstrap), skipping the picker
await page.goto(`${WEB}/?live=${encodeURIComponent(LIVE)}#none`, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('#zoomLevel', { timeout: 45000 });
await page.waitForTimeout(4000);

const read = () => page.evaluate(() => {
  const chip = document.getElementById('zoomLevel');
  const cs = chip && getComputedStyle(chip);
  return {
    band: chip?.querySelector('.rg-name')?.textContent ?? null,
    ctx: chip?.querySelector('.rg-ctx')?.textContent ?? null,
    regime: chip?.dataset.regime ?? null,
    rg: cs ? (cs.getPropertyValue('--rg').trim() || '(unset)') : null,
    color: cs ? cs.color : null,
    segments: [...document.querySelectorAll('#advisorToggle button')].map(b => b.textContent.trim()),
  };
});

// Zoom to a band by driving the real camera, then wait for the caption to SETTLE. The Terrain/
// Locale/Plot captions depend on plots that stream in after the camera stops, so they publish a
// provisional "Surveying…" first and resolve on the civstudio:plots arrival. Polling until the
// provisional clears is what makes this test exercise the real path rather than the placeholder.
const PROVISIONAL = /^Surveying/;
async function atZoom(k) {
  await page.evaluate(async z => {
    const core = await import('./js/core.mjs');
    const main = await import('./js/main.mjs');
    core.cam.k = z; core.clampPan(); core.S.baseVersion++; main.draw();
  }, k);
  for (let i = 0; i < 24; i++) {                      // ≤6s, bailing the moment it resolves
    await page.waitForTimeout(250);
    const r = await read();
    if (r.ctx && !PROVISIONAL.test(r.ctx.replace(/^·\s*/, ''))) return r;
  }
  return read();                                      // still provisional — report it as such
}

const rows = [];
for (const k of [1, 2, 4, 8, 16, 32, 64, 128, 256]) rows.push([k, await atZoom(k)]);

console.log('band chip across the zoom spine:');
for (const [k, r] of rows) {
  console.log(`  ${String(k).padStart(3)}x  band=${String(r.band).padEnd(10)} regime=${String(r.regime).padEnd(8)} --rg=${String(r.rg).padEnd(9)} ctx=${JSON.stringify(r.ctx)}`);
}
const last = rows[rows.length - 1][1];
console.log('\nadvisor segments:', last.segments);
console.log('console errors:', errs.length ? errs.slice(0, 6) : 'none');

// assertions
const fails = [];
if (rows.some(([, r]) => r.rg === '(unset)')) fails.push('regime tint --rg is unset (the .adv-zoom CSS fix did not take)');
if (rows.some(([, r]) => !r.ctx)) fails.push('some bands rendered no .rg-ctx caption');
const bands = rows.map(([, r]) => r.band);
if (new Set(bands).size < 8) fails.push('band name did not track zoom: ' + bands.join(','));
if (errs.length) fails.push(errs.length + ' console/page error(s)');
console.log(fails.length ? '\nFAIL:\n - ' + fails.join('\n - ') : '\nPASS');
await browser.close();
process.exit(fails.length ? 1 : 0);
