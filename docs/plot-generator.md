# Plan: procedural, province-driven plot terrain (C2C-style)

**Goal.** Stop assigning plot **terrain** (and features) from the imported `terrain.bmp` pixels and
instead generate them procedurally with a faithful **C2C-planet-generator** algorithm, driven by each
province's climate parameters — so all 33 terrains appear *properly* and climate-appropriately, instead
of the impoverished handful the pixel palette yields. The real Anbennar **geography stays**: province
shapes, the land/water mask, coastlines, rivers, and the mountain backbone.

Companion investigation lives in this doc's design; the C2C algorithm inventory is in the conversation
that produced it (terrain set + temperature-band + diversify logic from `C2C_Planet_Generator_0_68.py`).
Related: `docs/plots.md`, `docs/province-plots.md`, `docs/underworld.md` (special terrains). This
supersedes the map-pixel terrain path those describe.

## Decisions (locked with owner, 2026-07-12)

| Question | Decision |
| --- | --- |
| Terrain source | **Fully procedural** — `terrain.bmp` unused for terrain; 100% from province climate/winter/latitude + the C2C temperature×humidity algorithm |
| Features | Procedural too (C2C `FeatureGenerator` + terrain-implied); drop the `trees.bmp` feature hints, for consistency |
| Relief (flat/hill/peak) | **Hybrid** — real `heightmap.bmp` peaks (the mountain backbone) + `ReliefGenerator` C2C-range variation (≈ the current `rougher()` compose) |
| Geography kept from raster | province shape / land-water mask (`provinces.bmp`), coastlines, **rivers** (`rivers.bmp`), heightmap elevation |
| Special surface terrains | `province.type()` overrides the climate pick (a province typed `ANCIENT_FOREST`/`GLACIER`/… gets its terrain regardless of climate) |
| Determinism | **Seed-independent** (keep) — the canonical `Stream.TERRAIN` still excludes the game seed, so the field is identical every run and the shared plot cache/serving model is unchanged |
| Spatial coherence | **Coherent patches** — a value-noise / seed-and-spread **region pass** groups plots, and the C2C weighting picks per region (natural terrain patches, not per-plot salt-and-pepper) |
| Temperature | **Per-plot world latitude** — each plot's world latitude (province lat + plot-y offset) sets its temperature, giving a gradient across large north-south provinces |
| Food balance | **Accept the shift** — richer climate-driven terrain will move per-plot food yields; recalibrate the food economy + collapse-timing tests afterward, don't constrain the terrain to preserve today's balance |

## Why this is mostly *promotion*, not new code

The current live path is `MapTerrainCodec.ground(pixel)` **primary**, with the procedural
`TerrainGenerator` + `LatitudeClimate` only as the **fallback for unmapped pixels**
(`ProvincePlotField.java:186-188`). CivStudio already ships faithful C2C ports —
`TerrainGenerator` (climate-weighted pool), `LatitudeClimate` (C2C latitude→temperature + cold pool),
`ReliefGenerator` (C2C flat/hill/peak ranges), `FeatureGenerator` (C2C seed-and-spread), and
`ClimateProfile` (province → temperature+humidity). The work is to **promote the procedural generator to
primary**, complete the C2C two-stage terrain algorithm on top of it, and drive it from province params.

---

## The C2C algorithm to reproduce (from the script)

Per plot, in two stages, a **weighted probability pick** (matching `PrivateMaps/C2C_Planet_Generator_0_68.py`):

**Stage 1 — base terrain by temperature × humidity** (overlapping bands; humidity modulates weights):

