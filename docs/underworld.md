# The Underworld (Serpentspine)

**Status: design / planned.** This document is the design record and phased implementation
plan for the Underworld — a second map *plane* for the underground Serpentspine cave
network. Nothing here is built yet; sections marked **(open)** are decisions still to
lock before or during implementation.

## What it is

Anbennar's Serpentspine is a mountain range riddled with a stacked cave network — the
Dwarovar — home to the dwarven holds. In the imported EU4 world these underground
provinces already exist as real polygons, clustered in a thin band along the mountains.
CivStudio surfaces them as a distinct **Underworld plane**: a toggleable view where the
surface world dims out and the cave provinces light up in their true geographic positions,
with their own terrain, art, and — crucially — their own *economic physics* (no sun).

The four shaping decisions (chosen 2026-07-09):

| Axis | Decision |
| --- | --- |
| **Scope** | Full engine semantics — underground provinces simulate differently, not just a viewer layer. |
| **Province type** | Four new underground `ProvinceType`s — `CAVERN`, `DWARVEN_HOLD`, `DWARVEN_HOLD_SURFACE`, `DWARVEN_ROAD` — stamped from the matching Dwarovar terrain blocks in `terrain.txt`. |
| **Viewer layout** | Dimmed surface *ghost* — surface stays faintly visible beneath the lit underground, no auto-framing. |
| **Cave art** | Port real Civ4/C2C cavern + mushroom-forest terrain into the per-plot zoom, matching surface fidelity. |
| **Work schedule** | Underground runs a fixed **14-hour "sweatshop" day** in place of solar daylight. |

## Which provinces are underground

**Membership is defined by the four Dwarovar *terrain blocks* in
`data/anbennar/terrain.txt`, not the `serpentspine` continent.** The continent cross-cuts
the underground both ways, so it must *not* be used:

- Some `serpentspine`-continent provinces are **surface** — the impassable mountain walls
  (`Serpentspine Mountains`, `The Serpentreach`) and mixed surface passes (`northern_pass`:
  valleys, foothills, forests, lakes) — and stay surface.
- Some underground provinces sit under **mountains on other continents** — e.g.
  Dragonheights, the Deepwoods, holds like Marrhold (Cannor) and Ovdal Tungr (Sarhal) —
  outside the `serpentspine` continent entirely.

So the underworld is the union of the `terrain_override` lists of the four underground
terrain blocks, each mapped to its **own** province type by `CavernExporter` (applied over
`LAND`, and re-runnable over an already-underground type; water/`IMPASSABLE` walls are left
alone):

| terrain block | `ProvinceType` | what it is | count |
| --- | --- | --- | --- |
| `cavern` | `CAVERN` | open cavern floor | 212 |
| `dwarven_hold` | `DWARVEN_HOLD` | sub-surface holds (karaks) | 21 |
| `dwarven_hold_surface` | `DWARVEN_HOLD_SURFACE` | surface-gate holds (Verkal Dromak, Khugdihr, Marrhold, Ovdal Tungr…) | 16 |
| `dwarven_road` | `DWARVEN_ROAD` | the Dwarovrod tunnel network | 136 |

**385 underground provinces.** `ProvinceType.isUnderground()` (true for all four) is the
single membership test, end to end — the engine reads it for the sun-free clock, the web
viewer for the plane. All four render the same cave floor for now; distinct per-type art
(a hold hall, a paved tunnel) is possible later. The `serpentspine` continent
(`Continent.SERPENTSPINE`) stays a purely descriptive lore grouping.

The underground's own food comes from the `TERRAIN_CAVERN` floor itself (cave fungus), not
from any surface terrain.

## Special surface terrains

The same `CavernExporter` + synthetic-terrain machinery also promotes **seven distinctive
Anbennar *surface* terrains** to their own province types, so they no longer flatten onto
generic `LAND`/forest. These are *not* underground (sunlit, on the Overworld plane); they
share the pipeline only because the mechanism is identical. Each has its own
`ProvinceType`, per-plot `TERRAIN_*` (yields + recolored art), and — where fitting — a
signature feature:

