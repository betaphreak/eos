// seed.js — Phase 3 seeder for the "Studio as the engine's authoritative content store" rebuild
// (docs/studio-datamodel-rebuild-plan.md). Reads the engine's committed exporter JSON and upserts it
// into Strapi via the Document Service (in-process — boots Strapi programmatically, no HTTP).
//
// CommonJS on purpose: booting Strapi through ESM (`node seed.mjs`) trips ERR_UNSUPPORTED_DIR_IMPORT
// on @strapi/core's lodash/fp import; require() resolves Strapi's CJS build, which is fine.
//
// Idempotent: matches on natural key (tag/key/id/…), updates scalars if present, creates if absent,
// then relinks relations two-phase. Re-running converges. Run from studio/:  node scripts/seed.js
//
// Scope (this pass, per the locked decisions): core model. DEFERRED — place-name (GeoNames) and the
// full all-race name-pool (needs the Java RaceNameGenerator); era-modifiers/rank-ladder single types
// (no JSON source yet). name-pool seeds human + harimari only.

const { createStrapi, compileStrapi } = require('@strapi/strapi');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const RES = join(__dirname, '..', '..', 'civstudio-engine', 'src', 'main', 'resources');
const GEN = join(RES, 'generated');
const MAP = join(GEN, 'map');

// ── helpers ──────────────────────────────────────────────────────────────────
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

/** Run async fn over items with bounded concurrency. */
async function pool(items, size, fn) {
  let i = 0;
  await Promise.all(
    Array.from({ length: Math.min(size, items.length) }, async () => {
      while (i < items.length) {
        const idx = i++;
        await fn(items[idx], idx);
      }
    }),
  );
}

// ── seeder ───────────────────────────────────────────────────────────────────
async function main() {
  const app = await createStrapi(await compileStrapi()).load();
  app.log.level = 'error'; // quiet the request logger during bulk work
  const keyMaps = {}; // uid → Map(naturalKey → documentId)

  /**
   * Idempotent natural-key upsert of `rows` into `uid`.
   * @param uid       content-type uid, e.g. 'api::country.country'
   * @param keyField  Strapi attribute holding the natural key (unique)
   * @param rows      source records
   * @param toData    (row) => Strapi data object for the SCALAR fields (no relations)
   * @param keyOf     (row) => natural key value (defaults to row[keyField])
   * @returns Map(naturalKey → documentId)
   */
  async function upsert(uid, keyField, rows, toData, keyOf = (r) => r[keyField]) {
    // preload existing docs → key→documentId (page through; draftAndPublish is off)
    const existing = new Map();
    const PAGE = 1000;
    for (let start = 0; ; start += PAGE) {
      const batch = await app.documents(uid).findMany({ fields: [keyField], start, limit: PAGE });
      for (const d of batch) existing.set(d[keyField], d.documentId);
      if (batch.length < PAGE) break;
    }

    const map = new Map();
    let created = 0;
    let updated = 0;
    await pool(rows, 20, async (row) => {
      const key = keyOf(row);
      const data = { [keyField]: key, ...toData(row) };
      const docId = existing.get(key);
      if (docId) {
        await app.documents(uid).update({ documentId: docId, data });
        map.set(key, docId);
        updated++;
      } else {
        const doc = await app.documents(uid).create({ data });
        map.set(key, doc.documentId);
        created++;
      }
    });
    keyMaps[uid] = map;
    console.log(`[seed] ${uid.padEnd(28)} ${String(rows.length).padStart(5)} rows  (+${created} ~${updated})`);
    return map;
  }

  // ── Phase A: relation-free leaf types ───────────────────────────────────────
  await upsert('api::country.country', 'tag', readJson(join(MAP, 'countries.json')), (r) => ({
    name: r.name,
    color: r.color,
  }));

  await upsert('api::culture.culture', 'key', readJson(join(MAP, 'cultures.json')), (r) => ({
    name: r.name,
    group: r.group,
    color: r.color,
  }));

  await upsert('api::religion.religion', 'key', readJson(join(MAP, 'religions.json')), (r) => ({
    name: r.name,
    group: r.group,
    color: r.color,
  }));

  await upsert('api::trade-good.trade-good', 'key', readJson(join(MAP, 'tradegoods.json')), (r) => ({
    name: r.name,
    color: r.color,
    category: r.category,
  }));

  await upsert('api::terrain.terrain', 'key', readJson(join(GEN, 'terrains.json')), (r) => ({
    yields: r.yields,
    found: r.bFound,
    buildModifier: r.buildModifier,
    healthPercent: r.healthPercent,
    movement: r.movement,
  }), (r) => r.type);

  await upsert('api::combat-class.combat-class', 'key', readJson(join(GEN, 'unit-combats.json')), (r) => ({
    name: r.name,
    signatureSkill: r.signatureSkill,
    categoryButton: r.categoryButton,
    earlyWithdrawChange: r.iEarlyWithdrawChange,
    tauntChange: r.iTauntChange,
    dodgeModifierChange: r.iDodgeModifierChange,
    damageModifierChange: r.iDamageModifierChange,
    precisionModifierChange: r.iPrecisionModifierChange,
    captureResistanceModifierChange: r.iCaptureResistanceModifierChange,
    forMilitary: r.bForMilitary,
  }), (r) => r.id);

  console.log('[seed] Phase A (leaf types) done.');
  await app.destroy();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
