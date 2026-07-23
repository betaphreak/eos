// verify-bundle.js — prove the /api/world-bundle projection faithfully reverses the committed JSON.
// Usage (Strapi up):  node scripts/verify-bundle.js           (fetch live)
//        or:          node scripts/verify-bundle.js <file>    (a pre-fetched bundle, avoids reload flakiness)
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const zlib = require('node:zlib');
const http = require('node:http');

const RES = join(__dirname, '..', '..', 'civstudio-engine', 'src', 'main', 'resources');
const MAP = join(RES, 'generated', 'map');
const GEN = join(RES, 'generated');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

function canon(v) {
  if (Array.isArray(v)) { const a = v.map(canon).filter((x) => x !== undefined && x !== null); a.sort((x, y) => (JSON.stringify(x) < JSON.stringify(y) ? -1 : 1)); return a; }
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v).sort()) { const cv = canon(v[k]); if (cv !== undefined && cv !== null) o[k] = cv; } return o; }
  return v;
}
const eq = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));

function fetchBundle() {
  if (process.argv[2]) return Promise.resolve(readJson(process.argv[2]));
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:1337/api/world-bundle', { headers: { 'accept-encoding': 'gzip' } }, (res) => {
      const chunks = []; res.on('data', (c) => chunks.push(c));
      res.on('end', () => { let buf = Buffer.concat(chunks); if (res.headers['content-encoding'] === 'gzip') buf = zlib.gunzipSync(buf); resolve(JSON.parse(buf.toString('utf8'))); });
    }).on('error', reject);
  });
}

// records datasets: array keyed by a natural id. orderKeys = fields compared in ORDER (not as a set).
const REC = [
  { path: '/map/countries.json', file: join(MAP, 'countries.json'), key: (r) => r.tag },
  { path: '/map/cultures.json', file: join(MAP, 'cultures.json'), key: (r) => r.key },
  { path: '/map/religions.json', file: join(MAP, 'religions.json'), key: (r) => r.key },
  { path: '/map/tradegoods.json', file: join(MAP, 'tradegoods.json'), key: (r) => r.key },
  { path: '/map/areas.json', file: join(MAP, 'areas.json'), key: (r) => r.key },
  { path: '/map/regions.json', file: join(MAP, 'regions.json'), key: (r) => r.key },
  { path: '/map/superregions.json', file: join(MAP, 'superregions.json'), key: (r) => r.key },
  { path: '/map/provinces.json', file: join(MAP, 'provinces.json'), key: (r) => r.id, orderKeys: ['neighbors'] },
  { path: '/map/adjacencies.json', file: join(MAP, 'adjacencies.json'), key: (r) => `${r.from}_${r.to}` },
  { path: '/map/edges.json', file: join(MAP, 'edges.json'), key: (r) => r.id, orderKeys: ['km'] },
  { path: '/map/portals.json', file: join(MAP, 'portals.json'), key: (r) => r.id },
  { path: '/map/route-models.json', file: join(MAP, 'route-models.json'), key: (r) => `${r.routeType}_${r.modelFileKey}_${r.connections}_${r.modelConnections}` },
  { path: '/map/terrain-art.json', file: join(MAP, 'terrain-art.json'), key: (r) => r.artTag },
  { path: '/terrains.json', file: join(GEN, 'terrains.json'), key: (r) => r.type },
  { path: '/features.json', file: join(GEN, 'features.json'), key: (r) => r.type },
  { path: '/bonuses.json', file: join(GEN, 'bonuses.json'), key: (r) => r.type },
  { path: '/manufactured-bonuses.json', file: join(GEN, 'manufactured-bonuses.json'), key: (r) => r.type },
  { path: '/improvements.json', file: join(GEN, 'improvements.json'), key: (r) => r.type },
  { path: '/routes.json', file: join(GEN, 'routes.json'), key: (r) => r.type },
  { path: '/techs.json', file: join(GEN, 'techs.json'), key: (r) => r.Type },
  { path: '/unit-combats.json', file: join(GEN, 'unit-combats.json'), key: (r) => r.id },
  { path: '/units.json', file: join(GEN, 'units.json'), key: (r) => r.id },
  { path: '/buildings.json', file: join(GEN, 'buildings.json'), key: (r) => r.id },
  { path: '/recipes.json', file: join(GEN, 'recipes.json'), key: (r) => r.type },
  { path: '/housing.json', file: join(GEN, 'housing.json'), key: (r) => r.type },
  { path: '/tier1-providers.json', file: join(GEN, 'tier1-providers.json'), key: (r) => r.type },
  { path: '/feasts.json', file: join(RES, 'feasts.json'), key: (r) => `${r.month}_${r.day}_${r.name}` },
  { path: '/feasts-harimari.json', file: join(RES, 'feasts-harimari.json'), key: (r) => `${r.month}_${r.day}_${r.name}` },
];
// deep datasets: compare the whole value (optionally a picked sub-path) after canonicalization.
const DEEP = [
  { path: '/tech-effects.json', file: join(RES, 'tech-effects.json') },
  { path: '/human-names/male.json', file: join(RES, 'human-names', 'male.json') },
  { path: '/human-names/female.json', file: join(RES, 'human-names', 'female.json') },
  { path: '/human-names/dynasty.json', file: join(RES, 'human-names', 'dynasty.json') },
  { path: '/geo/region-earth-map.json', file: join(RES, 'geo', 'region-earth-map.json'), pick: (v) => v.regions },
  { path: '/building-unlocks.json', file: join(GEN, 'building-unlocks.json'), tolerateExtra: true },
  { path: '/unit-unlocks.json', file: join(GEN, 'unit-unlocks.json'), tolerateExtra: true },
];

