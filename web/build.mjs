// Build the self-contained caravan-migration dashboard from a recorded run.
//
//   node web/build.mjs [seed]        (default seed: 24601, ParallelCaravansTest)
//
// Reads output/<seed>/by-caravan/*-CaravanMarch.csv and the committed province
// map (src/main/resources/map/provinces.json), distils them into one JSON data
// bundle, injects it into dashboard.template.html, and writes dashboard.html.
// The output is fully inline (no external requests) so it can be opened directly
// or published as an Artifact. The output/ run must exist first — generate it by
// running the caravan scenario (e.g. ParallelCaravansTest) for that seed.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');
const SEED = process.argv[2] || '24601';
const DIR = path.join(ROOT, 'output', SEED, 'by-caravan');

if (!fs.existsSync(DIR)) {
  console.error(`No caravan journals at ${path.relative(ROOT, DIR)} — run the scenario for seed ${SEED} first.`);
  process.exit(1);
}

const allProv = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/map/provinces.json'), 'utf8'));
const byId = new Map(allProv.map(p => [p.id, p]));
const nameOf = p => (p ? p.name : '?');
const idOf = s => { const m = s.match(/\((\d+)\)\s*$/); return m ? +m[1] : null; };

const files = fs.readdirSync(DIR).filter(f => /-CaravanMarch\.csv$/.test(f)).sort();
const journeys = [];
const visited = new Set();

for (const file of files) {
  const dest = file.replace(/^[^-]+-/, '').replace(/-CaravanMarch\.csv$/, '');
  const rows = fs.readFileSync(path.join(DIR, file), 'utf8').split(/\r?\n/).filter(Boolean);
  const col = Object.fromEntries(rows[0].split(',').map((h, i) => [h, i]));
  const data = rows.slice(1).map(r => r.split(','));

  const keys = [];
  let lastProv = null;
  data.forEach((f, i) => {
    const pid = idOf(f[col.Province]);
    visited.add(pid);
    if (pid !== lastProv || i === 0 || i === data.length - 1) {
      const p = byId.get(pid);
      keys.push({
        date: f[col.Date], pid, prov: nameOf(p),
        lat: p ? +p.lat.toFixed(3) : null, lon: p ? +p.lon.toFixed(3) : null,
        band: +f[col.BandSize] || 0, larder: Math.round(+f[col.Larder] || 0),
        cargo: +f[col.Cargo] || 0, daylight: +f[col.DaylightH] || 0,
        camp: (f[col.Camp] || '').trim(),
      });
      lastProv = pid;
    }
  });
  const last = data[data.length - 1];
  journeys.push({
    dest, destId: idOf(last[col.Province]),
    startDate: data[0][col.Date], endDate: last[col.Date], days: data.length,
    provinceCount: new Set(data.map(f => idOf(f[col.Province]))).size,
    cargoFinal: +last[col.Cargo] || 0,
    carryingFinal: (last[col.Carrying] || '').replace(/"/g, '').trim(),
    larderFinal: Math.round(+last[col.Larder] || 0),
    bandFinal: +last[col.BandSize] || 0,
    keys,
  });
}

// province subset: everything visited + one neighbour ring, for map context
const sub = new Set(visited);
for (const pid of visited) { const p = byId.get(pid); if (p) p.neighbors.forEach(n => sub.add(n)); }
const provinces = [...sub].map(id => byId.get(id)).filter(Boolean).map(p => ({
  id: p.id, name: p.name, lat: +p.lat.toFixed(3), lon: +p.lon.toFixed(3),
  plots: p.plots, type: p.type, region: p.region,
  nb: p.neighbors.filter(n => sub.has(n)),
}));

// origin = the shared first province of the journeys
const originId = journeys[0].keys[0].pid;
const origin = byId.get(originId);
const scenario = journeys.length > 1 ? 'ParallelCaravansTest' : 'DhenijansarToWexkeepTest';
const allDates = journeys.flatMap(j => [j.startDate, j.endDate]).sort();

const bundle = {
  meta: {
    seed: +SEED, scenario,
    origin: { id: originId, name: origin.name, lat: +origin.lat.toFixed(3), lon: +origin.lon.toFixed(3), region: origin.region },
    dateStart: allDates[0], dateEnd: allDates[allDates.length - 1],
  },
  provinces, journeys,
};

const template = fs.readFileSync(path.join(WEB, 'dashboard.template.html'), 'utf8');
const out = template.replace('/*__BUNDLE__*/ null', JSON.stringify(bundle));
if (out.includes('__BUNDLE__')) { console.error('template placeholder not found'); process.exit(1); }
fs.writeFileSync(path.join(WEB, 'dashboard.html'), out);

console.log(`Built web/dashboard.html (${(out.length / 1024).toFixed(0)} KB) from seed ${SEED}`);
console.log(`  ${journeys.length} journeys · ${provinces.length} provinces · ${bundle.meta.dateStart} → ${bundle.meta.dateEnd}`);
for (const j of journeys) console.log(`  ${('→ ' + j.dest).padEnd(26)} ${j.provinceCount} prov · ${(j.days / 365.25).toFixed(1)}y · cargo ${j.cargoFinal}`);
