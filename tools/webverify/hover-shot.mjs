// Hover plots at deep zoom and capture the #tip tooltip (name · terrain · feature) + a screenshot.
// Usage: node hover-shot.mjs <baseUrl> <p> <z> <live> <outPng> [waitMs]
//   e.g. node hover-shot.mjs http://localhost:3000 2119 220 http://localhost:8080 out.png
import { chromium } from 'playwright-core';

const [, , base, pid, z, live, out, waitMs = '2600'] = process.argv;
// ?live=<server> so /api/* hit the LOCAL server (else SERVER_BASE falls back to prod); #none = physical overlay
const url = `${base.replace(/\/$/, '')}/?p=${pid}&z=${z}&live=${encodeURIComponent(live)}#none`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto(url, { waitUntil: 'networkidle' });
await page.waitForTimeout(+waitMs);

const readTip = () => page.evaluate(() => {
  const t = document.getElementById('tip');
  return (t && t.classList.contains('on')) ? t.innerHTML : '';
});
const strip = h => h.replace(/<br\s*\/?>/gi, '  |  ').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();

// sweep a grid of cursor points across the focused province; keep the richest tooltip (most lines)
const W = 1400, H = 900;
const seen = new Map();       // stripped text -> {html, x, y, lines}
let best = null;
for (let gy = -4; gy <= 4; gy++) {
  for (let gx = -6; gx <= 6; gx++) {
    const x = Math.round(W / 2 + gx * 55), y = Math.round(H / 2 + gy * 55);
    await page.mouse.move(x, y);
    await page.waitForTimeout(35);
    const html = await readTip();
    if (!html) continue;
    const text = strip(html), lines = (html.match(/<br/gi) || []).length;
    if (!seen.has(text)) seen.set(text, { html, x, y, lines });
    if (!best || lines > best.lines) best = { html, x, y, lines, text };
  }
}

if (best) { await page.mouse.move(best.x, best.y); await page.waitForTimeout(150); }
await page.screenshot({ path: out });

console.log('hover-shot:', out, '| url:', url);
console.log('distinct tooltips seen:', seen.size);
for (const [text] of seen) console.log('  •', text);
console.log('RICHEST:', best ? best.text : '(no tooltip found — likely not at plot/texture zoom)');
console.log('errors:', errors.length ? errors : 'none');
await browser.close();
