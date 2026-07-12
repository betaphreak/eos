// Local resolver for the Civilization VI SDK art depot (the .civ6-cache junction). The Civ6 sibling of
// web/civ4.mjs — but far simpler: the depot is a local, machine-specific, dev-only mount, so this is a
// synchronous path resolver with NO network and NO cache. When the depot is absent every resolver
// returns null, so the bakers degrade to their C2C fallbacks (web/civ4.mjs). This is the intended
// posture — CI has no depot, so committed web/assets are the ship artifact and Civ6 rebakes are manual.
//
// The mapping tables here are the single source of truth for the Civ6-first / C2C-fallback policy and
// mirror docs/civ6-assets.md §5/§8 + docs/civ6-art-replacement.md. Keep the two in sync.
//
//   import * as civ6 from './civ6.mjs';
//   if (civ6.available()) {
//     const dds = civ6.terrainGround('TERRAIN_GRASSLAND');    // absolute .dds path or null
//     const ov  = civ6.featureOverlay('FEATURE_FOREST');
//     const ic  = civ6.resourceIcon('BONUS_WHEAT');           // { tex } | { atlas, resource } | null
//   }

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');

/** Depot root — the .civ6-cache junction, overridable for tests / alternate mounts. */
export const CIV6_ROOT = process.env.CIV6_CACHE_DIR || path.join(ROOT, '.civ6-cache');

// Texture search path: base game first, then the DLC/expansion pantries (Amber/Olives/Turtles etc.).
const TEXTURE_DIRS = [
  'Civ6/pantry/Textures',
  'Civ6/DLC/Expansion1/pantry/Textures',
  'Civ6/DLC/Expansion2/pantry/Textures',
  'Civ6/DLC/Shared/pantry/Textures',
].map(d => path.join(CIV6_ROOT, ...d.split('/')));

/** True when the base-game texture pantry is present (i.e. the depot is mounted). */
export function available() {
  try { return fs.statSync(TEXTURE_DIRS[0]).isDirectory(); } catch { return false; }
}

// case-insensitive listing cache: dir -> Map(lowercaseFilename -> realFilename)
const _dirCache = new Map();
function listing(dir) {
  let m = _dirCache.get(dir);
  if (m) return m;
  m = new Map();
  try { for (const n of fs.readdirSync(dir)) m.set(n.toLowerCase(), n); } catch { /* absent dir */ }
  _dirCache.set(dir, m);
  return m;
}

/**
 * Absolute path to a Civ6 texture `<objectName>.dds` (case-insensitive), searching base then DLC
 * pantries; null if absent (or the depot is unmounted). The `.dds` extension is optional in the arg.
 */
export function resolveTexture(objectName) {
  if (!objectName) return null;
  const file = objectName.replace(/\.dds$/i, '').toLowerCase() + '.dds';
  for (const dir of TEXTURE_DIRS) {
    const real = listing(dir).get(file);
    if (real) return path.join(dir, real);
  }
  return null;
}

// ---- Terrain (docs/civ6-assets.md §5, Varied fold) ----------------------------------------------
// Civ6 in-world ground alias -> actual texture object (lit *_B where it exists, else FOW_Ground_*).
export const GROUND_TEX = {
  Grass: 'Grass_B', GrassDark: 'Grass_Dark_B', Plains: 'FOW_Ground_Plains_2k',
  Desert: 'FOW_Ground_Desert', Tundra: 'FOW_Ground_Tundra', Snow: 'FOW_Ground_Snow',
  Ice: 'FOW_Ground_Ice', Marsh: 'FOW_Ground_Marsh', Floodplains: 'FOW_Ground_Floodplains',
  Salt: 'FOW_Ground_Salt_Base', Cliff: 'Cliff_Rocks_B',
};

// CivStudio terrain key -> Civ6 ground alias. 16 land (Varied fold) + 9 synthetic (nearest ground,
// recolored by the bake). Water terrains are absent (color-only, no ground tile).
export const TERRAIN_FOLD = {
  TERRAIN_GRASSLAND: 'Grass', TERRAIN_LUSH: 'GrassDark', TERRAIN_PLAINS: 'Plains', TERRAIN_SCRUB: 'Plains',
  TERRAIN_MARSH: 'Marsh', TERRAIN_MUDDY: 'Marsh', TERRAIN_ROCKY: 'Cliff', TERRAIN_BADLAND: 'Cliff',
  TERRAIN_JAGGED: 'Cliff', TERRAIN_BARREN: 'Desert', TERRAIN_DESERT: 'Desert', TERRAIN_DUNES: 'Desert',
  TERRAIN_SALT_FLATS: 'Salt', TERRAIN_TAIGA: 'Tundra', TERRAIN_TUNDRA: 'Tundra', TERRAIN_PERMAFROST: 'Snow',
  // synthetic (no Civ6 base) -> nearest ground; the bake recolors to the terrain's display color
  TERRAIN_CAVERN: 'Cliff', TERRAIN_MUSHROOM_FOREST: 'GrassDark', TERRAIN_ANCIENT_FOREST: 'GrassDark',
  TERRAIN_GLADEWAY: 'GrassDark', TERRAIN_FEY_GLADEWAY: 'GrassDark', TERRAIN_BLOODGROVES: 'GrassDark',
  TERRAIN_SHADOW_SWAMP: 'Marsh', TERRAIN_GLACIER: 'Snow', TERRAIN_URBAN: 'Cliff',
};

/** Civ6 in-world ground texture for a CivStudio terrain key; null for water/unmapped (→ keep C2C). */
export function terrainGround(terrainKey) {
  const alias = TERRAIN_FOLD[terrainKey];
  return alias ? resolveTexture(GROUND_TEX[alias]) : null;
}

