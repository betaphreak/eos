// Verify the notification board (web/js/notify.mjs, docs/notifications.md) in a real browser.
//
// Two passes, in this order for a reason:
//   A) integration — Live mode auto-starts on load, so wind the session up and confirm cards
//      actually arrive from the real SSE stream, dated with the real in-game clock.
//   B) deterministic — then stopLive() to FREEZE the feed (otherwise the next snapshot re-ages the
//      board against the real clock, and a re-founded session id calls resetNotify and wipes it),
//      and feed the real module synthetic lines at known in-game ages. That exercises the whole
//      30-day ramp — fresh → red → expiry — in one shot instead of waiting out 30 in-game days.
//
// Usage: node notify-verify.mjs <siteBase> <liveServer> <outPng>
//   e.g. node notify-verify.mjs http://localhost:3000 http://localhost:8080 notify.png
import { chromium } from 'playwright-core';
const [, , site, live, out = 'notify.png'] = process.argv;
const url = `${site.replace(/\/$/, '')}/?live=${encodeURIComponent(live)}`;
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
const fails = [];
const check = (ok, what) => { console.log(`${ok ? '  ok  ' : '  FAIL'} ${what}`); if (!ok) fails.push(what); };

// Wait for Live mode to actually be connected — never a fixed sleep. Against a remote site the boot
// chain (loading splash → bundle prefetch → app.js → enter Live → /api/sessions → SSE) can take tens
// of seconds, and live starting LATE is not a slow no-op: connectStream calls resetNotify on session
// discovery, so a late start wipes whatever the test had put on the board and every assertion below
// reads someone else's state.
//
// Gate on the DOM, NOT on `import('/js/overlays/live.mjs').liveActive()`. That import looks harmless
// and is a trap: live.mjs → core.mjs reads window.BUNDLE at MODULE-EVAL, so importing it before the
// index.html bootstrap has fetched the bundle throws — and an ES module's evaluation failure is
// CACHED, so the app's own later import of live.mjs gets the same rejection and Live mode never
// starts at all. The probe broke the thing it was measuring. The board's host un-hiding is the same
// signal (hud(true) → showNotify(true)) and costs nothing.
const waitForLive = async () => page.waitForFunction(
  () => { const h = document.getElementById('notifyHost'); return !!h && !h.hidden; },
  null, { timeout: 120000 });

await page.goto(url, { waitUntil: 'load' });
await waitForLive().catch(() => console.log('  (timed out waiting for Live mode to connect)'));

// ---- A) integration: the board is recovered from the server's tail --------------------------
// RUN THIS SOON AFTER A SERVER RESTART. The demo (seed 7654321) logs just four lines in its whole
// first year — the founding, the 1445-01-01 digest and two starvation demotions on 1445-02-10/-11
// (ticks 0, 21, 61, 62) — so at ~3s/tick there is nothing inside the board's 30-day window once the
// session passes ~tick 92. That is also the point: a snapshot's log is a DELTA, so joining even a
// minute late means the stream alone can never show you those lines. The board recovers them from
// GET /api/sessions/{id}/events instead, which is what this asserts.
const readBoard = () => page.evaluate(() => {
  const host = document.getElementById('notifyHost');
  return {
    hidden: host.hidden,
    cards: [...host.querySelectorAll('.notif')].map(c => ({
      date: c.querySelector('.notif-date').textContent,
      routine: c.classList.contains('routine'),
      bg: getComputedStyle(c).backgroundColor,
      opacity: +(+getComputedStyle(c).opacity).toFixed(2),
      text: c.querySelector('.notif-text').textContent,
    })),
  };
});
// Ground truth, straight from the server: the same date window the board asks for, then the same RANK
// window the board applies. The board is not "the tail" — it is the tail seen from a rank: a viewer
// sees one rung either way, so a VILLAGE founding never reaches a CARAVAN band. Comparing against the
// raw tail would assert an invariant that predates ranked events.
const tailWindow = () => page.evaluate(async liveBase => {
  const { minusDays, LIFETIME_DAYS } = await import('/js/notify-age.mjs');
  const { visibleTo, VIEWER_RANK_DEFAULT } = await import('/js/notify-rank.mjs');
  const list = await (await fetch(liveBase + '/api/sessions')).json();
  const id = list[0].id;
  const snap = await (await fetch(`${liveBase}/api/sessions/${id}/snapshot`)).json();
  const from = minusDays(snap.date, LIFETIME_DAYS);
  const all = await (await fetch(`${liveBase}/api/sessions/${id}/events?from=${from}&limit=60`)).json();
  const lines = all.filter(l => visibleTo(l.rankLevel, VIEWER_RANK_DEFAULT));
  return { id, now: snap.date, from, lines, all, viewer: VIEWER_RANK_DEFAULT };
}, live);

