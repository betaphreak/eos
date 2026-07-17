// district-verify — count the LIVE vs ABANDONED neighborhood chips the district view actually
// draws for the spectated colony's province, and check that against the colony's district count.
// A city of N districts must light exactly N of its province's urban plots; the rest of the urban
// core is unbuilt ground and reads as abandoned (docs/urban-plots.md).
//
// It hooks CanvasRenderingContext2D.drawImage before the app boots and tallies the chip draws of
// one frame by image src (the baked `dis-neighborhood` vs `dis-neighborhood-abandoned` variants),
// so it observes the real render path rather than re-deriving the rule.
//
//   node district-verify.mjs [provId=4411] [zoom=220] [--live=<base>]
import { chromium } from 'playwright-core';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const pos = args.filter(a => !a.startsWith('--'));
const flags = Object.fromEntries(args.filter(a => a.startsWith('--')).map(a => a.replace(/^--/, '').split('=')));
const provId = +(pos[0] || 4411);
const zoom = +(pos[1] || 220);
const liveBase = flags.live || process.env.LIVE || 'http://localhost:8080';
const webDir = path.resolve(flags.web || path.join(here, '..', '..', 'web'));

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.png': 'image/png', '.webp': 'image/webp', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0].split('#')[0];
  const file = path.join(webDir, url === '/' ? 'index.html' : decodeURIComponent(url));
  if (!file.startsWith(webDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('nf'); }
  const size = fs.statSync(file).size, type = MIME[path.extname(file)] || 'application/octet-stream', range = req.headers.range;
  if (range) { const m = /bytes=(\d+)-(\d+)?/.exec(range), start = +m[1], end = m[2] ? +m[2] : size - 1;
    res.writeHead(206, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else { res.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': size }); fs.createReadStream(file).pipe(res); }
});
await new Promise(r => server.listen(0, r));
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

// tally neighborhood-chip draws by variant, before any app code runs
await page.addInitScript(() => {
  window.__chips = { live: 0, abandoned: 0 };
  window.__lastChips = null;
  const orig = CanvasRenderingContext2D.prototype.drawImage;
  CanvasRenderingContext2D.prototype.drawImage = function (img, ...rest) {
    const src = (img && img.src) || '';
    if (/dis-neighborhood/.test(src)) {
      // the abandoned chip is either its own baked variant, or the live art drawn through the
      // grayscale "fake ruin" filter — both count as abandoned
      if (/abandoned/.test(src) || /grayscale/.test(this.filter || '')) window.__chips.abandoned++;
      else window.__chips.live++;
    }
    return orig.call(this, img, ...rest);
  };
  // Track the district count off the SAME snapshot stream the renderer reads. The demo colony
  // collapses as it ticks (its district count falls with it), so a snapshot fetched after the fact
  // races the frame — pairing them here is what makes the comparison meaningful.
  window.__districts = null;
  const OrigES = window.EventSource;
  window.EventSource = class extends OrigES {
    constructor(...a) {
      super(...a);
      this.addEventListener('message', e => {
        try {
          const j = JSON.parse(e.data);
          const c = j && j.colonies && j.colonies[0];
          if (c) window.__districts = c.startingDistricts;
        } catch { /* ignore a bad frame, as live.mjs does */ }
      });
    }
  };

  // Fence the tally per frame and keep the last frame that actually drew chips, with the district
  // count that frame was drawn against. The app repaints on demand, so a fixed wait can land on an
  // idle frame; and forcing one with a resize would refit the camera and throw away the deep zoom
  // we came here to measure.
  (function loop() {
    const c = window.__chips;
    if (c.live || c.abandoned) window.__lastChips = { ...c, districts: window.__districts };
    window.__chips = { live: 0, abandoned: 0 };
    requestAnimationFrame(loop);
  })();
});

await page.goto(`${base}/index.html?live=${encodeURIComponent(liveBase)}`, { waitUntil: 'domcontentloaded' });
await page.waitForFunction(() => window.BUNDLE && document.getElementById('rail'), null, { timeout: 30000 }).catch(() => {});
await page.waitForTimeout(6000);
await page.evaluate(() => {
  const c = [...document.querySelectorAll('button')].find(x => /got it/i.test(x.textContent)); if (c) c.click();
});
// stay in LIVE mode (the colony must be spectated), deep-zoom onto its province
await page.evaluate(({ pid, z }) => { location.hash = `#p=${pid}&z=${z}`; window.dispatchEvent(new HashChangeEvent('hashchange')); }, { pid: provId, z: zoom });
await page.waitForTimeout(9000);   // let the per-plot terrain bake + a snapshot land

// read the last frame that drew chips, and the district count it was drawn against
const shot = await page.evaluate(() => ({ chips: window.__lastChips || { live: 0, abandoned: 0, districts: null } }));
await page.screenshot({ path: path.join(here, 'district-abandoned.png') });

// the colony's name (for the report only — the district count comes from the paired frame above)
const sessions = await (await fetch(`${liveBase}/api/sessions`)).json().catch(() => null);
const sid = Array.isArray(sessions) && sessions.length ? (sessions[0].id || sessions[0].sessionId) : null;
let colony = null;
if (sid) {
  const snap = await (await fetch(`${liveBase}/api/sessions/${sid}/snapshot`)).json().catch(() => null);
  colony = snap && snap.colonies && snap.colonies[0];
}
// the colony's own urban plots, and every OTHER loaded province's — neighbouring city sites are on
// screen too and draw (correctly abandoned) chips of their own, so they're part of the expected tally
const { urbanPlots, otherUrban } = await page.evaluate(pid => {
  let mine = null, other = 0;
  for (const p of window.BUNDLE.provinces) {
    if (!p._plots) continue;
    const n = p._plots.filter(q => q.urban).length;
    if (p.id === pid) mine = n; else other += n;
  }
  return { urbanPlots: mine, otherUrban: other };
}, provId);

const { live, abandoned, districts } = shot.chips;
// The invariant under test: the colony lights exactly as many plots as it has districts — the bug
// was every urban plot in its province reading live (74, its whole core). Everything else on screen
// is abandoned: its own unbuilt outskirts, plus any neighbouring city site in view — so the
// abandoned tally is only checked for the floor its own outskirts put under it (attributing each
// chip to a province would mean re-deriving the projection here).
const outskirts = districts != null && urbanPlots != null ? Math.max(0, urbanPlots - districts) : null;
const checks = {
  litExactlyItsDistricts: districts != null && live === Math.min(districts, urbanPlots ?? districts),
  outskirtsAbandoned: outskirts != null && abandoned >= outskirts,
  drewTheWholeCore: urbanPlots != null && live + abandoned >= urbanPlots,
  noErrors: errors.length === 0,
};
const pass = Object.values(checks).every(Boolean);
console.log(JSON.stringify({
  province: provId, colony: colony && colony.name, districts, urbanPlots, otherUrbanLoaded: otherUrban,
  drawn: { live, abandoned, total: live + abandoned },
  checks, pass, errors,
}, null, 2));
if (!pass) process.exitCode = 1;
await browser.close();
server.close();
