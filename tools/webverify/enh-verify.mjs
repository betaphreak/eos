// Verifies this round of top-bar work end to end against a live server:
//   (A) the Technology segment fills like a progress bar and clicking it opens the tech tree
//       centred + selected on the tech being researched
//   (B) the Halann segment leads the bar
//   (D) the fps · latency chip renders beside the account handle
//   (E) the boot prefetch pulls the manifest's eager set and reports bytes on the status line
// Usage: node enh-verify.mjs <pageBase> <liveBase> <outDir>
import { chromium } from 'playwright-core';

const [, , pageBase, liveBase, outDir] = process.argv;
const url = `${pageBase.replace(/\/$/, '')}/?live=${liveBase}`;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, deviceScaleFactor: 2 });
const errors = [], statuses = [], prefetched = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(String(e)));
// record what the prefetch actually pulled, to prove (E) rather than trust the status text
page.on('request', r => { const u = r.url(); if (/\/api\/(techs|buildings|tiers|resources)/.test(u)) prefetched.push(u.replace(liveBase, '')); });
// capture the status line as it changes (the prefetch progress lives there)
await page.exposeFunction('__note', s => statuses.push(s));

await page.goto(url, { waitUntil: 'domcontentloaded' }).catch(() => {});
await page.evaluate(() => {
  const el = document.getElementById('statusline');
  new MutationObserver(() => window.__note(el.textContent)).observe(el, { childList: true, characterData: true, subtree: true });
});
await page.waitForTimeout(14000);
await page.click('text=Got it').catch(() => {});
await page.waitForTimeout(600);

const bar = await page.evaluate(() => {
  const segs = [...document.querySelectorAll('#advisorToggle button')];
  const tech = document.querySelector('#advisorToggle button[data-advisor="technology"]');
  const chip = document.getElementById('diagChip');
  return {
    order: segs.map(b => b.textContent.trim()),
    techText: tech && tech.textContent.trim(),
    techHasFill: !!tech && tech.classList.contains('adv-research'),
    techResearchVar: tech && tech.style.getPropertyValue('--research'),
    techBgImage: tech && getComputedStyle(tech).backgroundImage.slice(0, 60),
    diag: chip && chip.textContent.trim(),
  };
});
await page.screenshot({ path: `${outDir}/topbar.png`, clip: { x: 0, y: 0, width: 1500, height: 90 } });

// (A) click the research pill → tree opens focused on the researching tech
await page.click('#advisorToggle button[data-advisor="technology"]').catch(() => {});
await page.waitForTimeout(3500);
const tree = await page.evaluate(() => {
  const sel = document.querySelector('.tech-node.sel');
  const res = document.querySelector('.tech-node.researching');
  const vp = document.getElementById('techViewport');
  const inView = el => {
    if (!el || !vp) return null;
    const a = el.getBoundingClientRect(), b = vp.getBoundingClientRect();
    return a.left >= b.left - 4 && a.right <= b.right + 4 && a.top >= b.top - 4 && a.bottom <= b.bottom + 4;
  };
  return {
    selected: sel && sel.textContent.trim().slice(0, 40),
    researching: res && res.textContent.trim().slice(0, 40),
    researchVar: res && res.style.getPropertyValue('--research'),
    researchingCentred: inView(res),
    lockedCount: document.querySelectorAll('.tech-node.locked').length,
    totalNodes: document.querySelectorAll('.tech-node').length,
  };
});
await page.screenshot({ path: `${outDir}/techtree.png` });

console.log(JSON.stringify({ bar, tree, prefetched: [...new Set(prefetched)], statuses, errors: errors.slice(0, 6) }, null, 2));
await browser.close();