console.log('\n--- A) recovered from the retained tail ---');
const truth = await tailWindow();
console.log(`session ${truth.id} · in-game ${truth.now} · window from ${truth.from} · ` +
  `${truth.all.length} line(s) in the tail, ${truth.lines.length} visible to a viewer at rank ${truth.viewer}`);
console.log(JSON.stringify(truth.all.map(l => `${l.date}  [${l.rank}/${l.rankLevel}]  ${l.text.slice(0, 46)}`), null, 1));
const hidden = truth.all.length - truth.lines.length;
if (hidden) console.log(`  (${hidden} line(s) are out of the viewer's rank window — a band is not told about a colony's affairs)`);
if (!truth.lines.length)
  console.log('  NOTE: nothing in the window is visible at this rank — the demo may have run past its\n' +
    '        last log line. Restart the server and re-run within ~90s to exercise this pass.');
check(truth.lines.length > 0, 'the server has lines this viewer can see (restart the server if this fails)');
check(truth.all.every(l => Number.isInteger(l.rankLevel)), 'every tail line carries a rank level');

// Rehydration is the tail of a long async chain — bundle fetch → boot → enter Live → /api/sessions →
// /snapshot → /events → seed — so wait for the board to settle rather than guessing at a sleep.
await page.waitForFunction(n => {
  const host = document.getElementById('notifyHost');
  return !host.hidden && host.querySelectorAll('.notif').length >= n;
}, truth.lines.length, { timeout: 30000 }).catch(() => console.log('  (timed out waiting for the board to fill)'));

let liveBoard = await readBoard();
console.log(JSON.stringify(liveBoard.cards.map(c => c.date + '  ' + c.text.slice(0, 54)), null, 1));
check(!liveBoard.hidden, 'board is visible in Live mode');
// THE claim: we connected long after these lines were logged and drained, and they are on the board.
// >= not ===: the session keeps running, so the stream may have added a line since `truth` was read.
check(liveBoard.cards.length >= truth.lines.length,
  `every recovered line became a card (${truth.lines.length} in the tail → ${liveBoard.cards.length} cards)`);
check(truth.lines.every((l, i) => liveBoard.cards[i] && liveBoard.cards[i].text === l.text
  && liveBoard.cards[i].date === l.date), 'each card matches the tail line it came from, in order');
check(liveBoard.cards.every(c => /^\d{4}-\d{2}-\d{2}$/.test(c.date)), 'every card carries an in-game date');
// curated survives the round trip: a founding must be a full card here, exactly as it is live
const foundCard = liveBoard.cards.find(c => c.text.includes('founded'));
if (foundCard) check(!foundCard.routine, 'a founding recovered from the tail is a full card, not routine churn');
// no duplicates: a line in the tail AND still undrained in the first delta must appear once
const seenKeys = liveBoard.cards.map(c => c.date + c.text);
check(new Set(seenKeys).size === seenKeys.length, 'no card is duplicated between the tail and the stream');
await page.screenshot({ path: out.replace(/\.png$/, '-live.png') });   // the recovered board, before the reload

// reload: the board must come back, which is the whole reason the endpoint exists
await page.reload({ waitUntil: 'load' });
await waitForLive().catch(() => console.log('  (timed out waiting for Live mode after the reload)'));
await page.waitForFunction(() => document.querySelectorAll('#notifyHost .notif').length > 0,
  null, { timeout: 30000 }).catch(() => console.log('  (timed out waiting for the reloaded board)'));
const afterReload = await readBoard();
// Compare against the tail as it is NOW, not the count from before the reload: this runs against a
// live clock, so a card can legitimately expire mid-test (seen against prod — the founding aged past
// 30 in-game days between the two reads, and the board correctly dropped it). The invariant is "the
// reloaded board holds what the server's window holds", not "the board never shrinks".
const truth2 = await tailWindow();
console.log(`after reload: ${afterReload.cards.length} card(s); tail window now holds ${truth2.lines.length}`);
// An empty window is a legitimate outcome, not a failure: the demo logs sparsely, so the 30-day
// window can slide past its last line mid-test (seen against prod — 2 lines at the first read, 0 by
// the reload). Demanding cards the server no longer has would be asserting a bug into existence.
if (!truth2.lines.length)
  console.log('  NOTE: the window emptied mid-test (the sim outran its last log line) — nothing left to recover.');
