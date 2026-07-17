# Design note: urban plots & imported development

**Urban is an OVERLAY, not a terrain (2026-07-16, MAP_VERSION 8).** The synthetic `TERRAIN_URBAN`
ground was retired from plot *generation*: a city now sits ON the terrain the generator draws
(grassland/plains/hill/…). `ProvincePlotField` no longer overwrites `ground[idx]` — it keeps the
natural terrain, flattens the core to level built ground, clears the wild feature/resource (built
over), and sets a new boolean `Plot.urban()` overlay flag. The flag is threaded
`ProvincePlot → Plot → StoredPlot` (serialized in the plot cache, so it reaches the web plot JSON as
`urban:true`), and the engine's urban checks re-key onto it (`ProvincePlotPool.paveUrbanPlots` /
`bestUrbanCenter`; `TERRAIN_URBAN` stays defined in the registry but is no longer assigned). The web
side dropped `markUrbanPlots` (no client re-terraining — the ground is already natural) and reads
`q.urban` straight off the JSON.

*District view (`districts.mjs`):* every urban plot draws a **small Civ6 NEIGHBORHOOD chip**
(centred icon, not a full-hex tile) — the default district; other types emerge from the buildings a
plot raises. A plot **not linked to a live settlement reads as ABANDONED** — a desaturated/ruined
`dis-neighborhood-abandoned` variant baked in `build.mjs`; the province hosting the live colony
renders **active** neighborhoods (matched client-side via plot-bounds containment) — but only on the
plots its districts actually occupy: a colony of N districts (`ColonyView.startingDistricts`) lights
the **N urban plots nearest its centre**, so the core is live and the unbuilt outskirts stay ruins.
The POV colony's built buildings still ring its centre as button icons.

*Caravan camp rule (`MarchingCaravan.claimCampOn`):* a marching band may **not camp on an urban plot
of a province that already holds a settlement** (`ProvincePlotPool.hasSettlement()` — any owned plot);
an **abandoned** urban core (no settlement) is fair game. A nightly `Camp` is a non-owning occupant,
so it never trips the check.

*Deploy:* MAP_VERSION 7→8 → the persistent plot cache must be rebaked (with GeoNames place names, which
prod can't generate) and re-uploaded before the server rolls, else prod regenerates nameless v8 plots.
This rebake is moving to CI/CD (see `docs/client-server.md` §Deployment).

Verified end-to-end on Dhenijansar (prov 4411) in the local stack: natural terrain under the city,
small neighborhood chips, abandoned (grey) vs live (active) variants. *Everything below this line
predates the overlay model and is kept for history.*

---

**Interim visual (2026-07-13):** the urban *art* was pulled pending proper Civ6 district tiles
(`docs/civ6-art-replacement.md`). The synthetic grey-concrete `TERRAIN_URBAN` ground tile, the
Civ4 `med_europe` **city sprite** (`plots.mjs citySprite`), and the procedural **roof-lot**
footprints (`city.mjs drawLots`) were all removed — they read as ugly. The frontend now
**re-terrains each urban core plot to the province's dominant real terrain** on load
(`plots.mjs markUrbanPlots`, flagging `q.urban`), so a city site blends into its countryside
instead of showing a grey patch, and `city.mjs drawCity` draws a **subtle stone pip marker** per
urban plot so cities stay locatable. Frontend-only — the engine `TERRAIN_URBAN` substrate and the
imported development below are unchanged; the baked `terrain-tiles` URBAN cell and `assets` `city`
sprite are simply no longer referenced. Verified in-app on Dhenijansar (prov 4411). *Next: replace
the pip with Civ6 district art.*

**Status:** Phases 1–4 done and **deployed live** to `dev.civstudio.com` (2026-07-12) — the
`TERRAIN_URBAN` substrate, imported development + `city` flag, the per-province urban core
(Civ4 `foundValue`) + anchoring, and the city sprite + city info panel. Verified at 256×:
Dhenijansar shows its two urban core plots (city sprites) and the `cityRail` info panel. The
optional web Development choropleth overlay (5) remains. *Deploy caveat handled:* the
persistent AzureFile plot cache was cleared so city provinces regenerate with urban cores (see
`docs/client-server.md` §Deployment); a self-invalidating **generation-version** on the cache
path would remove that manual step (follow-up).
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
| `geo/Province.java` | ✅ `baseTax`/`baseProduction`/`baseManpower` + `development()`; `boolean city` |
| `geo/export/ProvinceHistoryExporter.java` | ✅ parse + stamp the three dev keys |
| `geo/export/CavernExporter.java` | ✅ stamp `city` from the `city_terrain` block (leaving `type` as `LAND`) |
| `geo/WorldMap.java` | ✅ `FAIL_ON_NULL_FOR_PRIMITIVES` off — absent dev/`city` defaults to 0/false (Jackson 3 is strict) |
| `geo/CityPlacement.java` (new) | ✅ the Civ4-style `foundValue` work-radius scan (bonus-weighted); deterministic, no RNG; `coreSize` = 1, or a dev-scaled cluster for a `city` province |
| `geo/ProvincePlotField.java` | ✅ after the bonus stage, tag the `foundValue`-sited core cell(s) `TERRAIN_URBAN` + `FLAT`, clearing feature/bonus (LAND provinces only) |
| `settlement/ProvincePlotPool.java` | ✅ `claimFoundingCenter` anchors on the nearest free urban plot |
| `settlement/Plot.java` | no change — a core plot is identified by `terrain().type() == "TERRAIN_URBAN"` (dev read from the province, not stored per-plot) |

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
- **Phase 2 — import province data. ✅ Done.** `Province` gained the three dev fields +
  `development()` and the `city` flag; `ProvinceHistoryExporter` stamps development (onto 4708
  provinces), `CavernExporter` stamps the `city` flag from `city_terrain` (onto 113, `type`
  unchanged). Re-stamped `provinces.json`. Pure data, provinces stay `LAND`, no terrain change —
  Dhenijansar (`city:true`, dev 12/12/6 = 30) keeps its farmland; full suite green (engine 260,
  server 32). Needed `FAIL_ON_NULL_FOR_PRIMITIVES` off in `WorldMap`'s mapper (Jackson 3 fails
  an absent primitive record component where Jackson 2 defaulted it). Test:
  `WorldMapTest.loadsDevelopmentAndTheCityFlag`.
