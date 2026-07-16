// Verifies the live-HUD retirement (item C): the colony vitals render as a top-bar strip, the
// province rail shows the live colony inline for the colony's OWN province and not for others, and
// the retired #liveHud is gone without taking any mode's rail with it.
// Usage: node liverail-verify.mjs <pageBase> <liveBase> <outDir> <colonyProvId> <otherProvId>
import { chromium } from 'playwright-core';

const [, , pageBase, liveBase, outDir, colonyProv = '4411', otherProv = '4430'] = process.argv;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(String(e)));

async function open(prov) {
  await page.goto(`${pageBase}/?live=${liveBase}&p=${prov}&z=60`, { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.waitForTimeout(12000);
  await page.click('text=Got it').catch(() => {});
  await page.waitForTimeout(500);
  // ?p= only FOCUSES the camera on a province; selecting it (which is what fills the rail) is a
  // click. The deep link centres it, so the viewport centre is over it.
  await page.mouse.click(750, 500);
  await page.waitForTimeout(1200);
  return page.evaluate(() => ({
    hudGone: !document.getElementById('liveHud'),
    vitals: (document.getElementById('liveVitals') || {}).textContent || '',
    railHasColony: !!document.querySelector('.live-dot'),
    railHead: (document.querySelector('#rail .d-head h2') || {}).textContent || '',
    railVisible: !!document.querySelector('#rail') && getComputedStyle(document.getElementById('rail')).display !== 'none',
    colonyFigures: [...document.querySelectorAll('#rail .metacell .k')].map(e => e.textContent),
  }));
}

const onColony = await open(colonyProv);
await page.screenshot({ path: `${outDir}/rail-colony.png` });
const onOther = await open(otherProv);

console.log(JSON.stringify({ onColony, onOther, errors: errors.slice(0, 6) }, null, 2));
await browser.close();