else
  check(afterReload.cards.length > 0, `the board survives a page reload (${afterReload.cards.length} card(s) recovered)`);
check(truth2.lines.every(l => afterReload.cards.some(c => c.date === l.date && c.text === l.text)),
  'every line still inside the 30-day window is back on the reloaded board');
const reloadKeys = afterReload.cards.map(c => c.date + c.text);
check(new Set(reloadKeys).size === reloadKeys.length, 'and the reloaded board has no duplicates');
// the stream delivers a tick's lines in date order, so the pile is chronological in practice too
const dates = afterReload.cards.map(c => c.date);
check(dates.every((d, i) => i === 0 || d >= dates[i - 1]), `cards pile in date order: ${dates.join(' → ')}`);

// ---- B) deterministic: the ramp, the order, expiry, dismiss ----------------------------------
// Freeze the feed first, and CONFIRM it froze: a live snapshot would re-age these synthetic cards
// against the real clock (expiring them all — the real date is months past these), and a session
// discovery would resetNotify the board out from under the assertions.
await page.evaluate(async () => { (await import('/js/overlays/live.mjs')).stopLive(); });
const frozen = await page.waitForFunction(
  async () => !(await import('/js/overlays/live.mjs')).liveActive(), null, { timeout: 15000 })
  .then(() => true).catch(() => false);
check(frozen, 'the live feed stops on demand, so the deterministic pass below is isolated');

const NOW = '1445-01-10';   // each line's date below lands on a known age against this
await page.evaluate(async now => {
  const m = await import('/js/notify.mjs');
  m.resetNotify();
  m.showNotify(true);       // stopLive hid the board (hud(false)); this is the deterministic rig
  // delivered oldest-first, as a real tick's delta is
  m.ingestNotify([
    { date: '1444-12-11', text: 'EXPIRED: exactly 30 days old', curated: true, sev: 'info' },
    { date: '1444-12-17', text: 'Dhenijansar starved down from METROPOLIS to TOWN', curated: true, sev: 'info' },
    { date: '1444-12-30', text: 'annual digest: pop=402 children=0 nobles=3 firms=20 pool=483 poolKids=323 POI deaths=0 CPI=0.1', curated: false, sev: 'info' },
    { date: '1445-01-05', text: 'Necessity skyrocketed to 51.51 (>10x init)', curated: true, sev: 'warn' },
    { date: '1445-01-08', text: 'Dhenijansar was founded on 1445-01-08.', curated: true, sev: 'info' },
    { date: '----------', text: 'UNDATED: SimLog placeholder, stamped with the session clock', curated: true, sev: 'info' },
  ], now);
}, NOW);
// Wait for the expired card to finish its 240ms fade and leave the DOM — as a CONDITION, not a
// sleep. The map rasters terrain at ~7fps under load (1166ms frames), which starves both setTimeout
// and the CSS transitions the assertions below read, so any fixed wait here is a coin toss.
await page.waitForFunction(() => {
  const host = document.getElementById('notifyHost');
  return !host.querySelector('.notif.out') && host.querySelectorAll('.notif').length === 5;
}, null, { timeout: 20000 }).catch(() => console.log('  (timed out waiting for the expired card to leave)'));
// ...and for the entry transitions to fully settle. Both halves matter: opacity, so the dimming
// reads below are resting values; and transform, because a card still sliding up from translateY(8px)
// has a rect that is 8px stale — exactly the width of the gap between cards, so the right-click
// below would land in the gap and hit nothing.
await page.waitForFunction(() => [...document.querySelectorAll('#notifyHost .notif')]
  .every(c => { const s = getComputedStyle(c); return +s.opacity > 0 && s.transform === 'none'; }),
  null, { timeout: 20000 }).catch(() => console.log('  (timed out waiting for entry transitions)'));

const board = await page.evaluate(() => {
  const host = document.getElementById('notifyHost');
  const hb = host.getBoundingClientRect();
  return {
    hidden: host.hidden,
    host: { right: Math.round(hb.right), bottom: Math.round(hb.bottom) },
    cards: [...host.querySelectorAll('.notif')].map(c => {
      const r = c.getBoundingClientRect(), cs = getComputedStyle(c);
      return {
        date: c.querySelector('.notif-date').textContent,
        text: c.querySelector('.notif-text').textContent.slice(0, 40),
        bg: cs.backgroundColor, borderLeft: cs.borderLeftColor, opacity: +(+cs.opacity).toFixed(2),
        routine: c.classList.contains('routine'), top: Math.round(r.top),
      };
    }),
  };
});
const red = c => +c.bg.match(/\d+/g)[0];
const last = board.cards[board.cards.length - 1];

