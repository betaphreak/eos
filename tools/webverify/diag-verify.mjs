// Checks the top bar's fps readout tells the truth: the rate must derive from real frame COST (not
// paints per second, and not the display refresh), and its colour grade must track the rate it shows.
// Compares a cheap world view against an expensive plot zoom.
// Usage: node diag-verify.mjs <pageBase> <liveBase>
import { chromium } from 'playwright-core';

const [, , pageBase, liveBase] = process.argv;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });

async function read(url, label) {
  await page.goto(url, { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.waitForTimeout(12000);
  await page.click('text=Got it').catch(() => {});
  // nudge the camera so frames are actually being drawn when we read
  await page.mouse.move(700, 450);
  await page.mouse.wheel(0, -120);
  await page.waitForTimeout(1500);
  const r = await page.evaluate(() => {
    const f = document.getElementById('diagFps'), n = document.getElementById('diagNet');
    return { fps: f.textContent, grade: f.dataset.grade, title: f.title, stale: f.classList.contains('stale'), net: n.textContent, netGrade: n.dataset.grade };
  });
  return { label, ...r };
}

const world = await read(`${pageBase}/?live=${liveBase}&z=2`, 'world (cheap)');
const deep = await read(`${pageBase}/?live=${liveBase}&p=4411&z=90`, 'plot zoom (expensive)');
// the grade must agree with the number: fast → ok, slow → warn/bad. A green "8fps" is the bug.
const consistent = r => {
  const v = parseInt(r.fps, 10);
  if (!Number.isFinite(v)) return 'no reading';
  if (v >= 30 && r.grade === 'ok') return 'consistent';
  if (v < 15 && (r.grade === 'warn' || r.grade === 'bad')) return 'consistent';
  if (v >= 15 && v < 30) return 'consistent (mid)';
  return `INCONSISTENT: ${r.fps} graded ${r.grade}`;
};
console.log(JSON.stringify({
  world: { ...world, verdict: consistent(world) },
  deep: { ...deep, verdict: consistent(deep) },
}, null, 2));
await browser.close();