| Terrain | Temp band (°C) | Weight |
| --- | --- | --- |
| `TERRAIN_DESERT` | > 30 | `7·(1.5 − humidity)` |
| `TERRAIN_PLAINS` | 15…39 & −2…25 | 7 |
| `TERRAIN_GRASSLAND` | 4…30 | `7·(humidity + 0.5)` |
| `TERRAIN_MARSH` | −5…18 | 10 |
| `TERRAIN_TAIGA` (C2C `terrainTundra`) | −10…10 | 7 |
| `TERRAIN_TUNDRA` (C2C `terrainPermafrost`) | −10…−20 | `15·(humidity/2+0.75)` |
| `TERRAIN_ICE` (C2C `terrainSnow`) | < 0 (esp. < −30) | `7–15·(humidity/2+0.75)` |

**Stage 2 — "Diversify"** (replace each base with a weighted variant):
`Desert → {Desert:2, SaltFlats:1, Dunes:4, Scrub:3}` · `Plains → {Plains:3, Barren:1, Rocky:2}` ·
`Grass → {Grass:2, Lush:3, Muddy:1}`.

**Water:** `TERRAIN_COAST` / `TERRAIN_OCEAN`→`SEA` (existing water path adds the `_POLAR`/`_TROPICAL`
climate suffix by latitude). Features (6): `FOREST, JUNGLE, SWAMP, OASIS, FLOOD_PLAINS, ICE`.

Coverage note: these 16 C2C terrains + the **7 Anbennar specials** (`province.type`) + the water climate
suffixes + `CAVERN`/`URBAN` = CivStudio's full 33. Nothing else needs inventing.

---

## Design

### Inputs
- **humidity** (per province) — from `climate` + `monsoon`, via `ClimateProfile.humidity()` (0.10–0.95).
- **temperature** (**per plot**) — each plot's **world latitude** = province lat + (plot-y → degrees offset
  from the province bounding box), fed through `LatitudeClimate`/`ClimateProfile` (`≈ 40 − 0.6·|lat|` minus the
  `winter` offset). So a tall province warms toward its equatorward edge instead of reading one flat value.

