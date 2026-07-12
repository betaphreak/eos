# Plan: replace all baked assets with Civ6 art

**Goal.** Rebake CivStudio's web art from the **Civ VI SDK depot** instead of Civ4/C2C, under a
**Civ6-first / C2C-fallback (per entity)** policy, with **multiple LoD levels** so the continuous-zoom
band spine can cross-fade detail seamlessly. Companion to the reference [`civ6-assets.md`](civ6-assets.md)
(depot layout, addressing, the full bonus→resource map) and [`civ4-files.md`](civ4-files.md) (the C2C
source side being replaced). The current bake is described in
[`ported-terrain-art-system.md`](ported-terrain-art-system.md).

**Why now.** Feasibility is proven: `web/dds.mjs` decodes the depot's uncompressed DDS
([`civ6-assets.md` §2a], unit-tested), the addressing scheme resolves entities to loose `.dds`
([§2b/§4]), and the depot ships multi-resolution variants ideal for LoD.

## Decisions (locked with owner, 2026-07-12)

| Question | Decision |
| --- | --- |
| Terrain source | **In-world 2k ground** (`Grass_B` / `FOW_Ground_*`), recolored to each terrain's display color |
| Terrain fold (ambiguous 5) | **Varied**: SCRUB→Plains, MUDDY→Marsh, BADLAND→Cliff, BARREN→Desert, TAIGA→Tundra |
| Features | **Flat SV overlays** (`Features_*_Visible.dds`) for Civ6-covered features; C2C billboards kept for C2C-only flora |
| Scope | Terrain+water, bonus/resource icons, **yield symbols (new)**, **newly-baked improvements+routes** |
| Resource collapse (A-tier) | **Mixed**: collapse gems→Diamonds & shellfish→Crabs; keep henna/indigo/murex, walrus, beavers/rabbit on C2C |
| LoD | **Multi-LoD from the start** (2–3 sizes/sheet + manifest LoD descriptors + band cross-fade) |
| Techs | **Unchanged** — stay C2C (no per-tech icons in the depot) |

## ⚠️ Gates before shipping

**The Civ6 bake is local-dev only.** The depot is a machine-specific junction (`.civ6-cache`), absent
   from CI. So `propagate-c2c.yml` and any CI **cannot** rebake Civ6 art — the committed `web/assets` are
   the ship artifact and Civ6 rebakes are **manual/local**. Bakers must degrade gracefully to C2C when the
   depot is absent (so a no-depot machine still builds, all-C2C).

---

## Architecture

### 1. `web/civ6.mjs` — the resolver (new; sibling to `web/civ4.mjs`)
Unlike `civ4.mjs` (GitHub fetch + cache), Civ6 art is a **local** depot, so this is a thin synchronous
resolver — no network, no cache.

- `CIV6_ROOT = process.env.CIV6_CACHE_DIR || '<repo>/.civ6-cache'`; `available()` = does
  `Civ6/pantry/Textures` exist. When `!available()`, **every resolver returns `null`** → bakers fall back.
- `resolveTexture(objectName)` → absolute `.dds` path or null (case-insensitive; searches base
  `Civ6/pantry/Textures` then `DLC/*/pantry/Textures` for expansion entities).
- Entity resolvers, each backed by an explicit mapping table (below):
  - `terrainGround(terrainKey)` → ground `.dds` (or null for water/C2C-only synthetic).
  - `featureOverlay(featureKey)` → `Features_<X>_Visible.dds` (or null → C2C billboard).
  - `resourceIcon(bonusKey)` → `Resources_<Name>_Visible.dds` **or** `{atlas, cell}` (or null → C2C GameFont cell).
  - `yieldSymbol(sym)` → a `FontIcons.dds` cell rect.
  - `improvementOverlay(impKey)` → `StrategicView_Improvements_<X>.dds` (or null).
- **Mapping tables are the single source of truth** and mirror `civ6-assets.md` §5/§8 — colocated here so
  the bake and the doc can't drift.

### 2. Multi-LoD manifest schema
Extend the web-asset manifest (`civstudio-server/src/main/resources/map/web-asset-manifest.json`,
assembled by `WorldBundle`) so each art sheet carries **an ordered LoD list**, smallest→largest:

```jsonc
"bonusIcons": { "index": { "BONUS_WHEAT": 12, ... },
  "lods": [ {"src":"icons/bonus-icons@32.webp","cell":32,"cols":16},
            {"src":"icons/bonus-icons@64.webp","cell":64,"cols":16},
            {"src":"icons/bonus-icons@256.webp","cell":256,"cols":16} ] }
"terrainTiles": { "cols": {...}, "lods":[ {"src":"terrain/terrain-tiles@512.webp","tile":512}, {"src":".../@2k.webp","tile":2048} ] }
```
`index`/`cols` are shared across LoDs (same layout, different resolution). Backward-compatible: keep a
top-level `src` = the mid LoD so any un-migrated reader still works.

### 3. Frontend LoD selection (band-keyed, cross-faded)
In the layer draw path (`web/js/plots.mjs` + the band spine `web/js/bands.mjs`/`layers.mjs`):
- Add `pickLod(descriptor, zoom)` → choose the LoD whose native px best matches the on-screen tile px.
- Preload adjacent LoDs; at a band boundary, **alpha cross-fade** the outgoing→incoming LoD over a short
  zoom interval so the swap is invisible (per `zoom-bands.md`). One helper reused by every layer.

---

## Per-category plan

### A. Terrain (`terrain/terrain-tiles.webp`, `terrain.webp`) — Civ6-first
Reuse the **existing** recolor pipeline (`bakeTerrainTiles`, `terrainDisplayColors()` in `web/build.mjs`),
swapping only the **source texture**: instead of the C2C detail `.dds` from `terrain-art.json`, take the
Civ6 ground from `civ6.terrainGround(key)`, crop/tile to each LoD size, and recolor so the tile mean = the
terrain's display color (preserving luminance detail so the ground texture reads through the tint).
- **Fold table** (16 land → Civ6 ground): GRASSLAND→Grass, LUSH→Grass_Dark, PLAINS→Plains, **SCRUB→Plains**,
  MARSH→Marsh, **MUDDY→Marsh**, ROCKY→Cliff, **BADLAND→Cliff**, JAGGED→Cliff, **BARREN→Desert**,
  DESERT→Desert, DUNES→Desert, SALT_FLATS→Salt, **TAIGA→Tundra**, TUNDRA→Tundra, PERMAFROST→Snow.
- **Synthetic (9)**: map to the nearest Civ6 ground + recolor (CAVERN→Cliff, MUSHROOM/ANCIENT/GLADEWAY/
  FEY/BLOODGROVES→Grass_Dark, SHADOW_SWAMP→Marsh, GLACIER→Snow, URBAN→Cliff) — same recolor-from-neighbor
  the current bake already does.
- **Water terrains** (8): color-only, unchanged (no ground tile).
- **LoD**: emit `@512` and `@2k` (FOW_Ground_*_2k is the native source; downscale for 512).

### B. Bonus / resource icons (`icons/bonus-icons.webp`) — Civ6-first (Mixed collapse)
Rebake per the final source split (§ below). For a Civ6-served bonus, slice the clean per-resource
`Resources_<Name>_Visible.dds` (icon-only, no class backing) via `civ6.resourceIcon`; for a C2C-served
bonus, keep the current GameFont cell (`bakeBonusIcons` path). One merged atlas keyed by `index[BONUS_*]`.
- **LoD**: `@32/@64/@256` (native Civ6 `Resources32/50/256.dds`; C2C cells upscaled to match).

### C. Yield symbols (NEW — `icons/yield-symbols.webp`)
New sheet from `FontIcons.dds` cells (production/gold/science/food/culture/faith…), the Civ6 analogue of
the GameFont HAMMER/GOLD/BEAKER glyphs. New manifest entry `yieldSymbols`. Optional: repoint the four
research beakers (`tech-beaker*.webp`) at the Civ6 science symbol; keep the recolor.

### D. Features (`trees/*.webp`) — hybrid: Civ6 flat overlay + C2C billboard
- **Civ6-covered features** (FOREST, FOREST_ANCIENT→Forest, JUNGLE, SWAMP→Marsh, OASIS, FLOOD_PLAINS, ICE)
  become **flat SV overlays**: bake `Features_<X>_Visible.dds` → `trees/feat-<x>.webp` + a manifest
  `featureOverlays` descriptor; a **new draw path** in `plots.mjs` blits one hex overlay per plot instead
  of `stampTrees()` billboard scatter.
