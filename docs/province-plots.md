# Design note: province plot field — many settlements sharing generated plots

**Status:** Proposed (supersedes the dropped Civ4/WBSave export idea — the
generation algorithm survives, the file format does not).
**Date:** 2026-06-30
**Depends on:** the per-settlement plot model (`docs/plots.md` —
`settlement/Plot.java`, the `TravelLadder` Fibonacci commute ladder, the
`BuilderFirm`/`BuildProject` clearing flow, `geo/TerrainGenerator.java`,
`Plot.yieldFactor`/`Settlement.plotYieldFactor`, the center-grouping rule); the
geography axis (`com.civstudio.geo` — `Province` incl. `plots`/`waterPlots`/`type`,
`Climate`/`WinterSeverity`/`Monsoon`) and the committed Anbennar rasters under
`data/` with the Phase-5 raster reader (`docs/geography.md`); and the curated
Civ4/C2C content layer (`geo/TerrainRegistry.java`).
**Port gaps:** `docs/c2c-generator-port.md` — a slice-by-slice plan for bringing this
generator into faithful agreement with the upstream `data/civ4/C2C_Planet_Generator_0_68.py`
(feature seed-and-spread + terrain-rewriting, peak seeding, appearance-probability scatter,
oasis/diversification, bonus rules), noting what eos already supersedes with the real map.
**Related:** `docs/geography.md` — this **decides its open question** "Map scale
vs. colony scale": **a province holds multiple settlements**, which share one
generated plot field. `docs/village-founding.md` / `docs/caravan-trade.md` found
settlements into provinces; this note is what their second-and-later settlements
in a province draw their land from. The generation algorithm is what an earlier
"render a province as a Civ4 map" sketch described (province silhouette at 1
pixel = 1 plot, climate→terrain fill); that file-export framing is **dropped** —
the silhouette and the terrain fill are kept, but they produce **in-sim `Plot`s**,
not a `.CivBeyondSwordWBSave`.

> **Update (persisted, seed-independent field).** A province's plot field is now
> generated **seed-independently** (off `RngSeed.forProvinceCanonical` — province id
> only, not the run seed) and **cached** to `.plot-cache/v<MAP_VERSION>/<id>.json.gz`
> (gzipped JSON, ~25× smaller than raw) by `settlement/ProvincePlotStore`. This is the **same
> shared cache** the server's `PlotService` serves `/api/plots/{id}` from (byte-identical format,
> configurable via `civstudio.plots.cache-dir` — the server pushes it into the store at startup), so
> the sim and the web map share one cache instead of the sim writing a second copy into the source
> tree. The directory is **versioned by `MAP_VERSION`**, so a generation-changing bump orphans the
> old fields and everything regenerates fresh — otherwise the sim would keep loading a stale field
> while the map served the new one. The first run to
> need a province generates and writes it; every later run (any seed) **loads the committed
> canonical field** instead of regenerating — a province's geography is a property of the
> map, not of a run. This eliminates the repeated generation cost (notably for the caravan
> march, which crosses many provinces) at the cost of making colony farm terrain fixed per
> province across seeds (the aggregate food calibration is preserved — `PlotYieldTest` mean
> food factor ≈ 1.0 still holds). The per-seed determinism note below is superseded by this.

## Motivation

Today a `Settlement` grows **its own** `List<Plot>` one plot at a time, capped at
`province.plots`, via the `BuilderFirm` (`docs/plots.md`). That implicitly makes a
province a single colony's private plot budget. But a real province is larger than
one settlement — it should hold **several** settlements, each occupying a patch of
the same land.

This note inverts the ownership: the **province** generates its full plot field
once — its real land pixels as `Plot`s, terrain and relief from its climate — and
**multiple settlements claim plots from that shared field**, each taking the best
free land around its own center. The province tracks which settlement owns each
plot. This is the substrate a province needs to hold more than one settlement, and
it grounds plots in the province's real shape (from the `data/provinces.bmp`
raster) instead of an abstract count.

