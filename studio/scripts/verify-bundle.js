// verify-bundle.js — prove the /api/world-bundle projection faithfully reverses the committed JSON.
// Fetches the live bundle, then per dataset compares it record-by-record (keyed by natural id) against
// the engine's committed generated/*.json — canonicalized (null-dropped, arrays sorted) so key order /
// null-omission / array order don't create false diffs. Run from studio/ with Strapi up:
//   node scripts/verify-bundle.js
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const zlib = require('node:zlib');
const http = require('node:http');

const RES = join(__dirname, '..', '..', 'civstudio-engine', 'src', 'main', 'resources');
const MAP = join(RES, 'generated', 'map');
const GEN = join(RES, 'generated');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

// canonical form: drop null/undefined, sort object keys, sort arrays (primitives asc, objects by JSON)
function canon(v) {
  if (Array.isArray(v)) {
    const a = v.map(canon).filter((x) => x !== undefined && x !== null);
    a.sort((x, y) => (JSON.stringify(x) < JSON.stringify(y) ? -1 : 1));
    return a;
  }
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v).sort()) {
      const cv = canon(v[k]);
      if (cv !== undefined && cv !== null) o[k] = cv;
    }
    return o;
  }
  return v;
}
const eq = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));

// Read a pre-fetched bundle file if given (avoids `strapi develop` reload flakiness), else HTTP GET.
function fetchBundle() {
  if (process.argv[2]) return Promise.resolve(readJson(process.argv[2]));
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:1337/api/world-bundle', { headers: { 'accept-encoding': 'gzip' } }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        let buf = Buffer.concat(chunks);
        if (res.headers['content-encoding'] === 'gzip') buf = zlib.gunzipSync(buf);
        resolve(JSON.parse(buf.toString('utf8')));
      });
    }).on('error', reject);
  });
}

// dataset → { committed file, natural key }
const DATASETS = [
  { path: '/map/countries.json', file: join(MAP, 'countries.json'), key: (r) => r.tag },
  { path: '/map/cultures.json', file: join(MAP, 'cultures.json'), key: (r) => r.key },
  { path: '/map/religions.json', file: join(MAP, 'religions.json'), key: (r) => r.key },
  { path: '/map/tradegoods.json', file: join(MAP, 'tradegoods.json'), key: (r) => r.key },
  { path: '/map/areas.json', file: join(MAP, 'areas.json'), key: (r) => r.key },
  { path: '/map/regions.json', file: join(MAP, 'regions.json'), key: (r) => r.key },
  { path: '/map/superregions.json', file: join(MAP, 'superregions.json'), key: (r) => r.key },
  { path: '/map/provinces.json', file: join(MAP, 'provinces.json'), key: (r) => r.id },
  { path: '/map/adjacencies.json', file: join(MAP, 'adjacencies.json'), key: (r) => `${r.from}_${r.to}` },
  { path: '/map/edges.json', file: join(MAP, 'edges.json'), key: (r) => r.id },
  { path: '/map/portals.json', file: join(MAP, 'portals.json'), key: (r) => r.id },
  { path: '/terrains.json', file: join(GEN, 'terrains.json'), key: (r) => r.type },
  { path: '/unit-combats.json', file: join(GEN, 'unit-combats.json'), key: (r) => r.id },
];

(async () => {
  const bundle = await fetchBundle();
  console.log('meta:', JSON.stringify(bundle.meta));
  // committed areas.json references 1563 phantom province ids absent from provinces.json (export
  // cruft — filtered sea provinces). Relations can't point at non-existent rows, so the normalized
  // model correctly drops them; strip them from the committed side so the comparison is apples-to-apples.
  const provIds = new Set(readJson(join(MAP, 'provinces.json')).map((p) => p.id));
  let allOk = true;
  for (const d of DATASETS) {
    let committed = readJson(d.file);
    if (d.path === '/map/areas.json') {
      committed = committed.map((a) => ({ ...a, provinces: a.provinces.filter((id) => provIds.has(id)) }));
    }
    const got = bundle.resources[d.path] || [];
    const cMap = new Map(committed.map((r) => [String(d.key(r)), r]));
    const gMap = new Map(got.map((r) => [String(d.key(r)), r]));
    let mism = 0;
    const samples = [];
    for (const [k, cRec] of cMap) {
      const gRec = gMap.get(k);
      if (!gRec || !eq(cRec, gRec)) {
        mism++;
        if (samples.length < 2) samples.push({ k, committed: cRec, got: gRec || '(missing)' });
      }
    }
    const missingInCommitted = [...gMap.keys()].filter((k) => !cMap.has(k)).length;
    const ok = mism === 0 && got.length === committed.length && missingInCommitted === 0;
    allOk = allOk && ok;
    console.log(`${ok ? 'ok ' : 'XX '} ${d.path.padEnd(24)} committed=${committed.length} bundle=${got.length} mismatch=${mism} extra=${missingInCommitted}`);
    for (const s of samples) {
      console.log('     key', s.k);
      console.log('       committed:', JSON.stringify(s.committed).slice(0, 220));
      console.log('       bundle   :', JSON.stringify(s.got).slice(0, 220));
    }
  }
  console.log(allOk ? '\nALL DATASETS FAITHFUL ✓' : '\nDIFFERENCES FOUND');
  process.exit(allOk ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