- **C2C-only flora** (BAMBOO, CACTUS, VERY_TALL_GRASS, SAVANNA) **keep** the existing billboard bake +
  `stampTrees`. `treeGroupFor()` gains a branch: Civ6-overlay vs C2C-billboard per feature.
- **LoD**: overlays at 2 sizes (far/near); billboards unchanged.

### E. Water (`water/*.webp`)
- **Ice** → Civ6 `Features_Icecaps_Visible.dds` (Civ6-first).
- **River ribbon, sea, shore** → **keep C2C** (Civ6 rivers are edge decals, no tile equivalent; sea/shore
  ripple has no clean Civ6 flat source). No change beyond ice.

### F. Improvements + routes (NEW bake + NEW frontend layer)
Greenfield — not baked or drawn today. Civ6 flat SV overlays exist only for **Farm, Mine, Quarry** (+Harbor);
the rest are 3D-only.
- **Phase-in the 3 available**: bake `StrategicView_Improvements_{Farm,Mine,Quarry}.dds` →
  `improvements/*.webp` + manifest `improvementOverlays`; add a new `plots.mjs` improvement layer that
  draws the overlay on improved plots (`improvements.json` already carries the improvement type).
- **Gaps** (LUMBERMILL, PASTURE, WINERY, PLANTATION, COTTAGE/HAMLET/VILLAGE/TOWN, HUNTING_CAMP): no Civ6
  flat SV. Options — defer, render the `IMP_*` 3D albedo, or leave unarted. **Recommend defer** (log the
  gap, no silent omission) since the frontend improvement layer is itself new.
- **Routes**: Civ6 roads are decal-based (`RouteDoodads`/`RouteDecalMaterials`), not tiles; `route-models.json`
  is unbaked today. **Recommend defer** routes entirely this pass (unchanged: no pixels).

### G. Techs — unchanged
Stay C2C. No per-tech subject icons in the depot (`civ6-assets.md` §2b).

---

## Final baked-source split (bonus icons, Mixed policy)

**Civ6 icon (45)** = 34 Direct + 5 Close + gems (RUBIES, SAPPHIRES, TURQUOISE→Diamonds) + shellfish
(CLAM, LOBSTER, SHRIMP→Crabs). **C2C kept (61)** = the 48 with no Civ6 match + the distinctive
approximates kept on C2C (BEAVERS, BISON, DONKEY, HENNA, INDIGO, MUREX, MUSHROOMS, NATRON, RABBIT, RESIN,
SULPHUR, VANILLA, WALRUS). Full per-bonus table: `civ6-assets.md` §8.

---

## Phasing

- **Phase 0** ✅ — `web/civ6.mjs` resolver + mapping tables + graceful fallback; `web/civ6.test.mjs`.
  Manifest LoD schema designed here; the `pickLod`/cross-fade *frontend helper* is deferred to Phase 2
  (see Phase 1 note).
- **Phase 1** ✅ (bake) — Terrain (headline visual): Civ6 in-world ground recoloured to each terrain's
  display colour, all 25 land+synthetic sourced from Civ6, emitted as a **`@128/@256` LoD atlas** with
  `terrainTiles.lods` in the manifest (WebP's 16383px cap rules out a single-row 2k atlas). `src`/`tile`
  stay = the deep LoD, so the frontend renders it **unchanged**. **The frontend `pickLod`+cross-fade
  helper moved to Phase 2**: terrain tiles draw only at deep zoom, where the deep LoD is always the right
  pick, so LoD selection is near-invisible here — it's built and exercised against icons (which draw
  across every zoom band) instead.
