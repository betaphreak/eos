# Design note: urban plots & imported development

**Status:** Proposed (design + phased plan). Nothing built yet.
**Date:** 2026-07-12
**Depends on:**
- the special-terrain pipeline (`docs/underworld.md` §Special surface terrains) —
  `ProvinceType` promotion via `CavernExporter`, the source-less synthetic terrains in
  `TerrainExporter.SYNTHETIC` / `TerrainArtExporter.SYNTHETIC`, the per-plot terrain
  override in `ProvincePlotField` (`SPECIAL_POOL` / `SPECIAL_FEATURE`), and the web bake
  (`build.mjs`: `LANDLIKE`, `terrainDisplayColors`, `AUTHORED`);
- the plot / province-field model (`docs/plots.md`, `docs/province-plots.md`) — the
  per-province `ProvincePlotField`, the shared `ProvincePlotPool`, `claimFoundingCenter` /
  `bestYieldNearest`, the center-vs-worked-plot distinction, and the **dormant
  Production/Commerce yields** (only Food/`NECESSITY` is economically live — Phase 4b);
- the political layer (`docs/political-map.md`) — the exporter-stamps-`provinces.json`
  pattern and the web overlay modes this piggybacks on.

This note **supersedes** the `city_terrain is deferred` deferral called out in
`docs/underworld.md` (§Special surface terrains, "`city_terrain` is deferred") and the
matching exclusion comments in `CavernExporter` / `ProvinceType`.

## What this is

Two related additions, sharing one new terrain:

1. **`TERRAIN_URBAN` — a built-up "city" ground**, ported into the per-plot zoom like the
   other special terrains (repurposed Civ4 art, recoloured to a pavement grey — there is no
   dedicated city texture in C2C). Anbennar's `terrain.txt` marks some provinces as
   **entirely urban** (its `city_terrain` terrain block); those become a new
   `ProvinceType.URBAN` whose whole plot field is urban ground.

2. **One urban plot per province, concentrating that province's imported EU4 development.**
   Every province — not just the wholly-urban ones — gets **exactly one** urban plot at its
   centre, standing for the province's city/town. That plot **carries the province's whole
   development** (`base_tax` + `base_production` + `base_manpower`, i.e. EU4 ADM/DIP/MIL),
   which is **not currently imported** and lands with this work. A founding settlement
   **anchors its centre on this urban plot** — the village seat *is* the built-up plot.

### Decisions taken (from the design Q&A)

- **One urban plot per province** (not a count that scales with development): the province's
  entire development is **concentrated in a single urban plot**, its city centre. A
  `city_terrain` province is the special case where the *whole* field is urban ground, but it
  still has one designated development-bearing city plot.
- **Reference / visual this cut** — the urban terrain and the imported development land as
  **map + data**: the province shows its city on the zoom, and development is queryable and
  drawn in a web overlay, but the urban plot's yields are **authored and economically inert**.
  This is deliberate and low-risk: Production/Commerce are already dormant, and the urban plot
  sits at the **centre (plot 0), which is never farmed** — so it perturbs neither food balance
  nor the collapse smoke tests. Wiring an economic effect is deferred to the
  Production/Commerce activation (`docs/plots.md` Phase 4b) or a dedicated later cut.
- **The settlement anchors on the urban plot** — `claimFoundingCenter` places the founding
  centre on the province's tagged urban plot. The city seat and the urban terrain coincide.
- **The urban plot is sited by Civ4 city-founding rules** — not the province centroid. It is
  placed on the land cell that **maximises a Civ4-style `foundValue`**: as close as possible
  to as many **bonuses** (and good yield / river / coast) as fit within a city work radius, so
  the city sits where a Civ4 AI would found it. (See *The single urban plot* below.)
- **Import development now, and surface it in the web viewer** — add `base_tax` /
  `base_production` / `base_manpower` to `Province`, re-stamp `provinces.json` via
  `ProvinceHistoryExporter`, and add a **Development** map overlay (a numeric choropleth
  alongside the Nation/Culture/Faith overlays).

## Background: how special terrains work today (what we mirror)

The special-terrain pipeline (`docs/underworld.md`) is an 8-touch path, and `city_terrain`
is pre-marked as excluded at every step — so this is greenfield with the hooks already
flagged:

| Step | File | Today's special-terrain example |
|---|---|---|
| Province type | `geo/ProvinceType.java` | `CAVERN(...)`, `GLACIER(...)` — `(settleable, passable, underground)` |
| Block → type | `geo/export/CavernExporter.java` (`TERRAIN_TYPES`) | `"glacier" → "GLACIER"`; **`city_terrain` explicitly excluded** |
| Synthetic terrain + yields | `geo/export/TerrainExporter.java` (`SYNTHETIC`) | `new Terrain("TERRAIN_GLACIER", {0,0,0}, true, 0, 0, 1)` |
| Art binding | `geo/export/TerrainArtExporter.java` (`SYNTHETIC`) | `TERRAIN_CAVERN` → `Rocky*.dds`, empty blend |
| Per-plot assignment | `settlement`…`geo/ProvincePlotField.java` (`SPECIAL_POOL`, `SPECIAL_FEATURE`) | `CAVERN → cavernPool()`; latitude/relief flatten |
| C2C fold | `geo/PyTerrain.java` (`of`) | forest family → `GRASS`; cavern → `OTHER` |
| Feature hosts | `geo/export/FeatureExporter.java` (`EXTRA_HOSTS`) | forest terrains added to `FEATURE_FOREST.validTerrains` |
| Web render | `web/build.mjs` (`LANDLIKE`, `terrainDisplayColors` fallback + `AUTHORED`) | `TERRAIN_CAVERN: [58,45,37]` pinned |

`terrain-art.json` / `terrains.json` / `provinces.json` are regenerated by re-running the
exporters; `web/js/plots.mjs` and `core.mjs` need no change — they key off the plot's
terrain string automatically once the maps carry it.

## Part A — `TERRAIN_URBAN` and `ProvinceType.URBAN`

### The terrain

`TERRAIN_URBAN` is a source-less synthetic terrain (no Civ4 XML peer), authored like
`TERRAIN_CAVERN`:

- **Yields (authored, dormant this cut):** proposed `[food 1, production 1, commerce 3]` — a
  meagre-food, trade-and-tax-heavy city tile. **Only the food figure could ever bite**
  (Production/Commerce are gated off), and even that is inert wherever the urban plot is the
  unfarmed centre. The triple is a placeholder pending the Phase-4b commerce activation; kept
  low-food so a *wholly*-urban province (whose worked plots would sit on urban ground) does
  not distort food balance. `new Terrain("TERRAIN_URBAN", {1,1,3}, true, 0, 0, 1)` in
  `TerrainExporter.SYNTHETIC`.
- **Ground art:** the `TERRAIN_URBAN` ground is only the **base** the city stands on — a
  subtle paved surface. Repurpose `Rocky*.dds` (grey stone reads as pavement) recoloured to a
  concrete grey — there is **no city/pavement/cobble texture in C2C** (verified against the
  locked `CIV4ArtDefines_Terrain.xml`). `new TerrainArtInfo("TERRAIN_URBAN",
  "ART_DEF_TERRAIN_URBAN", "Art/Terrain/Textures/Land/RockyBlend.dds", ".../RockyGrid.dds",
  ".../RockyDetail.dds", <layerOrder>, false, Map.of())` in `TerrainArtExporter.SYNTHETIC`,
  then a `TERRAIN_URBAN: [grey]` entry in `build.mjs`'s `terrainDisplayColors` fallback +
  `AUTHORED`, and `"URBAN"` added to `LANDLIKE`. `PyTerrain.of` leaves it `OTHER` (no wild
  vegetation on city ground — correct default). The **city itself is a sprite drawn over this
  ground** — see *The city sprite* below.
- **Relief:** urban plots are **flattened to `PlotType.FLAT`** in `ProvincePlotField` (a city
  sits on level, built ground), the same flatten the underground floor uses.

### The city sprite — how Civ4 actually draws cities

In Civ4 a city is **not a terrain texture** — the tile keeps its natural ground and a **3D city
model sits on top of it**, procedurally assembled by the **City LSystem**
(`CIV4CityLSystem.xml`) from many small building pieces arranged in concentric zones. Its look
is driven by **art style × era × size**: the owner civ's `ArtStyleType` (European,
Greco-Roman, Middle-Eastern, Asian, …) picks the building set, the era re-skins it, and
population scales how many pieces appear (a floating **billboard** — name/pop/bars — is 2D UI,
not world art). So the faithful port is a **sprite overlaid on the plot**, not a ground recolour
— and this repo already has the exact precedent: `tools/nifbake` renders Civ4 `.nif` models to
sprite sheets (that is how the cactus / tall-grass foliage with no billboard atlas are drawn),
and `plots.mjs` stamps those sprites **over** the terrain (`docs/features-art.md`).

The plan therefore renders the urban plot as **paved ground + a baked city sprite on top**:

- **Bake a city sprite via `tools/nifbake`.** Fetch a representative Civ4/C2C city `.nif` from
  `Art/Structures/Cities/…` (via `Civ4Files` / `web/civ4.mjs`, adding the path to the C2C file
  map), render it front-on to a cutout, and pack it into a sprite sheet exactly as
  `bakeNifGroup` does for cactus/grass. Emit a `BUNDLE.cities` sprite set (mirroring
  `BUNDLE.trees`).
- **Art style from Anbennar culture (optional, phased).** Map the province's `culture` →
  a Civ4 `ArtStyleType` → a city sprite variant, so a Damerian city and a dwarven hold read
  differently. First cut can ship a **single** representative city sprite; the culture→style
  mapping + a per-style sprite is a follow-up (the LSystem's art-style axis, minus the LSystem).
- **Size from development.** Scale the city sprite (or pick among a few size tiers —
  hamlet / town / city) by the plot's concentrated `development()` — the visual analogue of
  Civ4's population-driven growth, and a natural use of the imported dev.
- **Render.** `plots.mjs` stamps the city sprite on any plot whose terrain is `TERRAIN_URBAN`
  (deterministic position at the plot centre, sized to the plot × the dev tier), drawn in the
  foliage/overlay pass over the ground — a small sibling to `featureSprite`. On a `city_terrain`
  province only the designated **city plot** gets the sprite (its neighbours are paved ground),
  so a wholly-urban province reads as one city amid its built district rather than a field of
  duplicated models.

**Out of scope: the full LSystem.** Procedurally assembling a city from per-building models
that grow as you construct buildings is overkill for a 2D baked renderer; one sprite (or a few
size tiers, and later per-art-style variants) is the pragmatic port. Noted in *Open questions*.

### Wholly-urban provinces (`city_terrain`)

Anbennar's `city_terrain` terrain block lists the provinces that are *entirely* urban. Mirror
the special-terrain path:

- `ProvinceType.URBAN(true, true, false)` — settleable, passable, surface (auto-`isLand()`).
- `CavernExporter.TERRAIN_TYPES.put("city_terrain", "URBAN")` (and drop the exclusion
  comments); re-run `CavernExporter` to stamp `provinces.json`.
- `ProvincePlotField.SPECIAL_POOL` gains `URBAN → Map.of("TERRAIN_URBAN", 1.0)` (a pure-urban
  pool — a city has no rural mix), flattening relief. **No** `SPECIAL_FEATURE` (cities are
  bare of forest/swamp).

## Part B — the per-province urban plot + imported development

### Importing development

Development is **not imported anywhere today** — `Province` has no dev field, and
`ProvinceHistoryExporter` parses only `owner`/`controller`/`culture`/`religion`/`trade_goods`.
The raw Anbennar `history/provinces/*.txt` (fetched via `AnbennarFiles`) *do* carry
`base_tax` / `base_production` / `base_manpower`, so no new data source is needed — only new
exporter fields:

- **`Province`** gains `baseTax`, `baseProduction`, `baseManpower` (ints; ADM/DIP/MIL), plus a
  convenience `development()` = their sum. Defaults 0 for provinces with no history entry
  (water, unlisted).
- **`ProvinceHistoryExporter`** — add the three keys to the overlay set, parse them as ints in
  `merge()`, and write them in the `provinces.json` stamp. Re-run to re-stamp.
- Provinces with a base development (EU4 minimum is `1/1/1`) → `development() ≥ 3`; capitals
  and cities run much higher.

### The single urban plot — sited by Civ4 founding value

In `ProvincePlotField.generate()`, **after the relief/feature/bonus stages have run** (so the
resource layout the score reads is final), pick the province's city cell by a **Civ4-style
`foundValue`** rather than the centroid, then:

1. set that cell's terrain to `TERRAIN_URBAN` and its relief to `PlotType.FLAT`;
2. record it as the province's **city plot** (its index/coords), and attach the province's
   `development()` to it (concentrated on this one plot).

**The `foundValue` (a `CityPlacement` helper).** Score each candidate land cell by the value
it can reach within a **city work radius** `R` (Civ4's "fat cross" is a 2-tile radius; here `R`
is a small Euclidean radius in raster pixels, tuned to a plausible town footprint). Mirroring
`CvPlayerAI::AI_foundValue` at the level this model supports:

- **Bonuses dominate.** Each `Bonus` on a cell within `R` adds a large weight (strategic /
  luxury / production resources heaviest), decayed by distance — "as close as possible to as
  many bonuses as possible" is exactly this term.
- **Yield.** Sum the food/production/commerce of the reachable cells (a good hinterland).
- **River / coast bonus** where a per-plot river/water signal exists (rivers already flag
  plots; coast is future).
- **Relief.** Prefer a `FLAT`/`HILL` centre (a `PEAK` is unfoundable), matching Civ4's
  city-on-hill preference without its defense model.

Pick the max-scoring cell; ties broken toward the centroid (a stable, central fallback). This
consumes **no RNG** (a deterministic scan of the finished field), so the persisted per-province
field stays seed-independent.

For a `city_terrain` province the whole field is already `TERRAIN_URBAN` (Part A); the
`foundValue` scan still tags **one** development-bearing **city plot** so there is always
exactly one dev anchor per province.

The urban plot is **generation-time, seed-independent province data** (like the rest of the
persisted field, `docs/province-plots.md`), so it is stable across seeds and cached.

### Anchoring the settlement

`claimFoundingCenter` today returns `bestYieldNearest(centroidX, centroidY)` for the first
settlement in a province. Since the urban plot is now `foundValue`-sited (Civ4 rules), it is
**no longer the centroid-nearest plot**, so the preference must be explicit:
`claimFoundingCenter` **claims the province's tagged city plot** if free, so the founding
centre (plot 0, the civic seat) lands on the bonus-optimal urban ground — the same plot a Civ4
AI would found on. Later settlements in the same province keep the min-distance auto-spacing
(they do not get the city plot). Because the centre is never a worked plot, the urban terrain's
yields stay inert — the "reference/visual only" guarantee holds regardless of where the city
plot sits.

## Part C — web: the Development overlay

Add a **Development** map mode beside Nation/Culture/Faith (`docs/political-map.md`,
`web/js/overlays/political.mjs`). Unlike the categorical political overlays it is a **numeric
choropleth**: bucket `development()` into a sequential ramp (see `dataviz` conventions for a
perceptually-ordered sequential palette), colour each province by its bucket, legend showing
the ramp. Reuse the political overlay's zoom-band + "province with no value never fills"
machinery. `web/build.mjs` bakes `development` onto each province record (from the re-stamped
`provinces.json`); the urban plot itself needs no special web handling beyond its terrain art
(Part A) — it already renders via the terrain key.

## Architecture mapping (files to touch)

Engine:

| File | Change |
|---|---|
| `geo/ProvinceType.java` | `URBAN(true, true, false)`; drop the `city_terrain`-deferred Javadoc note |
| `geo/Province.java` | `baseTax` / `baseProduction` / `baseManpower` fields + `development()` |
| `geo/export/ProvinceHistoryExporter.java` | parse + stamp the three dev keys |
| `geo/export/CavernExporter.java` | `TERRAIN_TYPES.put("city_terrain", "URBAN")`; drop exclusion comments |
| `geo/export/TerrainExporter.java` | `TERRAIN_URBAN` in `SYNTHETIC` |
| `geo/export/TerrainArtExporter.java` | `TERRAIN_URBAN` art (repurposed `Rocky*`) in `SYNTHETIC` |
| `geo/PyTerrain.java` | leave `TERRAIN_URBAN → OTHER` (default; no change unless we want city foliage suppressed explicitly) |
| `geo/ProvincePlotField.java` | `SPECIAL_POOL[URBAN]`; the `foundValue`-sited city-plot tag (**after** the bonus stage) + dev attach + flatten |
| `geo/CityPlacement.java` (new) | the Civ4-style `foundValue` work-radius scan (bonus-weighted); deterministic, no RNG |
| `settlement/ProvincePlotPool.java` | `claimFoundingCenter` claims the tagged city plot |
| `settlement/Plot.java` (or the field record) | carry the `isCity` flag + concentrated `development` (data only) |

Resources (regenerated by re-running the exporters, then re-persist province fields):
`terrains.json`, `terrain-art.json`, `provinces.json`, and the affected
`map/provinces/<id>.json.gz` fields.

Web:

- `build.mjs` — `LANDLIKE` += `"URBAN"`; `terrainDisplayColors` fallback + `AUTHORED` for
  `TERRAIN_URBAN`; bake `development` onto province records; **bake the city sprite** — add the
  C2C `Art/Structures/Cities/…` `.nif` path to the `civ4.mjs` file map and call `bakeNifGroup`
  (as for cactus/grass) to emit `BUNDLE.cities`.
- `web/civ4.mjs` — the city `.nif` path in the C2C file map.
- `web/js/plots.mjs` — a `citySprite` stamp (sibling to `featureSprite`) drawn over
  `TERRAIN_URBAN` plots, sized by the plot's dev tier. **This is the one `plots.mjs` change** —
  the terrain/colour path is otherwise automatic.
- a new `web/js/overlays/development.mjs` (or extend the political overlay) + panel/legend
  chrome + the map-mode toggle. `core.mjs` unchanged.

Because the live server bakes the bundle from engine resources, this is a
**redeploy-on-change** (see the deploy notes) once `provinces.json` / `terrain-art.json`
move.

## Phased implementation plan

Each phase is independently landable and leaves the suite green.

- **Phase 1 — the urban terrain (no province marked urban yet).** Add `TERRAIN_URBAN` to
  `TerrainExporter`/`TerrainArtExporter` `SYNTHETIC`, the web colour/`AUTHORED`, and
  `ProvinceType.URBAN` (unused). Regenerate `terrains.json`/`terrain-art.json`; web bake picks
  up the art. **Byte-identical** — no province is urban and no plot is assigned the terrain, so
  nothing generates it yet. Test: registry loads `TERRAIN_URBAN` with its yields (mirror
  `CavernTerrainTest`).
- **Phase 2 — wholly-urban provinces.** `CavernExporter` stamps `city_terrain` →
  `URBAN`; `ProvincePlotField.SPECIAL_POOL[URBAN]`. Re-stamp `provinces.json`. City provinces
  now render as urban ground on the zoom. Behavioural only if a scenario founds into a
  `city_terrain` province (the default `HomogeneousEconomy`/`TwinSettlementEconomy` found into
  Dhenijansar, a normal province — unaffected). Verify headless in the web viewer.
- **Phase 3 — import development.** `Province` dev fields + `ProvinceHistoryExporter` +
  re-stamp `provinces.json`. Pure data; no behaviour. Test: a known province's dev matches its
  Anbennar history entry.
- **Phase 4 — the per-province city plot (Civ4 `foundValue`) + anchoring.** Add the
  `CityPlacement` `foundValue` scan; `ProvincePlotField` runs it after the bonus stage and tags
  the winning plot `TERRAIN_URBAN` + `FLAT` + concentrated `development`; `claimFoundingCenter`
  claims it. **Behavioural but calibration-safe** — the city plot is the unfarmed centre, so
  food balance and the collapse profile are unchanged (re-validate the smoke tests to confirm).
  Tests: every generated province field has exactly one city plot; it maximises `foundValue`
  (a placement scoring test — the city sits near the province's bonuses); the default colony's
  centre is urban; `PlotYieldTest` mean food factor still ≈ 1.0.
- **Phase 5 — the city sprite.** Bake a representative Civ4/C2C city `.nif` (`Art/Structures/
  Cities/…`) via `tools/nifbake` → `BUNDLE.cities`; stamp `citySprite` over `TERRAIN_URBAN`
  plots in `plots.mjs`, sized by the plot's dev tier. Now a founded colony (and every
  `city_terrain` province's city plot) reads as an actual city, not grey ground. Web-only;
  verify headless. *Follow-up within/after this phase:* culture → `ArtStyleType` → per-style
  sprite (ships after the single-sprite first cut).
- **Phase 6 — the web Development overlay.** Bake `development` onto province records; add the
  choropleth overlay mode + legend + toggle. Verify headless.

## Open questions (deferred)

- **Should urban yield later scale with development?** This cut authors a fixed urban triple
  and stores development as concentrated data. When Production/Commerce wake (`docs/plots.md`
  Phase 4b), the natural follow-up is to make the city plot's commerce/production yield a
  function of `base_tax`/`base_production` — the first live economic use of the imported dev.
- **`base_manpower` → what?** ADM(`base_tax`)→commerce/tax and DIP(`base_production`)→
  production map cleanly onto Civ4 yields; MIL(`base_manpower`) has no yield analogue. Left as
  raw data for a future manpower/military model; not folded into a yield here.
- **Multiple cities in large provinces.** Decision was one urban plot per province; a very
  large or high-dev province having a couple of towns is a later refinement (the count-scaling
  option not taken).
- **City walls / encircling terrain.** `docs/underworld.md` paired urban plots with "their
  encircling walls" — walls are out of scope here (no wall terrain/among-plot adjacency exists).
- **The full City LSystem.** Civ4 assembles a city from per-building models that grow as you
  construct buildings (`CIV4CityLSystem.xml`). This cut bakes **one** representative city sprite
  (later: a few dev-driven size tiers, then per-culture `ArtStyleType` variants). Procedurally
  composing a city from building pieces is out of scope for the 2D baked renderer.
- **Era re-skin.** Civ4 re-skins a city by era (Ancient→Modern). CivStudio has no era axis
  yet; the sprite is era-neutral until one exists.
