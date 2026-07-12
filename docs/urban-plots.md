# Design note: urban plots & imported development

**Status:** In progress. Phase 1 (the `TERRAIN_URBAN` substrate) is done; the rest is planned.
**Date:** 2026-07-12
**Depends on:**
- the special-terrain pipeline (`docs/underworld.md` §Special surface terrains) — the
  source-less synthetic terrains in `TerrainExporter.SYNTHETIC` /
  `TerrainArtExporter.SYNTHETIC`, the per-plot terrain override in `ProvincePlotField`, and
  the web bake (`build.mjs`: `terrainDisplayColors`, `AUTHORED`);
- the plot / province-field model (`docs/plots.md`, `docs/province-plots.md`) — the
  per-province `ProvincePlotField`, the shared `ProvincePlotPool`, `claimFoundingCenter` /
  `bestYieldNearest`, the center-vs-worked-plot distinction, `BonusGenerator`, and the
  **dormant Production/Commerce yields** (only Food/`NECESSITY` is economically live);
- the political layer (`docs/political-map.md`) — the exporter-stamps-`provinces.json`
  pattern and the web overlay modes the Development overlay piggybacks on.

## What this is

Every province is a **city plus its rural hinterland**. This note adds:

1. **`TERRAIN_URBAN` — a built-up "city" ground** (a source-less synthetic terrain, with a
   baked city sprite on top; see *The city sprite*). It is **not** a province type and does
   **not** blanket a province — it is stamped on the **one-or-few urban plots** that form a
   province's city core, while the rest of the province keeps its real map terrain.

2. **One urban plot per province, concentrating that province's imported EU4 development.**
   Every province gets **at least one** urban plot at its city site, carrying the province's
   whole development (`base_tax` + `base_production` + `base_manpower`, i.e. EU4 ADM/DIP/MIL —
   **not currently imported**; it lands with this work). A founding settlement **anchors its
   centre on this urban plot** — the village seat *is* the built-up plot.

3. **`city_terrain` provinces get a denser core.** Anbennar marks some provinces (its
   `city_terrain` terrain block — 113 of them, including notable capitals) as cities. Rather
   than making them wholly urban ground (which would erase their farmland — and the default
   `HomogeneousEconomy` colony founds into one, Dhenijansar), a `city_terrain` province keeps
   its real terrain but is flagged `city` and gets **several** urban core plots instead of one.

### Decisions taken (from the design Q&A)