- **Phase 2** ✅ (icons) — Bonus icons rebaked Civ6-first with **class-coloured backings on all 106**:
  39 use a Civ6 `Resources256` atlas cell (hand-identified — the depot ships no index; kept its own
  backing), 67 keep the C2C GameFont glyph composited onto a matching procedural class octagon
  (`civ6BackingColors` samples yellow/purple/red from the atlas; `bonusClass` → colour). Emitted as a
  `@32/@64` LoD atlas; `src`/`cell` = deep LoD so the frontend renders unchanged. Raw-pixel helpers
  extracted to `web/imgutil.mjs`. **Deferred**: the generic `pickLod`/cross-fade frontend helper (per-
  plot icons draw only at deep zoom, like terrain tiles — the LoD atlases are in place for when a layer
  that spans zoom bands needs them) and **yield symbols** (FontIcons.dds — needs a hand-authored cell
  map, like the resource atlas; a new capability, not a replacement).
- **Phase 3** ✅ — Features → flat Civ6 SV overlays (forest/forest_ancient/jungle/swamp→marsh/oasis),
  one 128² tile each, blitted to fill a featured plot (per-plot h-flip breaks tiling); C2C-only flora
  (bamboo/cactus/tall-grass/savanna) keeps billboards. New `featureOverlays` manifest key +
  `featureSprite` overlay branch. Gotcha fixed: new top-level manifest keys must be added to
  `WorldBundle`'s manifest→bundle allow-list. Verified live.
- **Phase 4** ✅ — Water (ice only). `bakeIceTile` is Civ6-first: it crops the opaque central 40% of
  `Features_Icecaps_Visible.dds` (a 256² hex icecap — pale cracked-ice centre, transparent corners),
  forces it opaque, and downsamples to a tileable **128² colour tile** (`water/ice.webp`), else falls
  back to the Civ4 `icepack_1024.dds`. New resolver `civ6.iceTile()`; the `{src,tile}` descriptor +
  `drawSeaIce` draw path are unchanged (river/sea/shore stay C2C — Civ6 rivers are edge decals). Verified
  in-app: the frontend loads the new 128² tile (centre pixel (203,231,246), the Civ6 ice-blue), the source
  `drawSeaIce` builds its floe `createPattern` from. **Note** — sea ice only renders on **coastal** polar
  seas (SEA/LAKE adjacent to land, e.g. *The Passage* 1739, *Fjordsbay* 1436); open-ocean seas (1577/1610)
  generate no plottable shelf, so `drawSeaIce` never fires there.
- **Phase 5** — Improvements (Farm/Mine/Quarry) + new frontend layer; routes deferred.

Each phase: bake → `node web/build.mjs` → refresh engine jar + `spring-boot:run` → webverify screenshots
across bands → commit (gated on the licensing decision).

## Verification

- **Unit**: `node --test web/*.test.mjs` (existing `dds.test.mjs` + new `civ6.test.mjs` for resolver +
  mapping-table coverage + fallback).
- **Bake**: `node web/build.mjs [seed]` runs clean with the depot present; and **with `CIV6_CACHE_DIR`
  unset it must still complete** (all-C2C) — the fallback smoke test.
- **Visual**: `tools/webverify` deep-links (`?p=&z=`) at each zoom band to confirm terrain, icons,
  feature overlays, and LoD cross-fades; compare against the current C2C bake.
- **Server**: refresh the engine jar (`mvn -pl civstudio-engine install -DskipTests`) then
  `spring-boot:run`, load the map, walk the zoom bands.

## Key files

- New: `web/civ6.mjs`, `web/civ6.test.mjs`, `docs/civ6-art-replacement.md` (this).
- Changed bakers: `web/build.mjs` (terrain source, bonus split, feature overlays, ice, improvements,
  LoD emit), `web/build-techs.mjs` (unchanged; techs stay C2C), new yield-symbols bake.
- Manifest: `civstudio-server/.../map/web-asset-manifest.json` + `server.web.WorldBundle` (LoD descriptors).
- Frontend: `web/js/plots.mjs` (feature-overlay + improvement draw paths, LoD pick), `web/js/bands.mjs`/
  `layers.mjs` (band→LoD + cross-fade).
- Reuse: `decodeDds` (`web/dds.mjs`), `terrainDisplayColors()`/`bakeTerrainTiles`/`bakeBonusIcons`
  (`web/build.mjs`), the recolor + atlas-slice helpers already there.

---

*Planned 2026-07-12. Decisions locked with owner. Blocked on the licensing gate before committing any
Civ6-derived `web/assets`. When Phase 1 lands, cross-link from `civ6-assets.md` and add a one-line
pointer in `CLAUDE.md`.*