## Scope

**In:**

- **Lazy per-province plot field.** The first time a province is needed, it
  generates its plot field: its land pixels (the `province.plots` count) become
  `Plot`s, at **1 raster pixel = 1 plot**, terrain/relief/rivers generated from the
  province's single `Climate`/`WinterSeverity`/`Monsoon` by a **Java port of the
  Caveman2Cosmos planet generator's per-tile stage**
  (`data/C2C_Planet_Generator_0_68.py`) — spatially-coherent relief (clustered
  peak/hill ranges), water-seeded feature growth, river flood plains, and resource
  placement — over the province's real silhouette, on a per-**province** terrain
  `Rng`. Plots start **free**.
- **Multiple settlements per province**, sharing that field.
- **Hybrid ownership.** Free plots belong to the province; **claiming a plot
  transfers it to the claiming settlement**, which keeps its existing `List<Plot>`
  (the `docs/plots.md` per-settlement model survives — its plots are now claimed
  province plots). The province tracks plot → owner.
- **Per-settlement Fibonacci ladder.** Each settlement has a **center**; it claims
  the best free plots clustered around it, on its own travel ladder (the existing
  `TravelLadder` generalized so a plot's rung follows its real distance from that
  settlement's center).
- **"Best" = weighted yield + proximity** — a free plot's score trades its yield
  (food, `Plot.yieldFactor(NECESSITY)`) against its travel cost, the same trade-off
  as today's `workFactor`.
- **Reserve at founding, clear gradually.** A new settlement auto-places its center
  in the best free region (spaced from existing settlement centers), **reserves**
  its best free plots (ownership transfers), and the `BuilderFirm` develops them
  over time via the existing `BuildProject` flow.

**Out (deliberately):**

- **No Civ4/WBSave file export.** The `.CivBeyondSwordWBSave` writer, exporter, and
  the "play a province as a Civ4 map" framing are dropped. What is kept from that
  work is the C2C *generation algorithm* (its per-tile relief/terrain/feature
  stage), ported to Java to produce in-sim `Plot`s — not a file.
- **No whole-world projection, no new raster import, no boundary fill.** The
  in-sim field is the province's own land pixels as plots; out-of-province pixels
  are not plots, so the "coast/peak margin" of the file-export sketch is moot.

## The data and generation

A province's field is built from the committed rasters on demand (the Phase-5
raster-reading approach, `docs/geography.md`):

1. **Mask.** `ProvinceRaster` reads `data/definition.csv` (the colour↔id map) and,
   lazily, `data/provinces.bmp`/`data/rivers.bmp`; `mask(id)` returns the province's
   `ProvinceMask` — its bbox grid of land cells (its own pixels — the
   discarded-but-now-needed silhouette flagged in the geography work) and river flags.