- **Urban-cored, keep hinterland.** No province is wholly urban and there is **no
  `ProvinceType.URBAN`** — cities stay `LAND` (so no `type == LAND` blast-radius, no terrain
  re-baseline). "Urban" lives entirely at the **plot** level (`TERRAIN_URBAN`), as a small
  built-up core inside an otherwise-rural province. This keeps Dhenijansar (the default
  colony's home, a genuine `city_terrain` province) on farmland and leaves the calibration
  reference untouched.
- **One urban plot per province**, concentrating all of that province's ADM/DIP/MIL; a
  `city` province gets a few instead of one.
- **Reference / visual this cut.** The urban terrain and imported development land as **map +
  data**: the province shows its city, development is queryable and drawn in a web overlay,
  but the urban plot's yields are **authored and economically inert** — the urban plot is the
  unfarmed **centre** (plot 0), and Production/Commerce are already dormant, so no food-balance
  or collapse-profile change. An economic effect is deferred to the Production/Commerce
  activation (`docs/plots.md` Phase 4b).
- **The urban plot is sited by Civ4 city-founding rules** — placed on the land cell that
  maximises a Civ4-style `foundValue` (as close as possible to as many **bonuses** as fit in a
  city work radius), not the province centroid. The settlement anchors its centre there.
- **Import development, and surface it in the web viewer** (a numeric Development choropleth
  beside Nation/Culture/Faith).

## Part A — the `TERRAIN_URBAN` ground *(Phase 1 — done)*

`TERRAIN_URBAN` is a source-less synthetic terrain (no Civ4 XML peer), authored like
`TERRAIN_CAVERN`:

- **Yields (authored, dormant):** `[food 1, production 1, commerce 3]` — a meagre-food,
  trade-and-tax-heavy city tile. Only the food figure could ever bite (Production/Commerce are
  gated off), and even that is inert wherever the urban plot is the unfarmed centre. `new
  Terrain("TERRAIN_URBAN", {1,1,3}, true, 0, 0, 1)` in `TerrainExporter.SYNTHETIC`.
- **Ground art:** the built-up ground is only the **base** the city sprite stands on — a
  subtle paved surface. Repurpose `Rocky*.dds` recoloured to a concrete grey (`[120,116,110]`
  in `build.mjs` `terrainDisplayColors` fallback + `AUTHORED`) — there is **no
  city/pavement/cobble texture in C2C**. `TerrainArtInfo("TERRAIN_URBAN", …RockyBlend/Grid/
  Detail…, 13, false, Map.of())` in `TerrainArtExporter.SYNTHETIC`. `PyTerrain.of` leaves it
  `OTHER` (no wild vegetation on city ground). The **city itself is a sprite drawn over this
  ground** — see *The city sprite*.

**Done in Phase 1:** the terrain + art authored and regenerated into `terrains.json` /
`terrain-art.json`, the web colour/`AUTHORED` entry, and `UrbanTerrainTest` (registry loads
`TERRAIN_URBAN`). No province or plot uses it yet — that is Part B.

### The city sprite — how Civ4 actually draws cities

In Civ4 a city is **not a terrain texture** — the tile keeps its natural ground and a **3D
city model sits on top of it**, procedurally assembled by the **City LSystem**
(`CIV4CityLSystem.xml`) from building pieces in concentric zones, skinned by **art style × era
× size** (population scales how many pieces appear; a floating billboard is 2D UI). So the
faithful port is a **sprite overlaid on the plot**, not a ground recolour — and this repo
already has the exact precedent: `tools/nifbake` renders Civ4 `.nif` models to sprite sheets
(that is how cactus / tall-grass foliage with no billboard atlas are drawn), and `plots.mjs`
stamps those sprites **over** the terrain (`docs/features-art.md`).

The plan renders an urban plot as **paved ground + a baked city sprite on top**:

- **Bake a city sprite via `tools/nifbake`** — fetch a representative C2C city `.nif` from
  `Art/Structures/Cities/…` (via `Civ4Files` / `web/civ4.mjs`), render it front-on, pack it as
  `bakeNifGroup` does for cactus/grass → `BUNDLE.cities`.
- **Size from development** — scale the sprite (or pick hamlet / town / city tiers) by the
  plot's concentrated `development()` — the visual analogue of Civ4's population-driven growth.
- **Art style from Anbennar culture (phased)** — map the province's `culture` → a Civ4
  `ArtStyleType` → a per-style sprite (ships after a single-sprite first cut).
- **Render** — `plots.mjs` stamps `citySprite` (a sibling to `featureSprite`) on any
  `TERRAIN_URBAN` plot, at the plot centre, sized by the dev tier.

**Out of scope: the full City LSystem** (procedurally composing a city from per-building
models). One sprite (later a few tiers / per-style variants) is the pragmatic 2D port.

## Part B — the per-province urban core + imported development

### Importing province data

Two new inputs, both stamped onto `provinces.json`:

- **Development** — not imported today (`ProvinceHistoryExporter` reads only
  owner/controller/culture/religion/trade_goods). The raw Anbennar `history/provinces/*.txt`
  carry `base_tax` / `base_production` / `base_manpower`, so `Province` gains
  `baseTax`/`baseProduction`/`baseManpower` (ints; ADM/DIP/MIL) + `development()` (their sum),
  and `ProvinceHistoryExporter` parses + stamps them.
- **The `city` flag** — `Province` gains a `boolean city`, stamped from the Anbennar
  `city_terrain` terrain block (the same `terrain.txt` `CavernExporter` reads). Cities stay
  `type == LAND`; the flag only says "give this province a denser urban core." Parsed either by
  a small `CityTerrainExporter` or by extending `CavernExporter` to stamp the flag (without
  touching `type`).

### The urban core plots — sited by Civ4 founding value

In `ProvincePlotField.generate()`, **after the relief/feature/bonus stages** (so the resource
layout the score reads is final), pick the province's city cell(s) by a **Civ4-style
`foundValue`** and set each to `TERRAIN_URBAN` + `PlotType.FLAT` (level built ground). The
**primary** city plot carries the province's concentrated `development()`; a `city`-flagged
province promotes a few of the next-best cells to urban too (a denser core), an ordinary
province gets one. The rest of the field keeps its real map terrain — the rural hinterland.

**The `foundValue` (a `CityPlacement` helper).** Score each candidate land cell by the value
reachable within a **city work radius** `R` (a small Euclidean radius in raster pixels),
mirroring `CvPlayerAI::AI_foundValue` at the level this model supports:

- **Bonuses dominate** — each `Bonus` within `R` adds a large, distance-decayed weight
  (strategic / luxury / production heaviest): "as close as possible to as many bonuses as
  possible".
- **Yield** — sum the food/production/commerce of the reachable cells (a good hinterland).
- **River / coast** bonus where the signal exists; **relief** prefers a `FLAT`/`HILL` centre (a
  `PEAK` is unfoundable).

Pick the max-scoring cell(s); ties break toward the centroid. Deterministic (a scan of the
finished field), **no RNG**, so the persisted per-province field stays seed-independent.

### Anchoring the settlement

`claimFoundingCenter` today returns `bestYieldNearest(centroidX, centroidY)` for the first
settlement. Since the urban plot is `foundValue`-sited it is no longer the centroid-nearest
plot, so make the preference explicit: `claimFoundingCenter` **claims the province's primary
city plot** if free, so the founding centre (plot 0, the civic seat) lands on the bonus-optimal
urban ground. Later settlements keep the min-distance auto-spacing. Because the centre is never
a worked plot, the urban terrain's yields stay inert — the "reference/visual only" guarantee.

## Part C — web: the Development overlay

A **Development** map mode beside Nation/Culture/Faith (`docs/political-map.md`,
`web/js/overlays/political.mjs`) — a **numeric choropleth**: bucket `development()` into a
sequential ramp (see `dataviz` for a perceptual sequential palette), colour each province,
legend the ramp, reuse the political overlay's zoom-band + "no value never fills" machinery.
`build.mjs` bakes `development` onto each province record.

## Architecture mapping (files to touch)

Engine:

| File | Change |
|---|---|
| `geo/export/TerrainExporter.java` | ✅ `TERRAIN_URBAN` in `SYNTHETIC` |
| `geo/export/TerrainArtExporter.java` | ✅ `TERRAIN_URBAN` art (repurposed `Rocky*`) |
| `geo/Province.java` | `baseTax`/`baseProduction`/`baseManpower` + `development()`; `boolean city` |
| `geo/export/ProvinceHistoryExporter.java` | parse + stamp the three dev keys |
| `geo/export/CityTerrainExporter.java` (or extend `CavernExporter`) | stamp `city` from the `city_terrain` block (leaving `type` as `LAND`) |
| `geo/CityPlacement.java` (new) | the Civ4-style `foundValue` work-radius scan (bonus-weighted); deterministic, no RNG |
| `geo/ProvincePlotField.java` | after the bonus stage, tag the `foundValue`-sited core plot(s) `TERRAIN_URBAN` + `FLAT` + dev attach (more for a `city` province) |
| `settlement/ProvincePlotPool.java` | `claimFoundingCenter` claims the primary city plot |
| `settlement/Plot.java` (or the field record) | carry the `isCity` flag + concentrated `development` (data only) |

Resources: regenerated `terrains.json` / `terrain-art.json` (✅) and a re-stamped
`provinces.json` (+ the affected `map/provinces/<id>.json.gz` fields).

Web: `build.mjs` (`terrainDisplayColors`/`AUTHORED` for `TERRAIN_URBAN` ✅; bake `development`;
`bakeNifGroup` for the city sprite → `BUNDLE.cities`), `web/civ4.mjs` (the city `.nif` path),
`web/js/plots.mjs` (`citySprite` over `TERRAIN_URBAN` plots), a new
`web/js/overlays/development.mjs` + chrome/toggle. Because the live server bakes the bundle
from engine resources, `provinces.json` / `terrain-art.json` moving is a **redeploy-on-change**.

## Phased implementation plan

- **Phase 1 — the `TERRAIN_URBAN` substrate. ✅ Done.** The synthetic terrain + repurposed art
  (regenerated `terrains.json` 33 / `terrain-art.json` 25), the web colour/`AUTHORED`, and
  `UrbanTerrainTest`. Byte-identical — nothing generates the terrain yet. (An earlier cut of
  this phase added a `ProvinceType.URBAN` for wholly-urban `city_terrain` provinces; that was
  reverted when the design moved to urban-cored — cities stay `LAND`.)
- **Phase 2 — import province data.** `Province` gains the three dev fields + `development()`
  and the `city` flag; `ProvinceHistoryExporter` stamps development; the `city` flag is stamped
  from `city_terrain`. Re-stamp `provinces.json`. Pure data, provinces stay `LAND`, no terrain
  change — Dhenijansar unaffected. Test: a known province's dev + `city` flag match the source.
- **Phase 3 — the per-province urban core (Civ4 `foundValue`) + anchoring.** `CityPlacement`
  `foundValue`; `ProvincePlotField` tags the core plot(s) `TERRAIN_URBAN` + `FLAT` + dev after
  the bonus stage (denser for a `city` province); `claimFoundingCenter` anchors on the primary.
  **Behavioural but calibration-safe** — the core is the unfarmed centre, so food balance and
  the collapse profile are unchanged (re-validate the smoke tests). Tests: every province field
  has ≥1 urban core plot at a `foundValue` max (near its bonuses); a `city` province has more;
  the default colony's centre is urban; `PlotYieldTest` mean food factor still ≈ 1.0.
- **Phase 4 — the city sprite.** Bake a C2C city `.nif` → `BUNDLE.cities`; stamp `citySprite`
  over `TERRAIN_URBAN` plots in `plots.mjs`, sized by dev tier. Web-only; verify headless.
  *Follow-up:* culture → `ArtStyleType` → per-style sprite.
- **Phase 5 — the web Development overlay.** Bake `development`; add the choropleth mode +
  legend + toggle. Verify headless.

## Open questions (deferred)

- **Should urban yield later scale with development?** This cut authors a fixed urban triple
  and stores development as concentrated data. When Production/Commerce wake (`docs/plots.md`
  Phase 4b), making the city plot's commerce/production a function of `base_tax`/`base_production`
  is the first live economic use of the imported dev.
- **`base_manpower` → what?** ADM(`base_tax`)→commerce/tax and DIP(`base_production`)→
  production map onto Civ4 yields; MIL(`base_manpower`) has no yield analogue — left as raw
  data for a future manpower/military model.
- **How dense is a `city` core?** A `city` province gets "a few" urban plots; the exact count
  (fixed, or scaled by `development()`) is a Phase-3 tuning knob.
- **The full City LSystem** — out of scope; one sprite (later dev-size tiers, per-culture
  `ArtStyleType` variants). CivStudio also has no **era** axis yet, so the sprite is
  era-neutral.
- **City walls** — no wall terrain / inter-plot adjacency exists; out of scope.
</content>
