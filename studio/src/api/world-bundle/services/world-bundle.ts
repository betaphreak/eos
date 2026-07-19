/**
 * world-bundle projection — normalized Strapi content → the FLAT per-dataset shapes the engine's
 * committed exporter JSON uses (docs/studio-datamodel-rebuild-plan.md, Phase 4).
 *
 * The bundle is PATH-KEYED: bundle.resources["/map/provinces.json"] is exactly what the engine reads
 * from the classpath resource `/map/provinces.json` today. So the engine's WorldSource seam just does
 * open(path) → serialize(resources[path]) and every existing Jackson parser is unchanged.
 *
 * The transforms REVERSE scripts/seed.js: seed.js renamed committed keys → Strapi attrs (id→key,
 * lat→latitude, base_tax→baseTax, foreign keys → relations); here we rename back and resolve relations
 * to their natural keys. Faithfulness is verifiable by diffing bundle output against generated/*.json.
 *
 * Coverage this pass: the WorldMap subsystem (map/*.json) + the two trivial leaves terrains /
 * unit-combats. TODO(next): tech (techs + overlays), TerrainRegistry detail (features/bonuses/
 * improvements/routes), units, buildings, recipes/housing/tier1, feasts, region-earth-map, human-names.
 */

// `any` on the UID: these are runtime-valid content-type UIDs, but Strapi's documents() wants a UID
// *literal* type it can't infer from a computed string, so we opt out of that overload's typing.
const uid = (n: string): any => `api::${n}.${n}`;
/** Drop null/undefined so a projected record matches the committed JSON's present-keys (clean diff). */
const clean = (o: any) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null));

/** Page through every row of a collection (draftAndPublish is off, so no status juggling). */
async function all(u: any, opts: any = {}): Promise<any[]> {
  const out: any[] = [];
  const PAGE = 2000;
  for (let start = 0; ; start += PAGE) {
    const batch = await strapi.documents(u).findMany({ ...opts, start, limit: PAGE });
    out.push(...batch);
    if (batch.length < PAGE) break;
  }
  return out;
}