// Target id-sets for PHANTOM-REF stripping. C2C data references a broader universe than the kept
// collections; a relation can't point at a non-existent row, so the store/bundle drop such refs. Strip
// them from committed too (behavior-neutral — the engine can't resolve them either) for a fair compare.
const ID = {
  prov: new Set(readJson(join(MAP, 'provinces.json')).map((p) => p.id)),
  area: new Set(readJson(join(MAP, 'areas.json')).map((a) => a.key)),
  region: new Set(readJson(join(MAP, 'regions.json')).map((r) => r.key)),
  terrain: new Set(readJson(join(GEN, 'terrains.json')).map((t) => t.type)),
  feature: new Set(readJson(join(GEN, 'features.json')).map((f) => f.type)),
  tech: new Set(readJson(join(GEN, 'techs.json')).map((t) => t.Type)),
  building: new Set(readJson(join(GEN, 'buildings.json')).map((b) => b.id)),
  bonus: new Set([...readJson(join(GEN, 'bonuses.json')), ...readJson(join(GEN, 'manufactured-bonuses.json'))].map((b) => b.type)),
  unit: new Set(readJson(join(GEN, 'units.json')).map((u) => u.id)),
  improvement: new Set(readJson(join(GEN, 'improvements.json')).map((i) => i.type)),
  combat: new Set(readJson(join(GEN, 'unit-combats.json')).map((u) => u.id)),
};
const BONUS_REL = { validTerrains: ['terrain', 1], validFeatures: ['feature', 1], validFeatureTerrains: ['terrain', 1], techReveal: ['tech'], techCityTrade: ['tech'] };
const REL = {
  '/map/areas.json': { provinces: ['prov', 1] },
  '/map/regions.json': { areas: ['area', 1] },
  '/map/superregions.json': { regions: ['region', 1] },
  '/map/provinces.json': { neighbors: ['prov', 1] },
  '/features.json': { validTerrains: ['terrain', 1] },
  '/bonuses.json': BONUS_REL, '/manufactured-bonuses.json': BONUS_REL,
  '/improvements.json': { validTerrains: ['terrain', 1], validFeatures: ['feature', 1], prereqTech: ['tech'], upgradeType: ['improvement'] },
  '/routes.json': { bonusType: ['bonus'] },
  '/units.json': { prereqTech: ['tech'], obsoleteTech: ['tech'], combatClass: ['combat'], andTechs: ['tech', 1] },
  '/buildings.json': { prereqTech: ['tech'], obsoleteTech: ['tech'], andTechs: ['tech', 1] },
  '/recipes.json': { outputs: ['bonus', 1], bonus: ['bonus'], prereqBonuses: ['bonus', 1], vicinityBonuses: ['bonus', 1], rawVicinityBonuses: ['bonus', 1], prereqTech: ['tech'], obsoleteTech: ['tech'], prereqBuildings: ['building', 1], prereqOrBuildings: ['building', 1], prereqOrTerrains: ['terrain', 1], prereqOrFeatures: ['feature', 1] },
  '/housing.json': { prereqTech: ['tech'], obsoleteTech: ['tech'], obsoletesToBuilding: ['building'], bonus: ['bonus'], prereqBonuses: ['bonus', 1], prereqBuildings: ['building', 1], prereqOrBuildings: ['building', 1], replacements: ['building', 1], prereqOrFeatures: ['feature', 1], prereqOrTerrains: ['terrain', 1] },
};
// Field-presence guard. The bundle projection legitimately omits some committed fields: fields the
// engine DERIVES rather than reads verbatim (province region/area/continent/realm come from the
// area/region relations + geography), and raw C2C attributes studio simply does not model (the ~40
// flavor/AI tech flags, buildings.help, units.iCost, and the reference-only recipe/housing fields the
// engine never reads at runtime). Those are ACCEPTED here so the run stays green on today's content.
// Any dropped field NOT in this allowlist FAILS the run — so a reseed that silently loses a NEW field
// (the class of drift that once shipped to prod unnoticed) is caught. See
// docs/studio-datamodel-rebuild-plan.md Phase 5 (fidelity caveat). Shrink this list as studio's
// coverage improves.
const ACCEPTED_DROPPED = {
  '/map/provinces.json': ['region', 'area', 'continent', 'realm'],
  '/routes.json': ['bonusType'],
  '/techs.json': ['iAsset', 'Quote', 'SoundMP', 'iHappiness', 'bLanguage', 'FirstFreeUnit', 'iPower', 'iWorkerSpeedModifier', 'iAIWeight', 'iHealth', 'iAITradeModifier', 'bWaterWork', 'TerrainTrades', 'bRiverTrade', 'bOpenBordersTrading', 'iTradeRoutes', 'bEmbassyTrading', 'iFirstFreeTechs', 'bTechTrading', 'iFeatureProductionModifier', 'bMapCentering', 'bGoldTrading', 'bBridgeBuilding', 'bIrrigation', 'PrereqOrBuildings', 'iGlobalTradeModifier', 'iGlobalForeignTradeModifier', 'iTradeMissionModifier', 'CommerceFlexible', 'DomainExtraMoves', 'bVassalTrading', 'bExtraWaterSeeFrom', 'bMapTrading', 'bCanPassPeaks', 'bCanFoundOnPeaks', 'bDefensivePactTrading', 'bMoveFastPeaks', 'iCorporationRevenueModifier', 'iCorporationMaintenanceModifier', 'bIgnoreIrrigation'],
  '/units.json': ['iCost'],
  '/buildings.json': ['help'],
  '/recipes.json': ['bonus', 'obsoleteTech'],
  '/housing.json': ['obsoletesToBuilding', 'bonus', 'prereqTech', 'obsoleteTech'],
};