- **Phase 3 — the per-province urban core (Civ4 `foundValue`) + anchoring. ✅ Done.**
  `CityPlacement` scores each land cell by reachable bonuses/yield/river within a work radius
  and returns the core cell(s); `ProvincePlotField` tags them `TERRAIN_URBAN` + `FLAT`
  (clearing feature/bonus) after the bonus stage, on **LAND provinces only** (underground holds
  and special-terrain wilderness keep their character; every `city_terrain` province is LAND).
  An ordinary province gets one core plot; a `city` province a dev-scaled cluster (Dhenijansar,
  dev 30 → 2). `ProvincePlotPool.claimFoundingCenter` anchors the first settlement's centre on
  the nearest free urban plot. **Behavioural but calibration-safe** — the core is the unfarmed
  centre and low-food urban plots are deprioritised by `bestYieldNearest`, so food balance and
  the collapse profile held (full suite green: engine 263, server 32). Tests:
  `UrbanTerrainTest` (one core plot for an ordinary LAND province, a denser core for
  Dhenijansar with its hinterland kept), `ProvincePlotPoolTest.aColonyAnchorsItsCentreOnThe
  CityUrbanCore`. *Note:* persisted province fields (`map/provinces/<id>.json.gz`, gitignored)
  must regenerate — the live server's field cache needs invalidating on the next deploy.
- **Phase 4 — the city sprite + city info panel.** *(Code done; browser-visual check + deploy
  pending.)* Bake a C2C city `.nif` (`med_europe.nif`) → `BUNDLE.trees.city`; stamp
  `citySprite` over `TERRAIN_URBAN` plots in `plots.mjs`, sized by the province `dev` tier
  (`WorldBundle` now emits `dev`/`city` on each province node). A `cityRail(p)` in `panel.mjs`
  reuses the right-hand rail (in place of the generic province detail) to show a selected
  city's development / rank / trade good / politics / urban-core size — Dhenijansar first. Data
  pipeline verified against a local server (`/api/plots/4411` → 2 urban core plots;
  `/api/bundle` → `trees.city` + Dhenijansar `{dev:30, city:true}`). **Remaining:** a browser
  check of the sprite at the 256× "100%" zoom (and panel), then the deploy (see
  `docs/client-server.md` §Deployment — CI rebake **then** the server roll, no cache delete, + SWA). *Follow-up:* culture → `ArtStyleType` → per-style sprite; the city panel showing a live
  founded settlement's sim stats (population/firms) once one is founded at Dhenijansar.
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