### Region coherence pass (new — runs before Stage 1)
Partition the province's land plots into **regions** with a deterministic value-noise / seed-and-spread field
(same shape as `FeatureGenerator`'s spread, on the canonical stream). Stage 1 then draws **once per region**
(not per plot), so terrain forms natural contiguous patches. Despeckle still runs to clean edges.

### Per-plot terrain (canonical `Stream.TERRAIN` rng, unchanged draw-order)
1. **Stage 1** — per region, weighted pick from the plot's temperature-band array (humidity-modulated) → base terrain.
2. **Stage 2** diversify → detail variant (per plot, so a patch still has fine variation).
3. **Special override** — `province.type()` ∈ {ANCIENT_FOREST, GLADEWAY, FEY_GLADEWAY, BLOODGROVES,
   MUSHROOM_FOREST, SHADOW_SWAMP, GLACIER} ⇒ that terrain (the existing `SPECIAL_POOL` pass).
4. **Water plots** — existing `generateWater` (coast/sea + climate suffix, near-shore shelf).
5. **Despeckle** — keep the majority-smoothing pass to clean region edges.

### Relief (hybrid) & rivers & coast — keep
- Relief stays the `ReliefGenerator` + heightmap compose (`rougher()`), but **repoint the "map relief"
  input from the `terrain.bmp` palette to `heightmap.bmp` elevation** — with terrain pixels gone, peaks/hills
  must derive from elevation thresholds, not `MapTerrainCodec.relief(terrainIndex)`. (Implementation check.)
- Rivers, the coast mask, and the land/water mask keep coming from the raster (`ProvinceMask`).

### Features (procedural)
`FeatureGenerator` seed-and-spread + terrain-implied features (forest on cold/temperate, jungle on hot-wet,
swamp on marsh, oasis in desert, flood-plains on river). Drop the `trees.bmp` (`treeFeatureKey`) hints.

---

## Code changes

- **`ProvincePlotField.generate`** (`geo/ProvincePlotField.java:159`) — the one authority. Replace the
  `MapTerrainCodec.ground` primary with the C2C two-stage procedural pass; drop the `terrain.bmp`/`trees.bmp`
  terrain+feature reads; keep the relief (repointed to heightmap), water, special-override, bonus, city, and
  despeckle passes. Preserve the **row-major, one-draw-per-cell** order so the canonical stream stays byte-stable.
- **`TerrainGenerator`** — complete it into the faithful C2C two-stage weighting from `(temperature, humidity)`
  (port per the `port-c2c-generator-faithfully` note — mirror the script's constants/order). Reuse
  `ClimateProfile`/`LatitudeClimate` for the inputs; reuse the existing `probabilityArray`-style weighted pick.
- **`MapTerrainCodec`** — `ground` / `relief(terrainIndex)` / `treeFeatureKey` / `terrainFeatureKey` /
  `isWoody` become dead for the primary path (keep `water(...)` for the shelf; delete or retire the rest).
  `ProvinceRaster` can stop loading `terrain.bmp`/`trees.bmp` for generation (still needed by the dev-time
  web terrain bake? — no, that reads Civ art, not these rasters; confirm before dropping the load).
- **`ProvincePlotStore.GEN_VERSION`** (`:49`) — bump `2 → 3`. This invalidates every plot cache
  (`map/provinces/*.json.gz` + the prod volume) and the client `?v=` URL, forcing regeneration.

## Determinism

Unchanged model: the seed-independent **canonical `Stream.TERRAIN`** (`RngSeed.forProvinceCanonical`) +
row-major draw order = identical field every run/seed, persisted once. The new pass must consume the stream
in the same deterministic order (fixed draws per cell whether or not a variant/feature lands).

## Consumers (contract preserved)

The plot's serialized fields are **unchanged** (`terrain, feature, plotType, river, elevation, coast, bonus`),
so farm-TFP food yield (`Plot.yields`), caravan A* routing (`ProvincePlotPool.corridor`), plot claiming, and
the web viewer (`plots.mjs`) all keep working — they just see richer, climate-driven terrain. The Civ6 art
(Phases 1–3) keys off terrain/feature type, so it renders the new terrains automatically.

## Deployment (per the runbook + `always-az-deploy-on-change`)

A `GEN_VERSION` bump is a generation change: **rewarm** (`WorldPlotGenerator`) → deploy the server →
**clear the persistent plot cache** on the prod volume (else stale `v2` blobs serve) → rebake/redeploy the
bundle + static site. This is server-affecting engine data, so it must go out via `az`, not just SWA.

## Verification

1. **Terrain variety** — regenerate a sample of provinces across climates; assert all base terrains appear
   and are climate-appropriate: arctic/severe-winter provinces read cold (taiga/tundra/ice), tropical wet read
   grass/jungle/marsh, arid read desert/dunes/scrub. No province should be a flat single terrain.
2. **Special terrains** — provinces typed `ANCIENT_FOREST`/`GLACIER`/`MUSHROOM_FOREST`/… still show their
   terrain (override intact).
3. **Determinism** — same province regenerates byte-identical across two seeds; `GEN_VERSION` bumped.
4. **Geography intact** — coastlines, rivers, and the mountain backbone still match the real map (heightmap
   relief, raster rivers).
5. **In-app** — `mvn -pl civstudio-engine install` → `spring-boot:run` → `tools/webverify` screenshots across
   several provinces/climates; confirm the Civ6 terrain art renders the new distribution.
6. **Tests** — `mvn test` (scenarios smoke-test terrain-dependent food balance; watch for collapse-timing shifts
   since food yield now tracks climate-driven terrain).

## Resolved decisions & remaining risks

- ✅ **Coherence** — coherent-patch region pass (above), not raw per-plot noise.
- ✅ **Temperature gradient** — per-plot world latitude (above).
- ✅ **Determinism** — seed-independent, shared cache unchanged.
- ✅ **Food balance** — accept the shift; recalibrate afterward (don't constrain terrain to preserve it).
- ⚠️ **Relief repoint** (implementation) — confirm relief no longer depends on the terrain palette once
  `terrain.bmp` is dropped; derive peak/hill from `heightmap.bmp` elevation thresholds + `ReliefGenerator`.
- ⚠️ **Food-balance fallout** — climate-driven yields will shift the colony-collapse timing the smoke tests
  assert (`colony-collapse-accepted`); plan to retune the food economy / test expectations after it lands.
- ⚠️ **Region-pass tuning** — patch size/count is a knob; too coarse = uniform provinces, too fine = noise.
  Tune against real output.

---

## As built — increment 1 (2026-07-12)

Shipped: **procedural terrain is live and primary.** `ClimateTerrainGenerator` (the C2C two-stage
temperature×humidity port) drives `ProvincePlotField.generate`; `terrain.bmp` is no longer read for the
biome (still read for the hybrid mountain-relief signal via `MapTerrainCodec.relief`). `GEN_VERSION` 2→3.
All 33 terrains appear climate-appropriately (verified: arid→dunes/scrub/desert, tropical→lush/grass,
temperate→grass/plains/marsh cooling to taiga at latitude, cold→tundra/permafrost). Covered by
`ClimateTerrainGeneratorTest`; the scenario smoke tests still pass (the food-balance shift did not break
the clean-collapse assertions).

Province-type decisions implemented:
- **IMPASSABLE** → a climate-appropriate **barren** pool (`ClimateTerrainGenerator.barren`): hot→desert/
  badland, cold→rocky/permafrost, temperate→rocky/scrub, + mountainous relief.
- **city_terrain** (`province.city()`) → **fully urban** — every plot paved `TERRAIN_URBAN`, no farmland/
  features/resources (the render layer covers it; a dedicated paved texture is deferred, and long-term the
  urban plots become Civ6 **district tiles**).
- **Surface special terrains** (ancient_forest, glacier, …) → signature terrain (82%) + **climate-aware
  filler** (a northern ancient forest fills with taiga, not grassland). **Underground** types keep the
  fixed cavern pool + flattened floor. `DWARVEN_HOLD_SURFACE` stays cavern (owner's call).
- **Bonus density** → **stochastic rounding** in `BonusGenerator` (the richer terrain spreads each bonus
  across fewer matching plots; probabilistic rounding preserves expected density so small provinces still
  draw resources). **Retuned (increment 1b, `GEN_VERSION` 3→4):** `DENSITY_SCALE` 0.275→**0.055** (≈5×
  sparser — the procedural terrain made too many plots eligible, blanketing the map), and **wastelands
  (`IMPASSABLE`) now carry no resources at all** — the bonus pass is skipped for them (barren ground is
  worked by no one). Covered by `ProvincePlotFieldTest.wastelandsCarryNoResources` +
  `BonusPlacementTest` (density band retuned, upper bound now guards the 5× regression).

Anbennar calibration deviations from pure C2C (documented in `ClimateTerrainGenerator`): a **dry-desert
gate** (an `arid` province reads desert across its latitude range, not only when scorching) and a
**humidity-gated marsh** (a dry province stays steppe, not wetland).

**Still pending** (planned increments): the **region-coherence pass** (currently relies on the existing
despeckle for patch coherence), **per-plot world latitude** (currently one temperature per province), and
making **features fully procedural** (they still read the `trees.bmp` hints). Plus the food-economy
recalibration the yield shift invites.

---

*Planned 2026-07-12. Decisions locked with owner: fully-procedural terrain (ignore `terrain.bmp`), hybrid
relief (heightmap backbone + C2C variation), geography kept (shape/rivers/coast), **seed-independent**,
**coherent-patch** distribution, **per-plot-latitude** temperature, and **accept the food-balance shift**.
Largely promotes the existing faithful C2C ports from fallback to primary. When it lands, update
`docs/plots.md`/`province-plots.md` and the `docs-stale-terrain-pipeline` note, and cross-link here.*