console.log('\n--- B) deterministic (now = ' + NOW + ') ---');
console.log(JSON.stringify(board.cards.map(c => ({ d: c.date, bg: c.bg, routine: c.routine, t: c.text })), null, 1));

check(!board.hidden, 'board is visible');
check(board.cards.length === 5, `the 30-day-old card expired: 5 of 6 shown (got ${board.cards.length})`);
check(!board.cards.some(c => c.text.startsWith('EXPIRED')), 'the expired card is gone from the DOM');
check(board.cards.some(c => c.text.startsWith('UNDATED')), 'an undated line is kept (stamped with the session clock), not dropped');

// FCFS: DOM/arrival order, laid out oldest at the TOP and newest at the BOTTOM
const tops = board.cards.map(c => c.top);
check(tops.every((t, i) => i === 0 || t > tops[i - 1]), 'cards stack top→bottom in arrival order (newest enters at the bottom)');
check(board.cards[0].date === '1444-12-17', `oldest card is topmost (got ${board.cards[0].date})`);
check(last.text.startsWith('UNDATED'), `last-arrived card is bottom-most (got "${last.text}")`);

// the age ramp
const reds = board.cards.map(red);
check(reds.every((r, i) => i === 0 || r <= reds[i - 1]), `red decreases downward — oldest is reddest: ${reds.join(' > ')}`);
check(red(board.cards[0]) - red(last) > 60, `the ramp is visibly wide across the board (${red(board.cards[0])} → ${red(last)})`);
check(last.bg === 'rgb(20, 26, 38)', `the day-0 (undated→stamped) card sits at the fresh end (got ${last.bg})`);
check(red(board.cards[0]) > 85, `a 24-day-old card is well into the red (got ${red(board.cards[0])})`);

// routine vs curated
const digest = board.cards.find(c => c.routine);
check(!!digest && digest.date === '1444-12-30', 'the digest line rendered as a routine card');
check(!!digest && digest.opacity < 0.9, `routine cards are dimmed (opacity ${digest && digest.opacity})`);
check(board.cards.filter(c => !c.routine).length === 4, 'the curated lines rendered as full cards');
// severity is its own channel — the left border, NOT the background (which age owns)
const warn = board.cards.find(c => c.date === '1445-01-05');
check(!!warn && warn.borderLeft === 'rgb(232, 195, 122)', `a warn line is flagged on the border, not the background (got ${warn && warn.borderLeft})`);

// placement: bottom-right, clear of the centred log strip
const strip = await page.evaluate(() => {
  const s = document.getElementById('liveLog'), r = s.getBoundingClientRect();
  return { top: Math.round(r.top), left: Math.round(r.left), hidden: s.hidden };
});
check(board.host.right > 1200 && board.host.right < 1400, `board is right-anchored (right=${board.host.right})`);
check(strip.hidden || board.host.bottom <= strip.top + 1 || board.host.right < strip.left,
  `board clears the log strip (board bottom ${board.host.bottom} vs strip top ${strip.top})`);

await page.screenshot({ path: out });

// right-click dismiss
const target = await page.evaluate(() => {
  const c = document.querySelectorAll('#notifyHost .notif');
  const el = c[c.length - 1], r = el.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), n: c.length };
});
await page.mouse.click(target.x, target.y, { button: 'right' });
await page.waitForFunction(n => document.querySelectorAll('#notifyHost .notif').length === n - 1,
  target.n, { timeout: 20000 }).catch(() => {});
const afterDismiss = await page.evaluate(() => document.querySelectorAll('#notifyHost .notif').length);
check(afterDismiss === target.n - 1, `right-click dismissed one card (${target.n} → ${afterDismiss})`);

// ...and right-clicking the MAP must not be swallowed by the board
const mapPrevented = await page.evaluate(() => {
  let prevented = false;
  const probe = e => { prevented = e.defaultPrevented; };
  document.addEventListener('contextmenu', probe);
  document.getElementById('map').dispatchEvent(
    new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 400, clientY: 400 }));
  document.removeEventListener('contextmenu', probe);
  return prevented;
});
check(!mapPrevented, 'right-click on the map keeps the browser menu (the board claims only its cards)');

console.log('\nconsole errors:', errors.length ? errors : 'none');
console.log(fails.length ? `\nFAILED (${fails.length}):\n - ${fails.join('\n - ')}` : '\nALL CHECKS PASSED');
await browser.close();
process.exit(fails.length ? 1 : 0);