2. **Per-tile generation (the C2C port).** Over the mask, a **Java port of the
   Caveman2Cosmos planet generator's per-tile stage** paints each land cell, seeded
   by the province's `Climate`/`WinterSeverity`/`Monsoon` and drawn off a
   per-**province** terrain `Rng` (so a province's field is deterministic per seed
   and shared by all its settlements). The C2C generator's continent/ocean/latitude
   machinery is irrelevant to a single fixed-shape, single-climate province; only its
   per-tile stage is ported, in slices:
   - **terrain** — the ground now comes from the **real EU4 `data/terrain.bmp`**
     (full-resolution, 1 pixel = 1 province pixel), decoded per plot by
     `MapTerrainCodec.ground` (the EU4 palette → curated Civ4 `Terrain`, transcribed
     from `data/terrain.txt`'s `terrain { }` block). The climate-weighted pool
     (`TerrainGenerator.next`) is kept as the **fallback** for water/coastline/
     unmapped pixels and for a province-less colony. This replaces the interim
     climate-pool ground: a province now reads as the map actually paints it (e.g.
     Dhenijansar as plains/grassland, not a tropical lush/marsh draw). *(done — real map)*
   - **de-speckle** — because terrain is 1 raster pixel = 1 plot (a single-pixel sample,
     no footprint aggregation) and the latitude-cooling / special-pool passes draw each
     cell independently, the raw ground is **salt-and-pepper** — an isolated 1-plot speck
     of a different terrain in most cells (measured ~0.5 in cold provinces), which reads as
     a hard grid at the deep city-builder zoom no matter how the web blends plot edges.
     `ProvincePlotField.despeckle` runs a few passes of **majority (mode) smoothing** over
     the ground grid (each land cell adopts the terrain most common among its 8 neighbours
     when that plurality outnumbers its own) — coalescing the speckle into coherent regions
     while keeping each terrain's overall share. It reads a per-pass snapshot (order-
     independent) and consumes **no rng**, so the deterministic terrain draws are untouched.
     *(done — coherent regions)*
   - **relief** — `ReliefGenerator` ports the C2C `addPeaks`/`addHills`: seed by
     probability, then **grow into clusters/ranges** (so a province reads as a
     landscape, not scattered noise), an `IMPASSABLE` province mountainous. The
     **real map's** hills/mountains (EU4 folds relief into the same `terrain.bmp`
     palette — `hills`/`highlands`/`mountain`/`snow`) are lifted onto the orthogonal
     `PlotType` axis by `MapTerrainCodec.relief` and composed as the **rougher** of
     the two, so map mountains survive while ordinary ground keeps the generator's
     coherent ranges. *(done)*
   - **features** — `FeatureGenerator` ports the C2C water-seeded growth: vegetation
     seeds along rivers and the coast and **spreads inland** with distance decay
     (jungle in a hot climate, forest otherwise), plus river **flood plains** on flat
     riverside plots. The vegetation **amount** is now the real wooded fraction from
     **`data/trees.bmp`** (decoded by `MapTerrainCodec.isWoody`): `trees.bmp` is
     ~1/7.7 resolution (732×266 vs the 5632×2048 province raster) — far too coarse
     to place individual forest pixels on a small
     province — so it drives the spread **density** rather than a per-pixel feature,
     while the climate still sets the **kind**. `ClimateProfile` reduces the
     province's climate to the C2C temperature/humidity scalars the stage reads.
     *(done — real map density)*
   - **resources** — `BonusGenerator` wakes the dormant `Bonus` data: the C2C
     `addBonuses` delegates to the engine, which places each resource by its own
     terrain/feature/relief/latitude constraints — exactly the constraints the
     `Bonus` record already carries — so placement honours them (sparse,
     eligibility-gated). **Unchanged by the real-map sourcing above** — it consumes
     whatever terrain/relief/feature a plot ends up with, so resources now simply sit
     on the map's authentic ground. *(done)*
3. Each land cell becomes one `ProvincePlot` (raster `(x, y)`, river flag, terrain,
   relief, feature). The plot count equals `province.plots` (the land-pixel count) —
   the finite pool every settlement in the province shares.

Generation moves no money and consumes only the terrain `Rng` (salted apart from
the economic stream), exactly as `TerrainGenerator` does today.

## The model (ownership, placement, claiming)

- **`Plot` gains a real position and an owner.** Today a plot has only a ladder
  `index`; now it carries its raster `(x, y)` and a nullable owning `Settlement`
  (free when null). The ladder `index` becomes **per-settlement** — a plot's rung
  in a settlement's ladder is its rank by real distance from that settlement's
  center (so `plotTravelTime` still reads `2·T(rung)`, but the rung now follows
  geography).
- **Province owns the field; settlements claim into it.** `Province` (or a
  per-province holder the `WorldMap`/`GameSession` caches) owns the generated
  `List<Plot>` and the plot→owner map. `Settlement.claimPlot` sources a free plot
  from the province and transfers ownership; `vacatePlot` frees the plot for
  re-seating but **keeps the colony's ownership** (its territory persists as firms
  come and go) — only the colony's **death** returns its plots to the pool
  (`releasePlotsToPool`, Phase 4a).