| terrain block | `ProvinceType` | `TERRAIN_*` (yields F/P/C) | look | feature |
| --- | --- | --- | --- | --- |
| `ancient_forest` | `ANCIENT_FOREST` | `TERRAIN_ANCIENT_FOREST` (1/2/0) | deep old-growth green | ~90% `FEATURE_FOREST` |
| `gladeway` | `GLADEWAY` | `TERRAIN_GLADEWAY` (2/0/1) | verdant fey green | ~90% forest |
| `fey_gladeway` | `FEY_GLADEWAY` | `TERRAIN_FEY_GLADEWAY` (2/0/1) | teal fey | ~90% forest |
| `bloodgroves` | `BLOODGROVES` | `TERRAIN_BLOODGROVES` (1/1/0) | crimson blood-magic forest | ~90% forest |
| `mushroom_forest_terrain` | `MUSHROOM_FOREST` | `TERRAIN_MUSHROOM_FOREST` (2/1/0) | fungal violet (Haless) | — |
| `shadow_swamp_terrain` | `SHADOW_SWAMP` | `TERRAIN_SHADOW_SWAMP` (1/1/0) | shadowed marsh | ~90% `FEATURE_SWAMP` |
| `glacier` | `GLACIER` | `TERRAIN_GLACIER` (0/0/0) | pale ice | — |

Implementation notes:

- **Art** — reused from existing Civ4 textures (forests → `Lush`, swamp → `Marsh`, glacier →
  `Ice/Permafrost`), recolored in the web bake to each terrain's authored display colour;
  the colour is forced to win over the measured texture average (`AUTHORED` set in
  `terrainDisplayColors`).
- **Features** — a terrain-override province has no `trees.bmp` coverage, so the C2C
  feature stage leaves it bare. `ProvincePlotField` therefore stamps the signature feature
  over ~90% of non-peak plots directly (`SPECIAL_FEATURE`, `SPECIAL_FEATURE_COVER`), and
  `FeatureExporter` adds these terrains to `FEATURE_FOREST`/`FEATURE_SWAMP`'s
  `validTerrains` so the placement passes the validity gate. Forest terrains fold to
  `PyTerrain.GRASS`.
- **`city_terrain` is deferred** — cities need real urban plots and their encircling walls,
  a future phase; those provinces stay generic `LAND` for now.

## Engine semantics

### 1. No sun — the fixed 14-hour clock

Surface colonies recompute solar times each day: `Settlement.newDay()` →
`updateSolarTimes()` → `SolarClock.update(date)` (`SolarClock.java:79`), which sets
`daylightHours` from sunrise→sunset for the colony's latitude/longitude. Daylight then
scales labor output in `LaborMarket.addEmployee` (`LaborMarket.java:161–177`):

```
ratio          = colony.getDaylightHours() / FULL_OUTPUT_DAYLIGHT_HOURS   // FULL_OUTPUT_DAYLIGHT_HOURS = 8
daylightFactor = 1 + daylightSensitivity() * (ratio - 1)                  // applied per adult worker
```

Underground there is no sun, so the solar calculation is bypassed entirely. The **single
clean chokepoint** is the `SolarClock` instance itself (`Settlement.java:247`, constructed
at `:445`) — every colony-level daylight read (`getDaylightHours`, `getSunrise/Sunset`,
and transitively `getWorkWindowSeconds`) funnels through it; nothing else touches the
solar package. For an underground colony we substitute a **fixed-regime clock** whose
`update(date)` ignores the date and returns constant values:

- `daylightHours = 14` — the sweatshop day (dwarves on long lamplit shifts).
- constant sunrise/sunset spanning 14h → a stable `getWorkWindowSeconds()`.

