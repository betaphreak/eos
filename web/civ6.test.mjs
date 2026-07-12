// Tests for the Civ6 art resolver — run with `node --test`.
// Three layers: (1) pure mapping-table coverage (always runs, no depot needed); (2) resolution against
// the real depot (skipped when .civ6-cache is absent); (3) graceful-fallback in a subprocess with a
// bogus CIV6_CACHE_DIR (proves every resolver returns null with no depot). See docs/civ6-art-replacement.md.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import * as civ6 from './civ6.mjs';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const CIV6_URL = new URL('./civ6.mjs', import.meta.url).href;   // file:// specifier for subprocess import

// ---- (1) mapping tables (hermetic) --------------------------------------------------------------

test('TERRAIN_FOLD covers all 16 land + 9 synthetic terrains, all aliases valid', () => {
  const keys = Object.keys(civ6.TERRAIN_FOLD);
  assert.equal(keys.length, 25, 'expected 16 land + 9 synthetic');
  for (const [k, alias] of Object.entries(civ6.TERRAIN_FOLD))
    assert.ok(civ6.GROUND_TEX[alias], `${k} → unknown ground alias "${alias}"`);
  // spot-check the Varied fold decisions
  assert.equal(civ6.TERRAIN_FOLD.TERRAIN_SCRUB, 'Plains');
  assert.equal(civ6.TERRAIN_FOLD.TERRAIN_MUDDY, 'Marsh');
  assert.equal(civ6.TERRAIN_FOLD.TERRAIN_BADLAND, 'Cliff');
  assert.equal(civ6.TERRAIN_FOLD.TERRAIN_BARREN, 'Desert');
  assert.equal(civ6.TERRAIN_FOLD.TERRAIN_TAIGA, 'Tundra');
});

test('BONUS_TO_CIV6 is exactly the 45 Civ6-served bonuses (Mixed policy)', () => {
  const keys = Object.keys(civ6.BONUS_TO_CIV6);
  assert.equal(keys.length, 45, '34 Direct + 5 Close + 6 collapse-approved');
  assert.ok(keys.every(k => k.startsWith('BONUS_')));
  // collapse decisions
  for (const g of ['BONUS_RUBIES', 'BONUS_SAPPHIRES', 'BONUS_TURQUOISE'])
    assert.equal(civ6.BONUS_TO_CIV6[g], 'Diamonds');
  for (const s of ['BONUS_CLAM', 'BONUS_LOBSTER', 'BONUS_SHRIMP'])
    assert.equal(civ6.BONUS_TO_CIV6[s], 'Crab');
  // close matches
  assert.equal(civ6.BONUS_TO_CIV6.BONUS_ELEPHANTS, 'Ivory');
  assert.equal(civ6.BONUS_TO_CIV6.BONUS_GRAPES, 'Wine');
  // kept-on-C2C approximates must NOT be present
  for (const c2c of ['BONUS_HENNA', 'BONUS_INDIGO', 'BONUS_MUREX', 'BONUS_WALRUS', 'BONUS_BEAVERS', 'BONUS_RABBIT', 'BONUS_ALMONDS'])
    assert.equal(civ6.BONUS_TO_CIV6[c2c], undefined, `${c2c} should stay on C2C`);
});

test('FEATURE_OVERLAY covers the 7 Civ6 features and excludes C2C-only flora', () => {
  assert.equal(civ6.FEATURE_OVERLAY.FEATURE_FOREST, 'Features_Forest_Visible');
  assert.equal(civ6.FEATURE_OVERLAY.FEATURE_SWAMP, 'Features_Marsh_Visible');
  for (const c2c of ['FEATURE_BAMBOO', 'FEATURE_CACTUS', 'FEATURE_VERY_TALL_GRASS', 'FEATURE_SAVANNA'])
    assert.equal(civ6.FEATURE_OVERLAY[c2c], undefined, `${c2c} should stay a C2C billboard`);
});

test('ATLAS_CELL cells are valid (0-63) and keyed by real Civ6 resources', () => {
  const resources = new Set(Object.values(civ6.BONUS_TO_CIV6));
  for (const [res, cell] of Object.entries(civ6.ATLAS_CELL)) {
    assert.ok(Number.isInteger(cell) && cell >= 0 && cell < 64, `${res} cell ${cell} out of range`);
    assert.ok(resources.has(res), `${res} not referenced by any bonus`);
  }
});

