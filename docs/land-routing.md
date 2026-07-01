# Design note: land routing (province route + plot corridors)

**Status:** design only — not yet implemented
**Date:** 2026-07-01
**Depends on:** the province graph (`WorldMap` / `docs/geography.md`), the per-province
**plot generation** already in place (`geo/ProvinceRaster` + `geo/ProvincePlotField` +
`settlement/ProvincePlotPool` — `docs/province-plots.md`), and the caravan **march model**
that consumes routes (`docs/caravan-march.md`).
**Motivating use:** a caravan travelling **Dhenijansar (4411) → Wexkeep (306)** — ~44
passable province hops, ~6,700 km great-circle, 23°N → 65°N — needs a route that knows
both the **real distance** and the **plots it crosses**, neither of which today's routing
provides.

## Goal

Code that returns a **land route between two provinces that accounts for the plots
traversed** — the plot count and per-plot traversal cost along the way, not just a hop
count — for the daylight-bounded march (`docs/caravan-march.md`, *plot-step within, hop
across*). It must **reuse the existing plot generator** (which already produces a
province's plots with terrain and features, and will carry **roads** later), and must
scale to the whole map without a monolithic plot graph.

## Current state and the gaps

- **`WorldMap.path(from, to)`** — unweighted BFS over passable province neighbours: hop
  count only, **no km distance**, no cost.
- **`Province`** carries `lat`/`lon` and a `plots` count, but the adjacency has **no km
  edge weights**.
- **`ProvinceRaster.mask(id)`** gives a per-province pixel mask (bbox + `land[]`/`river[]`/
  terrain grids), 1 px = 1 plot, lazily; **`ProvincePlotField`/`ProvincePlotPool`**
  generate a province's plots (terrain, feature, relief) from it on demand.
- **`Plot`** has pixel `x`/`y` and a ladder `index`, but **no plot-to-plot adjacency**.
- **Nothing** computes inter-province distance, border/"portal" cells, or any spatial graph
  beyond the province neighbour list.

So routing today is province-level, unweighted, and blind to both distance and plots — the
two things this note adds.

## Why not one big plot graph

The map has **~2.6 M land plots**. A single global plot graph (every land plot ↔ its raster
neighbours, cross-province edges at borders) is buildable but is the **wrong tool for a
6,700 km route**: plot-level A* would expand a large fraction of a continent, and the
structure is tens of MB. The efficient form is **hierarchical** — provinces are natural
clusters and their shared borders are natural **portals** (the standard HPA* decomposition
for a large grid partitioned into regions). Only the provinces actually on a route ever
touch plots.

Crucially, the march needs the **number and cost** of plots crossed per province and a
**camp plot** at day's end — *not* the exact plot-by-plot sequence globally (per-plot
foraging and per-plot terrain speed are deferred in `docs/caravan-march.md`). That is
exactly what the hierarchical scheme delivers cheaply.

## The model — two levels

### Level 1: the weighted province graph (coarse)

Give the province adjacency **real km edge weights** — the great-circle (haversine)
distance between adjacent provinces (between their `lat`/`lon`, or, better, between the
midpoint of their shared border and each centroid). Route with **Dijkstra / A*** (a
lat/lon straight-line heuristic), over ~5,264 nodes — fast. Returns the **province
sequence with per-hop km**. This alone gives the march its "hop across a boundary costs
`d` km" (`docs/caravan-march.md` §6).

### Level 2: the per-province plot corridor (fine)

For each province on the route, the band crosses a **corridor** of plots from where it
enters (the border to the previous province) to where it leaves (the border to the next):

- **Portals** — the plots on a province's border to a given neighbour. Derived once from
  `provinces.bmp`: a land pixel of province *P* whose 4-neighbour belongs to province *Q*
  is a *P→Q* border cell. Portals are **static geography** (seed-independent), so they are
  precomputed and cached (see *What is precomputed*).
- **Corridor** — an A* over *P*'s **generated plots** (reusing `ProvincePlotField` /
  `ProvincePlotPool`, so terrain/feature/relief — and later **roads** — come for free)
  from the entry portal to the exit portal. The **cost of stepping onto a plot** is a
  function of that plot: `terrain.buildModifier`, its feature, its relief
  (`PEAK` = impassable, so peaks are corridor obstacles the route bends around), and —
  **future** — whether it carries a **road** (a road plot is cheap/fast). The corridor
  yields the **plot count and total traversal cost/km** through *P*.

A province is ≤ 5,158 plots, so a corridor A* is trivially small; only route provinces pay
it, and results are cached per `(province, entryPortal, exitPortal)`.

### Roads are plot improvements built by settlements