**Consequence to calibrate.** With `FULL_OUTPUT_DAYLIGHT_HOURS = 8`, a 14h day gives
`daylightFactor = 1 + 1·(14/8 − 1) = 1.75` — a **1.75× per-adult labor multiplier**, year
round. That is intentional flavor (underground toil out-produces a surface day) but it is
a large economic lever; watch food balance and expect to rebalance either the hours, the
reference, or the countervailing costs (see food below). The latitude attenuation
`daylightSensitivity()` needs no change — with a constant regime the `(ratio−1)` term still
does the right thing.

The gate is a `Province.isUnderground()` (or `type == CAVERN`) check at
`Settlement.java:445` picking the fixed clock vs. the real `SolarClock`. Bare-coordinate
colonies (no province) stay on the solar clock.

### 2. `ProvinceType.CAVERN`

`ProvinceType` today is `LAND / SEA / LAKE / IMPASSABLE` (`ProvinceType.java`); the
`cavern` terrain is currently flattened to plain `LAND`. We add **`CAVERN`**, stamped by
the terrain exporter from `terrain.txt`'s cavern override.

**Blast-radius risk (important).** Cavern provinces are still settleable land, but they are
no longer `== LAND`. Every existing `type == LAND` test must be audited so it also accepts
`CAVERN`. Recommended containment: add `ProvinceType.isLand()` (true for `LAND` and
`CAVERN`) / `isSettleable()` helpers and migrate call sites to them rather than sprinkling
`|| == CAVERN`. This audit is a first-class task, not an afterthought.

### 3. Cave food & economy *(done, Phase 3)*

"Cave economy" falls out of terrain yields rather than special-casing. Plot food yield
feeds farm TFP through `Plot.yieldFactor(Sector.NECESSITY)` (`Plot.java:396`,
`yields()[0] / YIELD_REFERENCE[0]`, food reference `3.4`). The underground's character is
set by the cave floor's yields:

- **`TERRAIN_CAVERN`** — the cave floor every underground plot sits on. `[food 1, prod 2,
  commerce 0]`: meager cave-fungus food (0.29 farm TFP, vs 0.59 for grassland) but ore-rich
  production. Food-scarce, so it partly offsets the 1.75× labor boost — the key balance
  lever. Assigned to all `CAVERN`-province plots in `ProvincePlotField.generate()`, which
  also flattens their relief (the raster reads them as mountains; a cavern plot is a flat,
  walkable, farmable floor rather than an un-buildable peak).
- **`TERRAIN_MUSHROOM_FOREST`** — a *surface* fungal woodland, `[food 2, prod 1, commerce
  0]`, assigned to the Haless `mushroom_forest_region` provinces. Not underground; included
  here only because it shares the same authoring path. It plays no part in the cave economy.

Both terrains are authored (no Civ4 XML source) in `TerrainExporter.SYNTHETIC` and exported
to `terrains.json`. No farm/market code changes were needed — only terrain yield authoring
plus the per-plot assignment. Deeper cave-economy ideas (ore-driven mining sectors,
import-dependent food) remain **(open)** and out of this first cut; watch whether 1 food is
too generous or too harsh once colonies actually run underground.

## Data model & pipeline changes

### Terrain definitions & per-plot assignment

The live terrain pipeline is `ProvincePlotField` + real `terrain.bmp` via
`MapTerrainCodec` (not the stale climate-pool path). As built, the two cave terrains were
wired *without* touching `CIV4TerrainInfos.xml` or `MapTerrainCodec` (underground provinces
have no distinct `terrain.bmp` index — they read as mountains — so the raster can't drive
them). Instead:

1. **Authored, source-less terrains** — `TERRAIN_CAVERN` and `TERRAIN_MUSHROOM_FOREST` are
   defined with hand-set yields in `TerrainExporter.SYNTHETIC` and appended to
   `terrains.json` (they have no Civ4 XML peer, so they are *not* in the `KEEP` XML curation).
