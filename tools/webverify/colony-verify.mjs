// Verify the top-bar vitals → colony composition rail against a running stack.
//   node colony-verify.mjs [webBase] [liveBase]
import { chromium } from 'playwright-core';

const WEB = process.argv[2] || 'http://localhost:3000';
const LIVE = process.argv[3] || 'http://localhost:8080';

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 900 }, deviceScaleFactor: 2 });
const errs = [];
page.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text()); });

await page.goto(`${WEB}/?live=${encodeURIComponent(LIVE)}#none`, { waitUntil: 'load' });
await page.waitForTimeout(1200);
await page.keyboard.press('Escape');           // dismiss the spectator lobby
await page.waitForTimeout(300);
// select the Zeitgeist advisor (class advisor-live) — this calls setOverlay("live") → startLive,
// connecting the feed and revealing the vitals strip
await page.evaluate(() => document.querySelector('#advisorToggle button.advisor-live')?.click());

// wait for the vitals figures to populate (first snapshot → renderHud)
await page.waitForFunction(() => document.querySelectorAll('#liveVitals .lv:not(.lv-status)').length > 0,
  null, { timeout: 45000 });
const vitalsCount = await page.evaluate(() => document.querySelectorAll('#liveVitals .lv:not(.lv-status)').length);

// click the first vitals figure (population)
await page.evaluate(() => document.querySelector('#liveVitals .lv:not(.lv-status)').click());
await page.waitForSelector('#rail .colony-sheet', { timeout: 10000 });
// wait for the roster to land (skills + household rows), up to ~8s
await page.waitForFunction(() => document.querySelectorAll('#rail .adv-house .adv-member').length > 0,
  null, { timeout: 8000 }).catch(() => {});

// direct fetch probe to rule out CORS/endpoint issues from the page origin
const probe = await page.evaluate(async () => {
  const core = await import('./js/core.mjs');
  const live = await import('./js/overlays/live.mjs');
  const sid = live.liveSid();
  try {
    const r = await fetch(core.apiUrl(`/api/sessions/${sid}/colony`), { cache: 'no-store' });
    const j = await r.json();
    return { ok: r.ok, status: r.status, members: j.members?.length, skills: j.skills?.length, ruler: j.rulerName };
  } catch (e) { return { error: String(e) }; }
});
console.log('fetch probe:', JSON.stringify(probe));

const sheet = await page.evaluate(() => {
  const r = document.querySelector('#rail .colony-sheet');
  if (!r) return null;
  return {
    name: r.querySelector('.adv-name')?.textContent ?? null,
    role: r.querySelector('.adv-role')?.textContent ?? null,
    sub: r.querySelector('.adv-sub')?.textContent ?? null,
    stats: [...r.querySelectorAll('.statrow .stat')].map(s =>
      `${s.querySelector('.k')?.textContent}=${s.querySelector('.v')?.textContent}`),
    skillBars: r.querySelectorAll('.adv-skills .adv-skill').length,
    rosterRows: r.querySelectorAll('.adv-house .adv-member').length,
    firstMember: (() => { const m = r.querySelector('.adv-house .adv-member');
      return m ? [...m.children].map(c => c.textContent.trim()) : null; })(),
    rulerBadged: !!r.querySelector('.adv-house .adv-member .cv-lead'),
  };
});

await page.screenshot({ path: 'colony-panel.png' });

// re-click the same figure → toggles the panel closed
await page.evaluate(() => document.querySelector('#liveVitals .lv:not(.lv-status)').click());
await page.waitForTimeout(600);
const afterToggle = await page.evaluate(() => document.getElementById('railwrap')?.classList.contains('open'));

console.log('vitals figures:', vitalsCount);
console.log('colony sheet:', JSON.stringify(sheet, null, 2));
console.log('rail open after re-click (should be false):', afterToggle);
console.log('errors:', errs.length ? errs : 'none');
await browser.close();