test('IMPROVEMENT_OVERLAY is just Farm/Mine/Quarry', () => {
  assert.deepEqual(Object.keys(civ6.IMPROVEMENT_OVERLAY).sort(),
    ['IMPROVEMENT_FARM', 'IMPROVEMENT_MINE', 'IMPROVEMENT_QUARRY']);
});

// ---- (2) resolution against the real depot (skipped when absent) --------------------------------

test('resolves real depot entities to .dds files', { skip: civ6.available() ? false : 'no .civ6-cache mounted' }, () => {
  const grass = civ6.terrainGround('TERRAIN_GRASSLAND');
  assert.ok(grass && grass.toLowerCase().endsWith('.dds'), 'grassland ground');
  assert.ok(civ6.featureOverlay('FEATURE_FOREST'), 'forest overlay');
  assert.ok(civ6.improvementOverlay('IMPROVEMENT_FARM'), 'farm overlay');
  assert.ok(civ6.fontIcons(), 'FontIcons sheet');

  // resource: atlas cell preferred (Wheat = cell 9)
  const wheat = civ6.resourceIcon('BONUS_WHEAT');
  assert.ok(wheat && wheat.atlas && wheat.cell === 9 && wheat.resource === 'Wheat', 'wheat atlas cell');
  const banana = civ6.resourceIcon('BONUS_BANANA');
  assert.ok(banana && banana.atlas && banana.cell === 0, 'banana atlas cell 0');
  // collapse: shellfish share the Crab cell (3)
  assert.equal(civ6.resourceIcon('BONUS_SHRIMP').cell, 3, 'shrimp → Crab cell');
  // Silk has no atlas cell but a loose sprite → { tex }
  const silk = civ6.resourceIcon('BONUS_SILK');
  assert.ok(silk && silk.tex && silk.resource === 'Silk', 'silk loose sprite');
  // Civ6-mapped but neither atlas nor loose (Gold) → null → C2C
  assert.equal(civ6.resourceIcon('BONUS_GOLD_ORE'), null, 'gold → C2C');
  // a C2C-only bonus has no Civ6 mapping
  assert.equal(civ6.resourceIcon('BONUS_ALMONDS'), null, 'almonds → C2C');

  // water terrain has no ground tile
  assert.equal(civ6.terrainGround('TERRAIN_SEA'), null, 'water → no ground');
  // C2C-only feature → null
  assert.equal(civ6.featureOverlay('FEATURE_CACTUS'), null, 'cactus → C2C billboard');
  // ice tile → the Civ6 icecaps sprite (Phase 4 water)
  const ice = civ6.iceTile();
  assert.ok(ice && ice.toLowerCase().endsWith('features_icecaps_visible.dds'), 'ice tile → Civ6 icecaps');
});

// ---- (3) graceful fallback with no depot (subprocess, bogus CIV6_CACHE_DIR) ----------------------

test('every resolver returns null / false when the depot is absent', () => {
  const script = `
    import * as civ6 from ${JSON.stringify(CIV6_URL)};
    const r = {
      available: civ6.available(),
      terrain: civ6.terrainGround('TERRAIN_GRASSLAND'),
      feature: civ6.featureOverlay('FEATURE_FOREST'),
      resource: civ6.resourceIcon('BONUS_WHEAT'),
      improvement: civ6.improvementOverlay('IMPROVEMENT_FARM'),
      fontIcons: civ6.fontIcons(),
      ice: civ6.iceTile(),
    };
    process.stdout.write(JSON.stringify(r));
  `;
  const out = execFileSync(process.execPath, ['--input-type=module', '-e', script],
    { env: { ...process.env, CIV6_CACHE_DIR: HERE + '__no_such_depot__' } }).toString();
  const r = JSON.parse(out);
  assert.equal(r.available, false);
  assert.equal(r.terrain, null);
  assert.equal(r.feature, null);
  assert.equal(r.resource, null);
  assert.equal(r.improvement, null);
  assert.equal(r.fontIcons, null);
  assert.equal(r.ice, null);
});