function stripPhantom(rec, relspec) {
  if (!relspec) return rec;
  const out = { ...rec };
  for (const [f, [t, list]] of Object.entries(relspec)) {
    if (!(f in out)) continue;
    const set = ID[t];
    if (list) out[f] = (out[f] || []).filter((x) => set.has(x));
    else if (out[f] != null && !set.has(out[f])) delete out[f]; // m2o phantom → bundle dropped it
  }
  return out;
}

(async () => {
  const bundle = await fetchBundle();
  console.log('meta:', JSON.stringify(bundle.meta));
  let allOk = true;

  for (const d of REC) {
    let committed = readJson(d.file).map((r) => stripPhantom(r, REL[d.path]));
    const got = bundle.resources[d.path] || [];
    const cMap = new Map(committed.map((r) => [String(d.key(r)), r]));
    const gMap = new Map(got.map((r) => [String(d.key(r)), r]));
    let missing = 0, valMism = 0, orderMism = 0;
    const dropped = new Set(), added = new Set(), samples = [];
    for (const [k, c] of cMap) {
      const g = gMap.get(k);
      if (!g) { missing++; if (samples.length < 2) samples.push(['missing', k, c, null]); continue; }
      for (const key of Object.keys(g)) if (!(key in c)) added.add(key);
      for (const key of Object.keys(c)) {
        if (!(key in g)) { dropped.add(key); continue; }
        const ordered = (d.orderKeys || []).includes(key);
        const ok = ordered ? JSON.stringify(g[key]) === JSON.stringify(c[key]) : eq(g[key], c[key]);
        if (!ok) {
          if (ordered) orderMism++; else valMism++;
          if (samples.length < 2) samples.push([ordered ? 'order:' + key : 'val:' + key, k, c[key], g[key]]);
        }
      }
    }
    const extra = [...gMap.keys()].filter((k) => !cMap.has(k)).length;
    const accepted = new Set(ACCEPTED_DROPPED[d.path] || []);
    const badDropped = [...dropped].filter((f) => !accepted.has(f)); // a NEW drop the allowlist doesn't cover
    const okDropped = [...dropped].filter((f) => accepted.has(f));
    const ok = missing === 0 && valMism === 0 && orderMism === 0 && extra === 0 && got.length === committed.length && badDropped.length === 0;
    allOk = allOk && ok;
    console.log(`${ok ? 'ok ' : 'XX '} ${d.path.padEnd(26)} c=${committed.length} b=${got.length} miss=${missing} valMism=${valMism} orderMism=${orderMism} extra=${extra}` +
      (badDropped.length ? ` DROPPED{${badDropped.join(',')}}` : '') +
      (okDropped.length ? ` dropped-ok{${okDropped.join(',')}}` : '') + (added.size ? ` added{${[...added].join(',')}}` : ''));
    for (const [why, k, c, g] of samples) { console.log(`     ${why} key=${k}`); console.log('       committed:', JSON.stringify(c).slice(0, 200)); console.log('       bundle   :', JSON.stringify(g).slice(0, 200)); }
  }

  for (const d of DEEP) {
    let c = readJson(d.file); if (d.pick) c = d.pick(c);
    let g = bundle.resources[d.path]; if (d.pick && g) g = d.pick(g);
    let ok;
    if (d.tolerateExtra) { // bundle is a superset of committed (reconstructed overlay ≈ prereq inverse)
      ok = Object.entries(c).every(([k, arr]) => g[k] && canon(g[k]).length >= canon(arr).length && eq(intersectByJson(g[k], arr), arr));
    } else ok = eq(c, g);
    allOk = allOk && ok;
    const cn = Array.isArray(c) ? c.length : Object.keys(c || {}).length;
    const gn = Array.isArray(g) ? g.length : Object.keys(g || {}).length;
    console.log(`${ok ? 'ok ' : 'XX '} ${d.path.padEnd(26)} committed=${cn} bundle=${gn}${d.tolerateExtra ? ' (superset ok)' : ''}`);
    if (!ok && !d.tolerateExtra) console.log('       committed:', JSON.stringify(c).slice(0, 160), '\n       bundle   :', JSON.stringify(g).slice(0, 160));
  }

  console.log(allOk
    ? '\nALL DATASETS FAITHFUL ✓ (dropped-ok = accepted omissions; DROPPED{…} would fail the guard)'
    : '\nDIFFERENCES FOUND — DROPPED{…} is a NEW committed field missing from the bundle: either fix the'
      + '\n  studio seed/projection to carry it, or (if intentional) add it to ACCEPTED_DROPPED. added=extra bundle fields.');
  process.exit(allOk ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });

function intersectByJson(gArr, cArr) { const cset = new Set(cArr.map((x) => JSON.stringify(canon(x)))); return gArr.filter((x) => cset.has(JSON.stringify(canon(x)))); }