2. **Membership-driven per-plot assignment** — `ProvincePlotField.generate()` overrides the
   ground of every land cell to `TERRAIN_CAVERN` when `province.isUnderground()` (and to
   `TERRAIN_MUSHROOM_FOREST` when `regionKey == "mushroom_forest_region"`), flattening
   cavern relief to a walkable floor. The override runs before the feature/bonus stages so
   they read the real cave ground. `PyTerrain.of()` was left untouched: cavern folds to
   `OTHER`, which correctly disables surface tree-cover weighting underground.

### Exporters

- **`CavernExporter`** *(done, Phase 1)* — `com.civstudio.geo.export.CavernExporter` parses
  the `cavern` block's `terrain_override` from `data/anbennar/terrain.txt` and stamps
  `ProvinceType.CAVERN` over `LAND` on `provinces.json` (212 provinces). Mirrors
  `ClimateExporter`; run after it in the exporter chain. `type == CAVERN` is the single
  underworld membership marker read by both the engine and the web build — no separate flag.

## Frontend (web viewer)

### Current scaffolding

`S.plane` already boots from a `#underworld` deep-link (`core.mjs:201`) and `setPlane()`
exists (`panel.mjs:372`) — but **nothing in the render path reads `S.plane`**, there is no
plane tag on baked province records, and the toggle button is `disabled` in
`index.html:41`. So flipping the plane currently changes nothing.

### Work *(done, Phase 4 — see the phased plan below for the as-built summary)*

1. **Bake a plane tag** onto each province record in `web/build.mjs` (the province
   assembly at `build.mjs:171–181`), from `type == CAVERN` in `provinces.json`.
2. **Drive rendering from `S.plane`.** The single draw path is `renderScene()`
   (`main.mjs:133`), which loops `for (const p of P)`; the same idiom repeats in
   `drawPlots()` (`plots.mjs`) and `drawPolitical()` (`political.mjs:25/31`). Apply the
   **dimmed surface ghost** treatment:
   - Overworld plane (default): unchanged.
   - Underworld plane: draw the surface raster + surface provinces at reduced opacity (the
     ghost), then draw underground provinces at full strength on top — lit polygons, their
     per-plot cavern/mushroom terrain on zoom, borders, hover/select. No auto-framing;
     camera stays put (underground sits in its real Serpentspine position).
   - The political overlay's "province with no value is skipped" filter
     (`drawPolitical`, `political.mjs`) is the pattern to copy for "only draw provinces on
     the active plane."
3. **Write `S.plane` back to `location.hash`** on toggle (today the hash is read only at
   boot) so the plane is shareable/deep-linkable both ways.
4. **Remove `disabled`** from the underworld button (`index.html:41`).

### Per-plot cave art

Art binds by gameplay terrain key (`TERRAIN_*`) via `terrain-art.json` +
`TerrainArtInfo`, baked into `web/assets/plots.pack` by `packPlots` and the terrain atlas
by `bakeTerrainTiles`. Adding the two cave terrains touches three spots in
`web/build.mjs`: the atlas bake `bakeTerrainTiles` (`:574`, auto-adds a column per
`terrain-art.json` entry with a `detail`/`blend` path), the flat-colour fallback
`landColors` + palette `use(...)` (`:442–448`, `:549–554`), and the layer-order map `LY`
(`:533`). `web/js/plots.mjs` needs no change — it keys off `q.terrain` automatically once
the string and build maps are present.

**Art sourcing (resolved).** There is no bespoke cave *ground* texture in the committed art
(`data/civ4/assets`), so the underground and the special surface terrains **repurpose existing
Civ4 ground textures**, recoloured in the bake to an authored hue: cavern → rocky, forests →
lush, glacier → ice/permafrost, shadow swamp → marsh (see `TerrainArtExporter.SYNTHETIC` and
`build.mjs`'s authored display colours). No new `.dds` had to be sourced. *(The old Git-LFS
`UnpackedArt/` tree — which held some cave/mushroom `.nif` models — was removed entirely in
2026-07; all art now resolves from `data/civ4/assets`.)*

## Phased implementation plan

