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
**Related:** `docs/geography.md` — this **decides its open question** "Map scale
vs. colony scale": **a province holds multiple settlements**, which share one
generated plot field. `docs/village-founding.md` / `docs/caravan-trade.md` found
settlements into provinces; this note is what their second-and-later settlements
in a province draw their land from. The generation algorithm is what an earlier
"render a province as a Civ4 map" sketch described (province silhouette at 1
pixel = 1 plot, climate→terrain fill); that file-export framing is **dropped** —
the silhouette and the terrain fill are kept, but they produce **in-sim `Plot`s**,
not a `.CivBeyondSwordWBSave`.

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
   - **relief** — `ReliefGenerator` ports the C2C `addPeaks`/`addHills`: seed by
     probability, then **grow into clusters/ranges** (so a province reads as a
     landscape, not scattered noise), an `IMPASSABLE` province mountainous. *(done)*
   - **terrain** — the ground; the C2C temperature→terrain selection (interim: the
     existing climate-weighted pool, `TerrainGenerator.next`). *(relief done; C2C
     temperature refinement next)*
   - **features** — water-seeded forest/jungle growth and river flood plains (the
     per-plot river flag is already captured for this). *(staged)*
   - **resources** — the C2C `addBonuses` placement onto plots. *(staged)*
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
  from the province and transfers ownership; `vacatePlot` returns it to free.
- **Center placement.** A founding settlement's center is the free plot that
  maximizes `(yield − distancePenalty)` while keeping a minimum spacing from
  existing settlement centers in the province — so settlements spread out across
  the province rather than stacking.
- **Best-plot claim.** From its center, a settlement scores free plots by
  `w·yield − (1−w)·travelCost` and claims the best, assigning successive Fibonacci
  rungs in claim order (the Nth-closest reserved plot sits on rung N), preserving
  today's `TravelLadder` index→time semantics.
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
    (`ProvincePlotField`: relief + climate-pool terrain). Covered by
    `ProvincePlotFieldTest` (silhouette/count, determinism, relief clusters).
  - *Slice 2 (next):* the C2C **temperature→terrain** selection (replacing the interim
    climate pool) + water-seeded **feature growth** and river **flood plains**.
  - *Slice 3:* the C2C **resource** placement (`addBonuses`).
- **Phase 2 — ownership + single-settlement claim.** Plot gains `(x,y)` + owner;
  `Settlement.claimPlot`/`vacatePlot` re-pointed to claim from the province field
  (ownership transfer); the per-settlement Fibonacci ladder from a center. Fold the
  existing single-settlement-per-province path onto the shared field with no
  behavioural change (the colony still founds and grows as today, now over province
  plots).
- **Phase 3 — best-plot selection + founding reservation.** Weighted yield+proximity
  scoring; founding reserves its footprint; `BuilderFirm` clears reserved plots.
- **Phase 4 — multiple settlements.** Center auto-placement with spacing; a second
  settlement founded into an occupied province claims the best free region; verify
  two settlements coexist on one province's field without contention.
- **Phase 5 — validate + retune.** Confirm the macro behaviour (food balance, the
  collapse profile) is unchanged for the single-settlement default, and exercise a
  two-settlement province end to end.

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
