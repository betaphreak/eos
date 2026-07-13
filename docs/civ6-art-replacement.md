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

### E. Water (`water/*.webp`) — now all Civ6 (Phase 6)
- **Ice** → `Features_Icecaps_Visible.dds`.
- **Sea + shore ripple** → the SV `TerrainHexOcean` / `TerrainHexCoast` tiles (their tile-scale RGB
  survives the 128px downsample, unlike the 2k `FOW_Water_*` surfaces); read as a neutral-mean greyscale
  ripple, soft-lit over the sea gradient as before.
- **Sea climate-band colours** → sampled from the same SV Ocean/Coast tiles (Civ6's ocean is one blue hue,
  so tropical/polar are warm/cool derivations of it; shore from the Coast tile).
- **River** → `TER_River_Water` (an opaque river surface with strong RGB ripple) as the ribbon fill.
  Civ6 has **no edge-decal tile**, so the river stays a **cell-path** line — restyled as a thin *banked*
  channel (dark wet bank + Civ6 water core + centre shimmer, `drawRiver`), the pragmatic "path A" (the
  cell↔edge topology gap is discussed in `docs/river-rendering.md`). All four keep a C2C fallback and are
  wrap-feathered seamless (`makeSeamless`).

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

### H. City districts (urban core) — planned (art chosen 2026-07-13)
Replaces the interim urban pip (`web/js/city.mjs`, `docs/urban-plots.md`) that stands in after the
ugly Civ4 city sprite + grey-concrete ground were pulled. **Analysis (verified by decoding the depot):
the depot has NO flat top-down district *building* art.** District art comes in three families:
- **`Hex_District*.dds` — 512², ~64% opaque, full-hex colored chip + emblem** (`CityCenter`=star,
  `Neighborhood`=house, `Commercial`=coin, `Campus`=flask, `Encampment`=shield, `Faith`=wings,
  `Theater`=clef). These are Civ6's strategic-view district hexes — clean, ready-to-bake, and the
  **chosen source** (owner, 2026-07-13: "good find, we'll use them later"). Only these **7** exist as
  `Hex_*`; other districts (Industrial/Harbor/Aqueduct/Aerodrome/Spaceport…) exist only as the badge
  or 3D families below.
- **`Districts_<Type>_Visible.dds` / `StrategicView_Districts_*` — 128², ~20-35% opaque** loose emblem
  badges (the same symbols without the hex backing; also `_Revealed`/`_UnderConstruction`/`_Pillaged`
  state variants). Fallback for district types with no `Hex_*` chip.
- **`DIS_*` — 3D model pieces** (2708 `.geo` + `.fgx` meshes + per-piece albedo `.dds`, e.g.
  `DIS_CTY_AB_Base_B` 1024×512 UV atlases). The literal in-game buildings, but **not flat-usable** and
  **no headless renderer exists**: nifbake only reads Civ4 `.nif`, and there is **no open-source
  `.fgx`/`.geo` renderer** — the only path is CivNexus6 (closed Windows GUI freeware) → `.nb2`/`.cn6` →
  Blender (open-source scripts: deliverator23/Sukritact) → render, a manual GUI pipeline that can't be a
  `node build.mjs` step. **Rejected** for this pass; the symbolic hex chip is the faithful
  *strategic-view* representation anyway.

**Bake sketch (for the later cut):** `civ6.districtTile(type)` resolver → `Hex_District<Type>.dds`;
`bakeDistrictTiles` takes RGB+**alpha** (the alpha is the hex cutout mask — keep it, like the feature
overlays), trims the transparent corners, emits `districts/dis-<type>.webp` at a `@128/@256` LoD + a
`districtTiles` manifest key (**remember to allow-list it in `WorldBundle`** — the Phase-3 gotcha). A
`plots.mjs` draw path stamps the hex chip on urban plots in place of the `city.mjs` pip.
**Mapping (interim, no engine districts yet):** the sim has only urban-core plots + `dev` — so stamp
`CityCenter` on the primary core; a `city_terrain` province's extra cores can vary
(`Neighborhood`/`Commercial`). When the engine grows a real district concept, map by function.

**Beyond the flat chip:** the chip is a *label*; the real district *view* is a procedural building
assembly (Civ6's "LSystem"), which needs **engine/server support** (district type, population, era,
culture, built-buildings) and sources its function buildings from **Civ4 C2C** (nifbake — Civ6
`.fgx` has no headless renderer). Full reverse-engineering + port plan + data contract in
[`district-generator.md`](district-generator.md).

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
  `drawSeaIce` builds its floe `createPattern` from. **Two ice paths** now share the tile: (1) the
  per-plot coastal-shelf floes (`plots.mjs drawSeaIce`) on SEA/LAKE provinces adjacent to land, at deep
  zoom; and (2) a screen-space **polar ice cap** over the *open* ocean (`main.mjs drawPolarIce`), since
  open-ocean seas generate no plottable shelf and so would otherwise read as bare dark water. The cap is a
  latitude-ramped coverage of the same icecaps tile (coverage 0 below ~62° → solid by ~80°, map-anchored),
  drawn over the sea base and under the land, and **faded out entering the plot band** (`[K_PLOT,K_TEX]` =
  5→16×) exactly where the per-plot shelf floes fade in — so the two never double. Verified at world,
  regional and deep zoom (both hemispheres).
- **Phase 5** ✅ (art + layer; **placement deferred**) — the three improvements Civ6 ships a flat SV for —
  **Farm/Mine/Quarry** — baked as 128² alpha overlays (`bakeImprovementOverlays` → `improvements/imp-*.webp`
  + a `improvementOverlays` manifest key, added to `WorldBundle`'s allow-list). A new frontend layer
  (`plots.mjs improvementSprite` + a per-plot pass, mirroring the feature-overlay path) draws the overlay on
  any plot carrying an `improvement`. **No placement yet** (owner's call): `ProvincePlot` is
  `(geo,terrain,plotType,feature,bonus)` — no `improvement` field — and the engine emits none, so the layer
  is wired but dormant. Verified by injecting improvements onto a province's plots in-app: the overlays draw
  correctly (farm on flat, mine on hills, quarry on bonuses). **Planned placement rule** (owner: *Both*) —
  when it lands (engine plot field or a frontend-derived pass): resource-improvements wherever a bonus
  matches (mine→ore/metal, farm→crops, quarry→stone/marble/gems) **plus** a farm scatter on good farmland
  near the urban core. The other improvements (pasture, plantation, cottage-line, lumbermill, camp, winery)
  and **routes** have no Civ6 flat SV and stay deferred (logged, not silently dropped).

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