**Decided:** a road is an **`Improvement` on a plot** — the same improvement system farms
use (`docs/plots.md`) — **laid by settlements** developing their surroundings. Routing
*uses* roads; it does not plan or lay them. Because the corridor cost is **read from the
generated plots**, a road plot simply carries a low traversal cost, so the corridor A*
**prefers road plots** and a route **hugs roads** where they exist (the in-model "why the
Romans built roads", feeding `docs/caravan-march.md`'s road speed factor). Laying a road
**invalidates** the serialized corridors through that plot, which recompute cheaper next
time — so roads spread by settlements gradually speed the routes through them, with **no
change** to Level 1 or the corridor machinery. The design is road-ready from the start.

## What is precomputed vs. computed on demand

Answering "does the entire map need precalculating?" — **the static geography, yes; the
plot corridors, no (lazily, cached):**

| Layer | When | Why |
| --- | --- | --- |
| **Province km edge weights** | **committed `map/` resource** (exporter), precompute once | pure geometry from `lat`/`lon` + borders; seed- and road-independent |
| **Border portals** (per province, per neighbour) | **committed `map/` resource** (exporter), one `provinces.bmp` pass | static geography |
| **Plot corridors** (entry→exit cost/length) | **compute from generated plots on first use, then serialize**; invalidate on road change | reuses the existing plot generator; **road-aware**, so the cache reflects the live plot state |

So there is **no monolithic 2.6 M-node graph**: the static layers (province km graph +
portals) are **precomputed by an exporter and committed to `map/`** (like
`ProvinceExporter`), loaded at runtime — small, seed-independent, fast startup. The plot
corridors are generated lazily from the *same* plot mechanism the settlements use, and are
**serialized** (cached to disk) rather than recomputed per run; because a corridor's cost
depends on the plots' improvements, a corridor is **invalidated and recomputed when a road
(or other improvement) is laid** on one of its plots (see *Roads*). So the only thing that
is never frozen is exactly the road-dependent layer.

## API sketch (illustrative, not final)

```java
// Level 1 — on WorldMap (or a LandRouter it owns)
double   distanceKm(int a, int b);            // great-circle between adjacent provinces
Route    route(int fromProv, int toProv);     // { List<Integer> provinces; double[] hopKm }

// Level 2 — plot corridors, reusing the province plot generator
PlotCorridor corridor(int province, int entryPortal, int exitPortal);
//   → { int plotCount; double costKm; List<Plot> path (optional, for the camp/debug) }

// portals, precomputed
List<Plot> portal(int province, int neighbour);   // border plots of province → neighbour
```

A caravan holds a `Route`; the march (`docs/caravan-march.md`) spends its daily km budget
along it — consuming corridor plots within a province (`KM_PER_PLOT × plotCost`), hopping
across a portal (province km) — and **camps on the plot it reaches at dusk** (materialized
on demand, the transient plot claim of `docs/caravan-march.md`).

## Determinism, cost, caveats

- **Deterministic.** Province geometry is fixed; plot generation is deterministic per seed
  (`ProvincePlotField` draws on a salted terrain RNG), so corridors are reproducible.
- **Cost.** Portal precalc is one `provinces.bmp` pass; each corridor A* is bounded by a
  province's ≤ 5,158 plots and cached. A full route touches ~tens of provinces.
- **Plot generation is currently colony-oriented.** `ProvincePlotPool` generates a
  province's plots shared across its *settlements*; routing needs the same generation for a
  province with **no** settlement. The pool already generates per-province, so the seam is
  to let a router request a province's plots (and its portals) without founding a colony —
  a small extension, not new generation.
- **Rivers.** The mask carries a river flag; **crossing a river costs a full day**
  (decided) — a ford/crossing delay that halts the day's march, not a small per-plot cost,
  so river-heavy corridors are materially slower (mirrored in `docs/caravan-march.md` §6).
- **Portal choice.** A neighbour border can be many cells; the corridor should enter/exit
  at the portal that minimises total cost (nearest to the through-line), not an arbitrary
  one.

## Decided (resolved 2026-07-01)

- **`KM_PER_PLOT` is derived from the map's pixel↔degree scale** (from the province raster
  resolution), not a free constant. Shared with `docs/caravan-march.md`.
- **The static layers ship as a committed `map/` resource** — an exporter (like
  `ProvinceExporter`) computes the province km edge weights + border portals once at build
  time; the runtime just loads them.
- **Plot corridors are serialized** (cached to disk), not recomputed per run, and are
  **invalidated when a plot's road/improvement changes** (a new road alters corridor cost).
- **Crossing a river costs a full day** — a ford delay, not a small per-plot cost.
- **Roads are plot improvements built by settlements** (an `Improvement`, like a farm —
  `docs/plots.md`); routing *uses* roads (corridors hug road plots), it does not plan or
  lay them.

## Open questions

- Whether **coastal / sea legs (ferries)** are ever allowed — today caravans are land-only
  (`WorldMap.path` is passable land), so a route with no land path (e.g. across an ocean)
  simply does not exist.
- **Portal choice** when a neighbour border spans many cells — enter/exit at the
  minimum-cost portal (nearest the through-line), the concrete selection rule.
- The exact **`KM_PER_PLOT` derivation** from the raster (degrees-per-pixel → km at the
  province's latitude), and whether it varies with latitude or is a single map constant.
