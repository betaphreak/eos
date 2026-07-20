/**
 * world-bundle projection — normalized Strapi content → the FLAT per-dataset shapes the engine's
 * committed exporter JSON uses (docs/studio-datamodel-rebuild-plan.md, Phase 4).
 *
 * PATH-KEYED: bundle.resources["/map/provinces.json"] is exactly what the engine reads from the
 * classpath resource `/map/provinces.json` today, so the engine's WorldSource.open(path) just
 * serializes resources[path] and every Jackson parser is unchanged.
 *
 * Transforms REVERSE scripts/seed.js: Strapi attrs → committed keys, relations → natural keys, and the
 * numeric/enum coercions seed.js applied (era prefix, string↔int, bool↔"1"/"0"). Some committed fields
 * the schema doesn't model are intentionally dropped (tech iAsset/Quote-key/SoundMP); the bundle is the
 * modeled subset. Faithfulness is checked by scripts/verify-bundle.js against generated/*.json.
 */

import zlib from 'node:zlib';

const uid = (n: string): any => `api::${n}.${n}`;
const clean = (o: any) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null));

// Version-keyed projection cache (module-scoped → per Strapi process). The projection is a heavy DB
// query (~50k rows + relations); assembling it on every boot/request would hammer the DB. Cache the
// serialized+gzipped bytes keyed by content-version and recompute only when a reseed bumps the version
// (or ?fresh=1 for an admin edit that didn't). A concurrent rebuild is coalesced via BUILDING.
type CachedBundle = { version: string; json: string; gzip: Buffer };
let CACHE: CachedBundle | null = null;
let BUILDING: Promise<CachedBundle> | null = null;
const ks = (arr: any[], f = 'key') => (arr || []).map((x: any) => x[f]); // relation array → natural-key list
const str = (v: any) => (v === null || v === undefined ? undefined : String(v)); // committed keeps these as strings
const bit = (v: any) => (v === undefined || v === null ? undefined : v ? '1' : '0'); // committed bools are "1"/"0"

/**
 * Read every row of a type, paged.
 *
 * EVERY paged read MUST be ordered. Postgres guarantees no row order for a LIMIT/OFFSET query
 * without an ORDER BY, so an unordered paged read can silently repeat or skip rows across page
 * boundaries — a correctness bug, not just cosmetics. It also made the bundle's row order vary
 * between reseeds: a locally-rebuilt bundle differed from the committed test fixture in 30 of 35
 * datasets with identical content and the same contentVersion, which left the fixture undiffable
 * and real drift hiding inside the noise.
 *
 * Callers sort on the type's NATURAL key (`key`/`tag`/`provinceId`/…), not on `id`: ids follow
 * seed.js's *concurrent* insert order, so they are stable within one database but not across
 * reseeds — which is exactly the property the fixture needs. `id:asc` is only the backstop for a
 * caller that names no key, and is enough to keep pagination sound.
 */
async function all(u: any, { sort = ['id:asc'], ...opts }: any = {}): Promise<any[]> {
  const out: any[] = [];
  const PAGE = 2000;
  for (let start = 0; ; start += PAGE) {
    const batch = await strapi.documents(u).findMany({ ...opts, sort, start, limit: PAGE });
    out.push(...batch);
    if (batch.length < PAGE) break;
  }
  return out;
}

/** Comparator over a row's fields in order — for datasets whose natural key isn't query-sortable. */
function by(...fields: string[]) {
  return (a: any, b: any) => {
    for (const f of fields) {
      const x = a[f], y = b[f];
      if (x !== y) return x === undefined ? 1 : y === undefined ? -1 : x < y ? -1 : 1;
    }
    return 0;
  };
}