- **Center placement.** A founding settlement's center is, for the first settlement
  in a province, the best-food free plot near the province centroid; for a later one,
  the free plot that **maximizes the minimum distance** to the other settlements'
  claimed plots (min-distance auto-spacing) — so settlements spread out rather than
  stacking.
- **Best-plot claim.** From its center, a settlement claims the **best-food plot
  among its nearest few** free plots (proximity picks the band, food yield picks
  within it — `bestYieldNearest`), assigning successive Fibonacci rungs in claim
  order, preserving today's `TravelLadder` index→time semantics. Kept
  proximity-dominant so the nearest-first growth and the food-balance calibration
  hold (Phase 4b/4c).
- **Founding reserves, the builder clears.** At founding the settlement reserves
  the footprint its founding firms need (the necessity sector sized to its labor
  force, per `docs/food-balance.md`); ownership transfers immediately, and the
  `BuilderFirm` clears each reserved plot over time via `BuildProject` (unchanged).
  Later growth (the ruler's dynamic firm provisioning) claims the next-best free
  plot the same way.
- **`province.plots` is now the shared pool size**, not a per-settlement cap. A
  province runs out of land when its free plots are gone across **all** its
  settlements; a settlement stops growing when no good free plot remains near it.

## Architecture mapping

- **`Province` / a per-province plot holder** gains the generated `List<Plot>`, the
  plot→owner index, the mask read on demand, and the free-plot queries
  (`bestFreePlotNear(center, weights)`, `claim(plot, settlement)`, `free(plot)`).
  Cached per `GameSession` like the `WorldMap`.
- **`settlement/Plot.java`** gains `(x, y)` and an owning-`Settlement` field; the
  ladder `index` moves from intrinsic to per-settlement (computed at claim time).
- **`Settlement`** keeps its `List<Plot>` and `claimPlot`/`vacatePlot`/
  `getPlotCount` API, re-pointed to source from the province field; `getMaxPlots`
  becomes "free plots reachable near my center," and `hasRoomToExpand` follows.
- **`TravelLadder`** is evaluated per settlement (distance rank from its center)
  rather than from a single intrinsic index.
- **`TerrainGenerator`** generalizes to fill a whole mask from one province climate,
  on a per-province terrain `Rng`.
- **`BuilderFirm`/`BuildProject`** are unchanged — they still clear one reserved
  plot at a time; they now clear *claimed province plots* rather than self-created
  ones.
- **Reporting.** Two printers (`io/printer/`) make the field visible:
  `ProvinceInventoryPrinter` (a monthly tally of a colony's claimed plots by
  terrain/relief/feature/bonus, with a developed count — settlement-level, so the
  multi-colony merge shows Upper's vs Lower's holdings side by side) and
  `PlotMapPrinter` (a one-time dump of the whole province field — every plot's
  `x`/`y`/terrain/relief/feature/bonus/owner). Wired by `addPlotInventoryPrinters`
  into the province-founded scenarios (`HomogeneousEconomy`, `TwinSettlementEconomy`).
  Bonus is reported but still dormant in `Plot.yields()`.

## Caveats

1. **Large provinces = many plots.** At 1 px/plot a big province generates hundreds
   to a few thousand `Plot`s. Generation is **lazy and per-province** (only
   provinces with a settlement build a field), so the cost is paid only where
   colonies actually sit.
2. **Distance metric inherits Mercator stretch.** Plot positions are raster pixels,
   so the proximity term uses Mercator-stretched distance (tall up north). A
   `cos(latitude)` correction on the y-axis is an easy refinement if it matters.
3. **Rivers as a plot attribute**, not Civ4 river edges (that edge-based concern was
   a WBSave artifact, now gone) — a river pixel flags its plot (floodplain / yield).

## Decided (from the design Q&A)

- **Ownership** — hybrid: free plots are province-level, claiming transfers a plot to
  the settlement (the per-settlement `List<Plot>` stays).
- **"Best" plot** — weighted yield + proximity.
- **Ladder** — per-settlement, measured from each settlement's own center; new
  settlements auto-place in the best free region, spaced from existing ones.
- **Claiming** — reserve the footprint at founding, then clear gradually via the
  `BuilderFirm`.
- **Secondary defaults** — yield basis = food (`yieldFactor(NECESSITY)`); center =
  best free plot by `(yield − distancePenalty)` with minimum spacing; ladder rung =
  claim order over distance-sorted plots; no boundary fill.

## Phased implementation plan

- **Phase 1 — province plot field + mask + the C2C per-tile port.** A per-province
  plot field that lazily reads the province silhouette from `data/provinces.bmp` and
  paints it via the Java port of the C2C planet generator's per-tile stage; plots
  start free, no settlement wiring — pure generation, tested for the
  `province.plots` count and per-seed determinism.
  - *Slice 1 (done):* the substrate (`ProvinceRaster` → `ProvinceMask`), the
    C2C-ported **relief** clustering (`ReliefGenerator`), and the field assembler
    (`ProvincePlotField`).
  - *Slice 2 (done):* the C2C water-seeded **feature growth** (`FeatureGenerator` —
    forest/jungle seeded along water, spread inland) + river **flood plains**, with
    `ClimateProfile` supplying the C2C temperature/humidity. Covered by
    `ProvincePlotFieldTest` (silhouette/count, determinism, relief clusters, climate
    vegetation + flood plains).
  - *Slice 3 (done):* **resource** placement (`BonusGenerator`) — the C2C `addBonuses`
    delegates to the engine, so this wakes the dormant `Bonus` data and places each
    resource by its own terrain/feature/relief/latitude constraints. Covered by
    `ProvincePlotFieldTest` (placed only where eligible; determinism includes bonus).
  - *Deferred:* the C2C **temperature→terrain** replacement — the climate pool is the
    better interim ground until the feature-driven terrain rewriting is ported (a hot
    climate's raw C2C terrain is desert until features convert it).

  With slices 1–3 the **per-tile generation port is functionally complete** (relief,
  features, resources — only the deferred terrain refinement remains); the next major
  work is the ownership/claiming model in **Phases 2–4** below.
- **Phase 2 — ownership + single-settlement claim.** Re-source `Settlement`'s plots
  from a shared province field, in two sub-slices:
  - *Slice 2a (done):* the claimable pool — additive, zero behaviour change. `Plot`
    gains its raster `(x, y)`, its `Bonus`, a settable ladder `index`, and a nullable
    owning `Settlement` (the existing constructor untouched). `ProvincePlotPool`
    (cached per province on `GameSession`, off a `RngSeed.forProvince` terrain stream)
    builds claimable `Plot`s from the Phase-1 geo field with `claim`/`release`
    ownership. `Settlement` is **not** rewired, so the full suite stays green
    (`ProvincePlotPoolTest`).
  - *Slice 2b (done):* `Settlement.generatePlot` now **claims from the province pool**
    for a province-founded colony — the free plot nearest its **center** (the first
    plot it claims, anchored at the plot nearest the province centroid), growing
    outward; the existing found/grow/peak/seat machinery is unchanged, so a claimed peak
    still consumes a ladder rung (rough country pushes workable land out) exactly as
    before, and the rung stays the claim order. A province-less colony keeps the legacy
    per-plot terrain draw. The whole suite stayed green with **no retuning** — the pool
    uses the same climate→terrain distribution, so the food-factor calibration and the
    collapse profile held; `ProvincePlotPoolTest` adds a check that a province colony's
    plots are pool-sourced and pool-owned. (A vacated plot stays owned by the colony,
    re-seatable; releasing back to the province pool is a Phase-4 concern, when several
    settlements share one province.)
- **Phase 3 — multiple settlements in one province. (Done.)** Pulled the multi-settlement
  payoff forward into a concrete scenario: **Upper and Lower, both founded into
  Dhenijansar**, sharing its 74-plot pool (`simulation/TwinSettlementEconomy`,
  replacing the old two-province `HanseaticEconomy`).
  - *Slice 3a (done):* the shared pool is now **thread-safe** (several settlements claim
    concurrently under the lockstep `SessionRunner`); a settlement's founding center is
    placed by **min-distance auto-spacing** (`claimFoundingCenter` — the free plot
    maximizing the minimum distance to other settlements' claimed plots; the centroid for
    the first), then `claimNearest` grows it outward. Growth is **pool-aware and graceful**:
    `canAcquirePlot` checks the shared pool's free count (the real limit when settlements
    compete), so `hasRoomToExpand`/`requestGrowth` stop instead of crashing when the pool
    drains, and a lost claim race returns cleanly. Single-colony behaviour is unchanged
    (equivalent when one colony owns the whole pool). `ProvincePlotPoolTest` covers two
    settlements claiming spaced, disjoint plots from one pool.
  - *Slice 3b (done):* `TwinSettlementEconomy` founds Upper + Lower into Dhenijansar at
    default scale and runs them concurrently; both **compete for the 74 plots** until the
    pool drains, then collapse (sooner than a lone colony, crowding one small province).
    `TwinSettlementEconomyTest` is the concurrent smoke test (both run clean over the
    shared pool to dissolution); the old `HanseaticEconomy`/`HanseaticEconomyTest` are
    removed. Full suite green.
  - *Deferred from the original Phase 3:* **weighted yield+proximity** best-plot selection
    (claiming is nearest-first proximity only, no yield term yet) and **founding
    reservation** (`BuilderFirm`-cleared reserved footprint) — placement uses min-distance
    spacing + nearest-first, which suffices for the scenario.
- **Phase 4 — refinements. (Done.)** Two refinements to the shared field:
  - *Release on death (4a):* when a colony dies, `Settlement.releasePlotsToPool` returns
    all its claimed plots to the shared pool — vacating each first, since its firms (the
    plot occupants) outlive its last laborer — so its land is free for the other
    settlements and any later founding. Covered by `TwinSettlementEconomyTest` (after both
    die, the pool is all free). A per-firm `vacatePlot` still keeps the plot owned and
    re-seatable; only the colony's **death** frees its whole territory (a colony's
    territory persists as firms come and go).
  - *Yield-aware claim + center (4b/4c):* a settlement claims the **best-food plot among
    its nearest few** free plots (`ProvincePlotPool.bestYieldNearest`, food =
    `yieldFactor(NECESSITY)`), and its founding center likewise prefers good central land.
    Kept **proximity-dominant** (best yield among the `YIELD_CHOICE_NEIGHBOURS` = 4
    nearest) so the nearest-first growth, the travel ladder, and the food-balance
    calibration all hold — the full suite stays green with no retuning (`PlotYieldTest`'s
    mean food factor still ≈ 1.0). The literal `w·yield − (1−w)·travelCost` blend the model
    sketches is the heavier alternative; the bounded k-nearest form is the calibration-safe
    realization.
- **Phase 5 — validate + retune. (Done for the current cuts.)** The single-settlement
  default and the two-settlement province both run end to end with no retuning (the pool
  preserves the climate→terrain distribution); full suite green.

## Open questions deferred to later

- **Inter-settlement competition.** When two settlements' best-free regions overlap,
  whether claiming is purely first-come or weighted by need/wealth.
- **Yield beyond food.** Production/commerce plot yields are dormant in
  `docs/plots.md`; when they wake, the "best" weight gains those terms.
- **Province exhaustion.** What a settlement does when its province's free plots run
  out near it (stop growing, or found/seed a sibling settlement elsewhere in the
  province — the multi-settlement hook this note enables).
- **Distance correction.** Whether to de-project the Mercator y-stretch in the
  proximity metric (Caveat 2).