export default {
  async build() {
    const [meta, resources] = await Promise.all([this.meta(), this.resources()]);
    return { meta, resources };
  },

  async meta() {
    const mv: any = await strapi.documents(uid('map-version')).findFirst({});
    return { mapVersion: mv?.mapVersion ?? null, contentVersion: mv?.contentVersion ?? null };
  },

  async resources() {
    const [countries, cultures, religions, tradegoods, areas, regions, superregions,
           provinces, adjacencies, edges, portals, terrains, unitCombats] = await Promise.all([
      this.countries(), this.cultures(), this.religions(), this.tradegoods(),
      this.areas(), this.regions(), this.superregions(), this.provinces(),
      this.adjacencies(), this.edges(), this.portals(), this.terrains(), this.unitCombats(),
    ]);
    return {
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
      '/terrains.json': terrains,
      '/unit-combats.json': unitCombats,
    };
  },

  // ── relation-free leaves ──
  async countries() {
    return (await all(uid('country'), { fields: ['tag', 'name', 'color'] }))
      .map((r) => clean({ tag: r.tag, name: r.name, color: r.color }));
  },
  async cultures() {
    return (await all(uid('culture'), { fields: ['key', 'name', 'group', 'color'] }))
      .map((r) => clean({ key: r.key, name: r.name, group: r.group, color: r.color }));
  },
  async religions() {
    return (await all(uid('religion'), { fields: ['key', 'name', 'group', 'color'] }))
      .map((r) => clean({ key: r.key, name: r.name, group: r.group, color: r.color }));
  },
  async tradegoods() {
    return (await all(uid('trade-good'), { fields: ['key', 'name', 'color', 'category'] }))
      .map((r) => clean({ key: r.key, name: r.name, color: r.color, category: r.category }));
  },
  async terrains() {
    return (await all(uid('terrain'), { fields: ['key', 'yields', 'found', 'buildModifier', 'healthPercent', 'movement'] }))
      .map((r) => clean({ type: r.key, yields: r.yields, bFound: r.found, buildModifier: r.buildModifier, healthPercent: r.healthPercent, movement: r.movement }));
  },
  async unitCombats() {
    return (await all(uid('combat-class'), { fields: ['key', 'name', 'signatureSkill', 'categoryButton', 'earlyWithdrawChange', 'tauntChange', 'dodgeModifierChange', 'damageModifierChange', 'precisionModifierChange', 'captureResistanceModifierChange', 'forMilitary'] }))
      .map((r) => clean({
        id: r.key, name: r.name, signatureSkill: r.signatureSkill, categoryButton: r.categoryButton,
        iEarlyWithdrawChange: r.earlyWithdrawChange, iTauntChange: r.tauntChange,
        iDodgeModifierChange: r.dodgeModifierChange, iDamageModifierChange: r.damageModifierChange,
        iPrecisionModifierChange: r.precisionModifierChange, iCaptureResistanceModifierChange: r.captureResistanceModifierChange,
        bForMilitary: r.forMilitary,
      }));
  },

  // ── geography hierarchy (reverse the m2m relations to natural-key lists) ──
  async areas() {
    return (await all(uid('area'), { fields: ['key', 'name'], populate: { provinces: { fields: ['provinceId'] } } }))
      .map((r) => clean({ key: r.key, name: r.name, provinces: (r.provinces || []).map((p: any) => p.provinceId) }));
  },
  async regions() {
    return (await all(uid('region'), { fields: ['key', 'name'], populate: { areas: { fields: ['key'] } } }))
      .map((r) => clean({ key: r.key, name: r.name, areas: (r.areas || []).map((a: any) => a.key) }));
  },
  async superregions() {
    return (await all(uid('super-region'), { fields: ['key', 'name'], populate: { regions: { fields: ['key'] } } }))
      .map((r) => clean({ key: r.key, name: r.name, regions: (r.regions || []).map((x: any) => x.key) }));
  },

  // ── the province hub (reverse relations to natural keys; neighbors → province ids) ──
  async provinces() {
    const rows = await all(uid('province'), {
      fields: ['provinceId', 'name', 'latitude', 'longitude', 'plots', 'waterPlots', 'type', 'continent', 'realm', 'winter', 'monsoon', 'climate', 'baseTax', 'baseProduction', 'baseManpower', 'city'],
      populate: {
        owner: { fields: ['tag'] }, controller: { fields: ['tag'] },
        culture: { fields: ['key'] }, religion: { fields: ['key'] }, tradeGood: { fields: ['key'] },
        area: { fields: ['key'] }, region: { fields: ['key'] }, neighbors: { fields: ['provinceId'] },
      },
    });
    return rows.map((r) => clean({
      id: r.provinceId, name: r.name, lat: num(r.latitude), lon: num(r.longitude),
      plots: r.plots, waterPlots: r.waterPlots, type: r.type,
      region: r.region?.key, area: r.area?.key, continent: r.continent, realm: r.realm,
      winter: r.winter, monsoon: r.monsoon, climate: r.climate,
      owner: r.owner?.tag, controller: r.controller?.tag,
      culture: r.culture?.key, religion: r.religion?.key, trade_goods: r.tradeGood?.key,
      base_tax: r.baseTax, base_production: r.baseProduction, base_manpower: r.baseManpower,
      city: r.city || undefined,
      neighbors: (r.neighbors || []).map((n: any) => n.provinceId).sort((a: number, b: number) => a - b),
    }));
  },

  // ── geometry sidecars ──
  async adjacencies() {
    return (await all(uid('adjacency'), { fields: ['type', 'comment'], populate: { from: { fields: ['provinceId'] }, to: { fields: ['provinceId'] } } }))
      .map((r) => clean({ from: r.from?.provinceId, to: r.to?.provinceId, type: r.type ?? '', comment: r.comment }));
  },
  async edges() {
    return (await all(uid('province-edge'), { fields: ['provinceId', 'km'] }))
      .map((r) => clean({ id: r.provinceId, km: r.km }));
  },
  async portals() {
    return (await all(uid('province-portal'), { fields: ['provinceId', 'portals'] }))
      .map((r) => clean({ id: r.provinceId, portals: r.portals }));
  },
};

/** decimal columns come back as strings from some drivers — coerce to number for lat/lon. */
function num(v: any) {
  return v === null || v === undefined ? undefined : typeof v === 'string' ? parseFloat(v) : v;
}
