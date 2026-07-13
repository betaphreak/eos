// Screenshot the technology-tree modal. Usage:
//   node tech-shot.mjs <baseUrl> <outPng> [era] [waitMs]
import { chromium } from 'playwright-core';
const [, , base = 'http://localhost:8099/', out = 'tech.png', era = '', waitMs = '2000'] = process.argv;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 950 }, deviceScaleFactor: 1.5 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(base.replace(/\/$/, '') + '/', { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
// the tech tree is the Technology advisor's map-mode now (the standalone #techBtn was retired)
await page.click('#advisorToggle button[data-advisor="technology"]');
await page.waitForTimeout(+waitMs);
if (era) {
  // jump to the era and screenshot the scroll result — do NOT click a node (Playwright would
  // auto-scroll an off-screen node back into view and undo the jump)
  await page.click(`#techEras button[data-era="C2C_ERA_${era}"]`).catch(() => {});
  await page.waitForTimeout(1200);
} else {
  // open a node's detail + trigger the ancestry highlight on a mid-tree tech
  const node = page.locator('.tech-node').nth(40);
  await node.hover().catch(() => {});
  await page.waitForTimeout(400);
  await node.click().catch(() => {});
  await page.waitForTimeout(600);
}
await page.screenshot({ path: out });
console.log('shot:', out, '| errors:', errors.length ? errors : 'none');
await browser.close();