// ---- Features (docs/civ6-assets.md §5, flat SV overlays) -----------------------------------------
// Civ6-covered CivStudio features -> the flat strategic-view overlay. C2C-only flora (BAMBOO, CACTUS,
// VERY_TALL_GRASS, SAVANNA) are intentionally absent → featureOverlay() returns null → C2C billboards.
export const FEATURE_OVERLAY = {
  FEATURE_FOREST: 'Features_Forest_Visible', FEATURE_FOREST_ANCIENT: 'Features_Forest_Visible',
  FEATURE_JUNGLE: 'Features_Jungle_Visible', FEATURE_SWAMP: 'Features_Marsh_Visible',
  FEATURE_OASIS: 'Features_Oasis_Visible', FEATURE_FLOOD_PLAINS: 'Features_Floodplains_Visible',
  FEATURE_ICE: 'Features_Icecaps_Visible',
};

/** Civ6 flat feature-overlay texture for a CivStudio feature key; null → keep the C2C billboard bake. */
export function featureOverlay(featureKey) {
  return resolveTexture(FEATURE_OVERLAY[featureKey]);
}

// ---- Bonuses / resources (docs/civ6-assets.md §8, Mixed collapse) --------------------------------
// The 45 C2C bonuses served by Civ6 -> the Civ6 resource sprite name (used as Resources_<name>_Visible
// when a loose sprite exists, else as the atlas cell id in Phase 2). The other 61 bonuses are absent
// here → resourceIcon() returns null → keep the C2C GameFont cell.
export const BONUS_TO_CIV6 = {
  // Direct (34)
  BONUS_AMBER: 'Amber', BONUS_BANANA: 'Bananas', BONUS_COAL: 'Coal', BONUS_COCOA: 'Cocoa',
  BONUS_COFFEE: 'Coffee', BONUS_COPPER_ORE: 'Copper', BONUS_COTTON: 'Cotton', BONUS_COW: 'Cattle',
  BONUS_CRAB: 'Crab', BONUS_DEER: 'Deer', BONUS_DIAMOND: 'Diamonds', BONUS_FISH: 'Fish',
  BONUS_GOLD_ORE: 'Gold', BONUS_HORSE: 'Horse', BONUS_INCENSE: 'Incense', BONUS_IRON_ORE: 'Iron',
  BONUS_JADE: 'Jade', BONUS_MARBLE: 'Marble', BONUS_OIL: 'Oil', BONUS_OLIVES: 'Olives',
  BONUS_PEARLS: 'Pearls', BONUS_RICE: 'Rice', BONUS_SALT: 'Salt', BONUS_SHEEP: 'Sheep',
  BONUS_SILK: 'Silk', BONUS_SILVER_ORE: 'Silver', BONUS_SPICES: 'Spices', BONUS_STONE: 'Stone',
  BONUS_SUGAR: 'Sugar', BONUS_TEA: 'Tea', BONUS_TOBACCO: 'Tobacco', BONUS_URANIUM: 'Uranium',
  BONUS_WHALE: 'Whales', BONUS_WHEAT: 'Wheat',
  // Close (5)
  BONUS_ANCIENT_RELICS: 'AntiquitySite', BONUS_BAUXITE_ORE: 'Aluminum', BONUS_ELEPHANTS: 'Ivory',
  BONUS_GRAPES: 'Wine', BONUS_LEMONS: 'Citrus',
  // Collapse-approved approximates (6): gems -> Diamonds, shellfish -> Crab
  BONUS_RUBIES: 'Diamonds', BONUS_SAPPHIRES: 'Diamonds', BONUS_TURQUOISE: 'Diamonds',
  BONUS_CLAM: 'Crab', BONUS_LOBSTER: 'Crab', BONUS_SHRIMP: 'Crab',
};

/**
 * Civ6 icon source for a C2C bonus, per the Mixed policy. Returns:
 *   { tex, resource }            — a loose per-resource sprite (Resources_<X>_Visible.dds), when present;
 *   { atlas, resource }          — the Resources256.dds atlas (cell index resolved in Phase 2); else
 *   null                         — no Civ6 mapping (or depot absent) → keep the C2C GameFont cell.
 */
export function resourceIcon(bonusKey) {
  const resource = BONUS_TO_CIV6[bonusKey];
  if (!resource) return null;
  const tex = resolveTexture(`Resources_${resource}_Visible`);
  if (tex) return { tex, resource };
  const atlas = resolveTexture('Resources256');
  return atlas ? { atlas, resource } : null;
}

// ---- Improvements (docs/civ6-assets.md §5) -------------------------------------------------------
// Only Farm/Mine/Quarry have a flat strategic-view overlay; the rest are 3D-only (deferred).
export const IMPROVEMENT_OVERLAY = {
  IMPROVEMENT_FARM: 'StrategicView_Improvements_Farm',
  IMPROVEMENT_MINE: 'StrategicView_Improvements_Mine',
  IMPROVEMENT_QUARRY: 'StrategicView_Improvements_Quarry',
};

/** Civ6 flat improvement-overlay texture for a CivStudio improvement key; null → unarted/deferred. */
export function improvementOverlay(impKey) {
  return resolveTexture(IMPROVEMENT_OVERLAY[impKey]);
}

// ---- Yield symbols (docs/civ6-art-replacement.md §C) --------------------------------------------
/** The Civ6 inline-symbol sheet (FontIcons.dds); per-symbol cell layout is resolved in Phase C. */
export function fontIcons() {
  return resolveTexture('FontIcons');
}