The pieces are separable; suggested order keeps something runnable at each step.

1. ~~**Membership + type.**~~ **Done.** Membership = the `cavern` terrain (authoritative;
   the `serpentspine` continent is *not* used). Added `ProvinceType.CAVERN` (settleable,
   passable, land), folded it into `isLand()`, wrote `CavernExporter` to stamp it over
   `LAND` (212 provinces), fixed the one `type`-comparing test. No behavior change yet;
   full suite green (248 tests).
2. ~~**Fixed clock.**~~ **Done.** `FixedDaylightClock` (a `SolarClock` subclass whose
   `update()` is a no-op and whose getters return a constant 14h day) is swapped in at
   `Settlement` construction when `province.isUnderground()`. `Settlement.CAVERN_WORK_HOURS
   = 14`, sunrise fixed at 05:00. A cavern colony reports 14h year-round → the labor market's
   `daylightFactor` is a steady **1.75×** (14/8). `CavernDaylightTest` covers it; full suite
   green (250 tests). *(engine behavior)*
3. ~~**Cave terrains + yields.**~~ **Done.** Authored `TERRAIN_CAVERN` `[1,2,0]` and the
   surface `TERRAIN_MUSHROOM_FOREST` `[2,1,0]` in `TerrainExporter.SYNTHETIC` →
   `terrains.json` (26 terrains). `ProvincePlotField.generate()` overrides underground
   plots to the flat cavern floor (and the mushroom region to its ground). Resolved the
   membership question: mushroom forest is **surface**, not cave. `CavernTerrainTest`
   covers it; full suite green (253 tests). Yield rebalance vs. food economy still pending
   real underground colonies. *(engine economy)*
4. ~~**Viewer plane.**~~ **Done.** `build.mjs` now ships `CAVERN` provinces (a `LANDLIKE`
   set replacing the two `type === "LAND"` filters); the frontend keys the plane off the
   baked `p.type === "CAVERN"` (no separate tag needed). `main.drawUnderworld()` applies the
   dimmed-surface-ghost: a per-world-copy veil over the map raster (abutting copies, no
   additive darkening) then the cavern provinces relit as warm amber-rimmed rock floors in
   place, drawn before the hover/selected highlights so those stay crisp. Button un-disabled
   in `index.html`; `setPlane` drives it. Hash write-back skipped for parity (the overlay
   toggle doesn't sync the hash either; `#underworld` still deep-links in). Verified headless
   (msedge): toggle flips `aria-pressed`, 212 caverns lit, no console errors, world + zoom
   views correct. Cave polygons are flat-lit — per-plot cave *art* is Phase 5.
5. **Cave art.** Source/author cavern + mushroom-forest ground textures; add
   `terrain-art.json` entries + the three `build.mjs` maps; optionally repurpose
   `cave.nif`/`mushrooms.nif` as feature overlays. Rebuild `plots.pack`. *(art)*

## Open questions

- **Labor calibration** — is 1.75× the intended net output, or should the 14h/8h reference
  be retuned once food scarcity is in?
- **Cave economy depth** — do we stop at terrain-yield-driven food, or add mining sectors /
  no-agriculture semantics later?
- **Stacked layers** — the real Dwarovar is vertically stacked; EU4 (and thus CivStudio)
  flattens it to adjacent 2D provinces. No 3D-layer modeling planned.
- **Ground art sourcing** — author from scratch, adapt an existing rock/dirt tile, or pull
  a cave texture from another Civ4 art set?

## Related

- Terrain pipeline: `docs/plots.md`, `docs/province-plots.md` (the live path).
- Feature/terrain art: `docs/features-art.md`, `TerrainArtExporter`.
- Solar/daylight: `docs/solar.md`; `SolarClock`, `com.civstudio.solar`.
- Geography & continents: `docs/geography.md`; `Continent`, `WorldMap`.
- Web plane/overlays: `web/README.md`; `js/overlays/`, `js/plots.mjs`.