export default {
  async build() {
    const [meta, resources] = await Promise.all([this.meta(), this.resources()]);
    return { meta, resources };
  },

  /** The serialized+gzipped bundle, cached by content-version. Pass fresh=true to force a rebuild. */
  async serialized(fresh = false): Promise<CachedBundle> {
    const current = (await this.meta()).contentVersion ?? 'none';
    if (!fresh && CACHE && CACHE.version === current) return CACHE;
    if (BUILDING) return BUILDING; // coalesce a concurrent rebuild
    BUILDING = (async () => {
      try {
        const bundle = await this.build();
        const json = JSON.stringify(bundle);
        CACHE = { version: current, json, gzip: zlib.gzipSync(json) };
        return CACHE;
      } finally {
        BUILDING = null;
      }
    })();
    return BUILDING;
  },

  async meta() {
    const mv: any = await strapi.documents(uid('map-version')).findFirst({});
    return { mapVersion: mv?.mapVersion ?? null, contentVersion: mv?.contentVersion ?? null };
  },

  async resources() {
    const [
      countries, cultures, religions, tradegoods, areas, regions, superregions, provinces,
      adjacencies, edges, portals, terrains, features, bonusesAll, improvements, routes,
      techs, unitCombats, units, buildings, recipes, housing, resourceSources, routeModels,
      terrainArt, feastsHuman, feastsHarimari, humanNames, regionEarthMap, economies,
    ] = await Promise.all([
      this.countries(), this.cultures(), this.religions(), this.tradegoods(), this.areas(),
      this.regions(), this.superregions(), this.provinces(), this.adjacencies(), this.edges(),
      this.portals(), this.terrains(), this.features(), this.bonuses(), this.improvements(),
      this.routes(), this.techs(), this.unitCombats(), this.units(), this.buildings(),
      this.recipes(), this.housing(), this.resourceSources(), this.routeModels(), this.terrainArt(),
      this.feasts('human'), this.feasts('harimari'), this.namePools('human'), this.regionEarthMap(),
      this.economies(),
    ]);

    // bonus is one collection; committed splits it into base bonuses.json vs manufactured-bonuses.json.
    // The files share no types and have disjoint class sets — manufactured-bonuses.json holds the
    // MANUFACTURED and WONDER classes; bonuses.json holds CROP/LUXURY/PRODUCTION/STRATEGIC/… — so split
    // by class set.
    const isManuf = (b: any) => b.bonusClass === 'BONUSCLASS_MANUFACTURED' || b.bonusClass === 'BONUSCLASS_WONDER';
    const manufactured = bonusesAll.filter(isManuf);
    const baseBonuses = bonusesAll.filter((b: any) => !isManuf(b));
    // building-unlocks / unit-unlocks are the inverse of prereqTech — reconstruct from the projections.
    const buildingUnlocks = invertPrereq(buildings, 'id', (t, id) => ({ target: id, kind: 'UNLOCK' }));
    const unitUnlocks = invertPrereq(units, 'id', (t, id) => ({ kind: 'UNLOCK', target: id }));

    return {
      // omitted entirely when unauthored — see economies() on why absent != empty
      ...(economies ? { '/balance/economies.json': economies } : {}),
      '/map/countries.json': countries,
      '/map/cultures.json': cultures,
      '/map/religions.json': religions,
      '/map/tradegoods.json': tradegoods,
      '/map/areas.json': areas,
      '/map/regions.json': regions,
      '/map/superregions.json': superregions,
      '/map/provinces.json': provinces,
      '/map/adjacencies.json': adjacencies,
      '/map/edges.json': edges,
      '/map/portals.json': portals,
      '/map/route-models.json': routeModels,
      '/map/terrain-art.json': terrainArt,
      '/terrains.json': terrains,
      '/features.json': features,
      '/bonuses.json': baseBonuses,
      '/manufactured-bonuses.json': manufactured,
      '/improvements.json': improvements,
      '/routes.json': routes,
      '/techs.json': techs,
      '/tech-effects.json': {}, // empty stub today
      '/building-unlocks.json': buildingUnlocks,
      '/unit-unlocks.json': unitUnlocks,
      '/unit-combats.json': unitCombats,
      '/units.json': units,
      '/buildings.json': buildings,
      '/recipes.json': recipes,
      '/housing.json': housing,
      '/tier1-providers.json': resourceSources,
      '/feasts.json': feastsHuman,
      '/feasts-harimari.json': feastsHarimari,
      '/human-names/male.json': humanNames.male,
      '/human-names/female.json': humanNames.female,
      '/human-names/dynasty.json': humanNames.dynasty,
      '/geo/region-earth-map.json': regionEarthMap,
    };
  },

  // ── geography leaves ──
  async countries() { return (await all(uid('country'), { sort: ['tag:asc'], fields: ['tag', 'name', 'color'] })).map((r) => clean({ tag: r.tag, name: r.name, color: r.color })); },
  async cultures() { return (await all(uid('culture'), { sort: ['key:asc'], fields: ['key', 'name', 'group', 'color'] })).map((r) => clean({ key: r.key, name: r.name, group: r.group, color: r.color })); },
  async religions() { return (await all(uid('religion'), { sort: ['key:asc'], fields: ['key', 'name', 'group', 'color'] })).map((r) => clean({ key: r.key, name: r.name, group: r.group, color: r.color })); },
  async tradegoods() { return (await all(uid('trade-good'), { sort: ['key:asc'], fields: ['key', 'name', 'color', 'category'] })).map((r) => clean({ key: r.key, name: r.name, color: r.color, category: r.category })); },

  // ── geography hierarchy ──
  async areas() { return (await all(uid('area'), { sort: ['key:asc'], fields: ['key', 'name'], populate: { provinces: { fields: ['provinceId'] } } })).map((r) => clean({ key: r.key, name: r.name, provinces: ks(r.provinces, 'provinceId') })); },
  async regions() { return (await all(uid('region'), { sort: ['key:asc'], fields: ['key', 'name'], populate: { areas: { fields: ['key'] } } })).map((r) => clean({ key: r.key, name: r.name, areas: ks(r.areas) })); },
  async superregions() { return (await all(uid('super-region'), { sort: ['key:asc'], fields: ['key', 'name'], populate: { regions: { fields: ['key'] } } })).map((r) => clean({ key: r.key, name: r.name, regions: ks(r.regions) })); },

  async provinces() {
    const rows = await all(uid('province'), { sort: ['provinceId:asc'],
      fields: ['provinceId', 'name', 'latitude', 'longitude', 'plots', 'waterPlots', 'type', 'continent', 'realm', 'winter', 'monsoon', 'climate', 'baseTax', 'baseProduction', 'baseManpower', 'city'],
      populate: { owner: { fields: ['tag'] }, controller: { fields: ['tag'] }, culture: { fields: ['key'] }, religion: { fields: ['key'] }, tradeGood: { fields: ['key'] }, area: { fields: ['key'] }, region: { fields: ['key'] }, neighbors: { fields: ['provinceId'] } },
    });
    return rows.map((r) => clean({
      id: r.provinceId, name: r.name, lat: num(r.latitude), lon: num(r.longitude), plots: r.plots, waterPlots: r.waterPlots,
      type: r.type, region: r.region?.key, area: r.area?.key, continent: r.continent, realm: r.realm,
      winter: r.winter, monsoon: r.monsoon, climate: r.climate, owner: r.owner?.tag, controller: r.controller?.tag,
      culture: r.culture?.key, religion: r.religion?.key, trade_goods: r.tradeGood?.key,
      base_tax: r.baseTax, base_production: r.baseProduction, base_manpower: r.baseManpower, city: r.city || undefined,
      neighbors: ks(r.neighbors, 'provinceId'), // NOT sorted — edges.km[] is parallel to this order
    }));
  },
  // its natural key is the (from, to) pair, which lives in RELATIONS — not sortable in the query, so
  // order the mapped rows instead
  async adjacencies() { return (await all(uid('adjacency'), { fields: ['type', 'comment'], populate: { from: { fields: ['provinceId'] }, to: { fields: ['provinceId'] } } })).map((r) => clean({ from: r.from?.provinceId, to: r.to?.provinceId, type: r.type ?? '', comment: r.comment })).sort(by('from', 'to', 'type')); },
  async edges() { return (await all(uid('province-edge'), { sort: ['provinceId:asc'], fields: ['provinceId', 'km'] })).map((r) => clean({ id: r.provinceId, km: r.km })); },
  async portals() { return (await all(uid('province-portal'), { sort: ['provinceId:asc'], fields: ['provinceId', 'portals'] })).map((r) => clean({ id: r.provinceId, portals: r.portals })); },

  // ── terrain / plot reference ──
  async terrains() { return (await all(uid('terrain'), { sort: ['key:asc'], fields: ['key', 'yields', 'found', 'buildModifier', 'healthPercent', 'movement'] })).map((r) => clean({ type: r.key, yields: r.yields, bFound: r.found, buildModifier: r.buildModifier, healthPercent: r.healthPercent, movement: r.movement })); },
  async features() {
    return (await all(uid('feature'), { sort: ['key:asc'], fields: ['key', 'yieldChanges', 'clearCost', 'requiresFlatlands', 'requiresRiver', 'healthPercent', 'growth', 'movement', 'appearance'], populate: { validTerrains: { fields: ['key'] } } }))
      .map((r) => clean({ type: r.key, yieldChanges: r.yieldChanges, clearCost: r.clearCost, requiresFlatlands: r.requiresFlatlands, requiresRiver: r.requiresRiver, validTerrains: ks(r.validTerrains), healthPercent: r.healthPercent, growth: r.growth, movement: r.movement, appearance: r.appearance }));
  },
  async bonuses() {
    return (await all(uid('bonus'), { sort: ['key:asc'],
      fields: ['key', 'bonusClass', 'yieldChanges', 'health', 'happiness', 'minLatitude', 'maxLatitude', 'hills', 'flatlands', 'peaks', 'placementOrder', 'constAppearance', 'randApps', 'tilesPer', 'minAreaSize', 'groupRange', 'groupRand', 'techEra'],
      populate: { techReveal: { fields: ['key'] }, techCityTrade: { fields: ['key'] }, validTerrains: { fields: ['key'] }, validFeatures: { fields: ['key'] }, validFeatureTerrains: { fields: ['key'] } },
    })).map((r) => clean({
      type: r.key, bonusClass: r.bonusClass, yieldChanges: r.yieldChanges, techReveal: r.techReveal?.key, techCityTrade: r.techCityTrade?.key,
      health: r.health, happiness: r.happiness, minLatitude: r.minLatitude, maxLatitude: r.maxLatitude, hills: r.hills, flatlands: r.flatlands, peaks: r.peaks,
      validTerrains: ks(r.validTerrains), validFeatures: ks(r.validFeatures), validFeatureTerrains: ks(r.validFeatureTerrains),
      placementOrder: r.placementOrder, constAppearance: r.constAppearance, randApps: r.randApps, tilesPer: r.tilesPer, minAreaSize: r.minAreaSize, groupRange: r.groupRange, groupRand: r.groupRand, techEra: r.techEra,
    }));
  },
  async improvements() {
    return (await all(uid('improvement'), { sort: ['key:asc'], fields: ['key', 'yieldChanges', 'hillsMakesValid', 'freshWaterMakesValid', 'buildCost', 'healthPercent', 'upgradeTime', 'culture', 'actsAsCity', 'techYieldChanges'], populate: { prereqTech: { fields: ['key'] }, validTerrains: { fields: ['key'] }, validFeatures: { fields: ['key'] }, upgradeType: { fields: ['key'] } } }))
      .map((r) => clean({ type: r.key, yieldChanges: r.yieldChanges, prereqTech: r.prereqTech?.key, hillsMakesValid: r.hillsMakesValid, freshWaterMakesValid: r.freshWaterMakesValid, validTerrains: ks(r.validTerrains), validFeatures: ks(r.validFeatures), buildCost: r.buildCost, healthPercent: r.healthPercent, upgradeType: r.upgradeType?.key, upgradeTime: r.upgradeTime, culture: r.culture, actsAsCity: r.actsAsCity, techYieldChanges: r.techYieldChanges }));
  },
  async routes() {
    return (await all(uid('route'), { sort: ['key:asc'], fields: ['key', 'value', 'movement', 'flatMovement', 'advancedStartCost', 'seaTunnel', 'yields', 'trail'], populate: { bonusType: { fields: ['key'] } } }))
      .map((r) => clean({ type: r.key, value: r.value, movement: r.movement, flatMovement: r.flatMovement, advancedStartCost: r.advancedStartCost, bonusType: r.bonusType?.key, seaTunnel: r.seaTunnel, yields: r.yields, trail: r.trail }));
  },
  async routeModels() {
    return (await all(uid('route-model'), { sort: ['key:asc'], fields: ['modelFileKey', 'modelFile', 'lateModelFile', 'animated', 'connections', 'modelConnections', 'rotations'], populate: { routeType: { fields: ['key'] } } }))
      .map((r) => clean({ routeType: r.routeType?.key, modelFileKey: r.modelFileKey, modelFile: r.modelFile, lateModelFile: r.lateModelFile, animated: r.animated, connections: r.connections, modelConnections: r.modelConnections, rotations: r.rotations }));
  },
  async terrainArt() {
    return (await all(uid('terrain-art'), { sort: ['artTag:asc', 'path:asc'], fields: ['artTag', 'path', 'grid', 'detail', 'layerOrder', 'alphaShader', 'blend'], populate: { terrain: { fields: ['key'] } } }))
      .map((r) => clean({ terrain: r.terrain?.key, artTag: r.artTag, path: r.path, grid: r.grid, detail: r.detail, layerOrder: r.layerOrder, alphaShader: r.alphaShader, blend: r.blend }));
  },

  // ── tech tree + game definitions ──
  async techs() {
    return (await all(uid('tech'), { sort: ['key:asc'], fields: ['key', 'name', 'help', 'quote', 'description', 'civilopedia', 'advisor', 'era', 'cost', 'gridX', 'gridY', 'trade', 'goodyTech', 'sound', 'button', 'flavors'], populate: { andPreReqs: { fields: ['key'] }, orPreReqs: { fields: ['key'] } } }))
      .map((r) => clean({
        Type: r.key, name: r.name, help: r.help, quote: r.quote, Description: r.description, Civilopedia: r.civilopedia,
        Advisor: r.advisor, Era: r.era ? `C2C_ERA_${r.era}` : undefined, iCost: str(r.cost), iGridX: str(r.gridX), iGridY: str(r.gridY),
        bTrade: bit(r.trade), bGoodyTech: bit(r.goodyTech), Sound: r.sound, Button: r.button, Flavors: r.flavors,
        AndPreReqs: prereqNode(ks(r.andPreReqs)), OrPreReqs: prereqNode(ks(r.orPreReqs)),
      }));
  },
  async unitCombats() {
    return (await all(uid('combat-class'), { sort: ['key:asc'], fields: ['key', 'name', 'signatureSkill', 'categoryButton', 'earlyWithdrawChange', 'tauntChange', 'dodgeModifierChange', 'damageModifierChange', 'precisionModifierChange', 'captureResistanceModifierChange', 'forMilitary'] }))
      .map((r) => clean({ id: r.key, name: r.name, signatureSkill: r.signatureSkill, categoryButton: r.categoryButton, iEarlyWithdrawChange: r.earlyWithdrawChange, iTauntChange: r.tauntChange, iDodgeModifierChange: r.dodgeModifierChange, iDamageModifierChange: r.damageModifierChange, iPrecisionModifierChange: r.precisionModifierChange, iCaptureResistanceModifierChange: r.captureResistanceModifierChange, bForMilitary: r.forMilitary }));
  },
  async units() {
    return (await all(uid('unit'), { sort: ['key:asc'], fields: ['key', 'name', 'pedia', 'defaultUnitAI', 'caravanRole', 'domain', 'quality', 'bandSizeClass', 'moves', 'combat', 'builds', 'artDefineTag', 'button', 'special', 'species'], populate: { prereqTech: { fields: ['key'] }, obsoleteTech: { fields: ['key'] }, combatClass: { fields: ['key'] }, andTechs: { fields: ['key'] } } }))
      .map((r) => clean({ id: r.key, name: r.name, pedia: r.pedia, prereqTech: r.prereqTech?.key, combatClass: r.combatClass?.key, defaultUnitAI: r.defaultUnitAI, caravanRole: r.caravanRole, domain: r.domain, iMoves: r.moves, iCombat: r.combat, obsoleteTech: r.obsoleteTech?.key, quality: r.quality, bandSizeClass: r.bandSizeClass, andTechs: ks(r.andTechs), artDefineTag: r.artDefineTag, button: r.button, builds: r.builds, special: r.special, species: r.species }));
  },
  async buildings() {
    return (await all(uid('building'), { sort: ['key:asc'], fields: ['key', 'name', 'pedia', 'category', 'artDefineTag', 'button', 'cost'], populate: { prereqTech: { fields: ['key'] }, andTechs: { fields: ['key'] } } }))
      .map((r) => clean({ id: r.key, name: r.name, pedia: r.pedia, category: r.category, prereqTech: r.prereqTech?.key, andTechs: ks(r.andTechs), artDefineTag: r.artDefineTag, button: r.button, cost: str(r.cost) }));
  },
  async recipes() {
    return (await all(uid('recipe'), { sort: ['key:asc'], fields: ['key', 'river', 'freshWater'], populate: { bonus: { fields: ['key'] }, outputs: { fields: ['key'] }, prereqBonuses: { fields: ['key'] }, vicinityBonuses: { fields: ['key'] }, rawVicinityBonuses: { fields: ['key'] }, prereqTech: { fields: ['key'] }, obsoleteTech: { fields: ['key'] }, prereqBuildings: { fields: ['key'] }, prereqOrBuildings: { fields: ['key'] }, prereqOrTerrains: { fields: ['key'] }, prereqOrFeatures: { fields: ['key'] } } }))
      .map((r) => clean({ type: r.key, outputs: ks(r.outputs), bonus: r.bonus?.key, prereqBonuses: ks(r.prereqBonuses), vicinityBonuses: ks(r.vicinityBonuses), rawVicinityBonuses: ks(r.rawVicinityBonuses), prereqTech: r.prereqTech?.key, obsoleteTech: r.obsoleteTech?.key, prereqBuildings: ks(r.prereqBuildings), prereqOrBuildings: ks(r.prereqOrBuildings), prereqOrTerrains: ks(r.prereqOrTerrains), prereqOrFeatures: ks(r.prereqOrFeatures), river: r.river, freshWater: r.freshWater }));
  },
  async housing() {
    return (await all(uid('housing'), { sort: ['key:asc'], fields: ['key', 'prereqPopulation', 'freshWater', 'autoBuild', 'health', 'happiness', 'yieldChanges', 'commerceChanges'], populate: { prereqTech: { fields: ['key'] }, obsoleteTech: { fields: ['key'] }, obsoletesToBuilding: { fields: ['key'] }, bonus: { fields: ['key'] }, prereqBonuses: { fields: ['key'] }, prereqBuildings: { fields: ['key'] }, prereqOrBuildings: { fields: ['key'] }, replacements: { fields: ['key'] }, prereqOrFeatures: { fields: ['key'] }, prereqOrTerrains: { fields: ['key'] } } }))
      .map((r) => clean({ type: r.key, prereqTech: r.prereqTech?.key, obsoleteTech: r.obsoleteTech?.key, obsoletesToBuilding: r.obsoletesToBuilding?.key, prereqPopulation: r.prereqPopulation, freshWater: r.freshWater, autoBuild: r.autoBuild, bonus: r.bonus?.key, prereqBonuses: ks(r.prereqBonuses), prereqBuildings: ks(r.prereqBuildings), prereqOrBuildings: ks(r.prereqOrBuildings), prereqOrFeatures: ks(r.prereqOrFeatures), prereqOrTerrains: ks(r.prereqOrTerrains), replacements: ks(r.replacements), health: r.health, happiness: r.happiness, yieldChanges: r.yieldChanges, commerceChanges: r.commerceChanges }));
  },
  async resourceSources() {
    return (await all(uid('resource-source'), { sort: ['key:asc'], fields: ['key', 'gatherers'], populate: { output: { fields: ['key'] } } }))
      .map((r) => clean({ type: r.key, output: r.output?.key, gatherers: r.gatherers }));
  },

  // ── calendar / naming ──
  async feasts(race: string) {
    // no single scalar natural key — order on the calendar position, then the name
    return (await all(uid('feast'), { fields: ['month', 'day', 'name', 'race'], sort: ['month:asc', 'day:asc', 'name:asc'] }))
      .filter((r) => r.race === race)
      .map((r) => clean({ month: r.month, day: r.day, name: r.name }));
  },
  async namePools(race: string) {
    const rows = await all(uid('name-pool'), { sort: ['key:asc'], fields: ['race', 'kind', 'names'] });
    const pick = (kind: string) => (rows.find((r) => r.race === race && r.kind === kind) || {}).names || [];
    return { male: pick('male'), female: pick('female'), dynasty: pick('dynasty') };
  },
  async regionEarthMap() {
    const rem: any = await strapi.documents(uid('region-earth-map')).findFirst({});
    // seed.js's rest-spread double-nested the map under regions.regions; un-nest defensively.
    const inner = rem?.regions?.regions ?? rem?.regions ?? {};
    return { regions: inner };
  },
  // The era x race economy matrix (engine: EconomyCatalog). Returns null when unauthored, so the
  // key is OMITTED from the bundle rather than emitted empty: the engine reads an absent resource
  // as "every cell keeps its compiled constant", while a present-but-empty {} would be authored
  // emptiness. A malformed one makes the engine throw, by design — an economy is load-bearing.
  async economies() {
    const row: any = await strapi.documents(uid('economy-matrix')).findFirst({});
    const m = row?.economies;
    return m && Object.keys(m).length > 0 ? m : null;
  },
};

// TECH prereq node: committed uses {PrereqTech: key} for one, {PrereqTech: [keys]} for many, omitted if none.
function prereqNode(keys: string[]) {
  if (!keys || keys.length === 0) return undefined;
  return { PrereqTech: keys.length === 1 ? keys[0] : keys };
}
// building/unit unlock overlay = inverse of prereqTech: { TECH_X: [entry(TECH_X, id)] }.
function invertPrereq(rows: any[], idField: string, entry: (tech: string, id: string) => any) {
  const out: Record<string, any[]> = {};
  for (const r of rows) if (r.prereqTech) (out[r.prereqTech] = out[r.prereqTech] || []).push(entry(r.prereqTech, r[idField]));
  return out;
}
function num(v: any) { return v === null || v === undefined ? undefined : typeof v === 'string' ? parseFloat(v) : v; }
