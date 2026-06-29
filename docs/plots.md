# Design note: Civ4-style plots for settlement slots

**Status:** Phases 0–3 implemented; Phase 4 proposed
**Date:** 2026-06-29
**Depends on:** the occupant seam (`PlotOccupant`, which `Agent` implements) and the build
queue (`BuildProject`, `BuilderFirm`) — this note **removes** the disc geometry it replaces
(`SlotTable`/`SlotInfo`/`Slot`/the `size` field, now removed);
the geography axis (`com.civstudio.geo` — `Province` incl. `plots`,
`Climate`/`WinterSeverity`/`Monsoon`, `Settlement.getAgricultureClimateMultiplier()`); and
the sector/TFP plumbing (`tech.Sector`, `ConsumerGoodFirm.effectiveA()`).

## Motivation

Today a settlement is a **disc of identical, featureless build slots**: `SlotTable` gives
an `effective` count, and each `Slot` (`settlement/Slot.java`) is just an occupant holder
— vacant or holding one `PlotOccupant`. Land quality enters the economy as a single flat
**per-colony** scalar, `Settlement.getAgricultureClimateMultiplier()` (climate × winter ×
monsoon), which only `NFirm.effectiveA()` folds into food TFP. Every firm on every slot is
otherwise on interchangeable ground, and enjoyment/capital/export output is entirely
land-independent.

The goal is to model **Civ4 plots without a tiled map**: each build slot becomes a
**`Plot`** carrying a base **terrain** and an optional **feature**, each contributing a
**Food / Production / Commerce** yield triple (sourced from `data/CIV4TerrainInfos.xml`
and `data/CIV4FeatureInfos.xml`). All three yields are plumbed into the matching firm
sectors so land quality varies *per plot* — though only Food is live in this cut (see
*Decisions taken*), the model is poised to drive all production, not just food.

There is **no 2D coordinate grid**. Instead of a disc, plots are arranged by **travel
time from the city center** (see *Spatial arrangement* below): the center is plot 0
(0 minutes — where the daily labor market is held and where laborers live for now), and
each successive plot lies one rung farther out on a **Fibonacci** ladder of round-trip
travel times. Distance is no longer cosmetic geometry — the commute to a distant plot
**eats into the working day**, so a worker delivers less effective labor the farther its
firm sits from the center. The terrain/feature mix is generated **procedurally from the
founding province's climate** (reusing existing `Province` attributes), deterministically
per seed.

This also unblocks two designed-but-stalled features: the **forage firm** (works *wild*,
uncleared, feature-bearing plots — see `CLAUDE.md` *Forage firm* and `docs/granary.md`
§6.1) and gives the builder's land-clearing a real, terrain-dependent cost.

### Decisions taken

- **All three yields plumbed** — Food → necessity, Production → capital/construction,
  Commerce → export/enjoyment. The data model and the `effectiveA` wiring support all three,
  but **only Food is live this cut** (only the `NFirm` farm sits on a plot); Production and
  Commerce activate later, when a mine / cottage / trading-post firm is placed on a plot.
- **Per-plot terrain** — each plot has its own terrain + feature; a firm reads *its* plot's
  yield. (Not an aggregate per-colony terrain histogram.)
- **Procedural from province climate** — the plot mix is generated deterministically from
  the founding `Province`'s `Climate`/`WinterSeverity`/`Monsoon`/`type`.
- **Plots arranged by travel time, not on a disc** — plot 0 is the center (the labor
  market and, for now, where every laborer lives); successive plots sit at Fibonacci
  round-trip travel times, and that travel time scales down the labor a worker delivers
  to a firm on that plot.
- **A firm on a plot operates an improvement** (Civ4's third leg — farm/mine/cottage…); the
  improvement is the firm's building and adds its yield on top of terrain + feature. A
  **necessity firm is subsistence agriculture = a `FARM`, one firm per plot**; firms with no
  improvement are center-grouped.

## Civ4 data — what we keep

From the two XMLs, only the economically-relevant fields are kept (footsteps, sounds, art,
movement, defense, culture-distance, pollution manipulators are dropped):

### Terrain (`TerrainInfo`)

| Field | Use |
|---|---|
| `Yields` = `[food, production, commerce]` | base yield triple (missing entries → 0) |
| `bFound` | whether land is settleable |
| `iBuildModifier` | % build-cost surcharge on this terrain |
| `iHealthPercent` | stored, **dormant** (future disease/health axis) |

Curated **land** subset (water/polar/`ICE`/`PEAK` deferred — coastal fishing is future
work):

```
GRASSLAND 2/0/0   LUSH 3/0/0    PLAINS 1/1/0    SCRUB 1/0/0
MARSH 1/1/0       MUDDY 1/2/0   ROCKY 0/2/0     BADLAND 0/1/0
JAGGED 0/1/0      BARREN 0/0/0  DESERT 0/0/0    DUNES 0/0/0
SALT_FLATS 0/0/2  TAIGA 1/0/0   TUNDRA 0/0/0    PERMAFROST 0/0/0
```

**Hills and peaks are a per-plot *type*, not a terrain** (decided — matching Civ4, where
plot type is orthogonal to terrain). A `Plot` carries a `PlotType` (`FLAT` / `HILL` /
`PEAK`) alongside its terrain, so any terrain can be flat or hilly (a grassland hill, a
desert hill): a `HILL` adds a production bonus and makes `MINE` valid (`bHillsMakesValid`);
a `PEAK` is **unworkable** (no occupant, no usable yield). A peak still **occupies a rung on
the travel ladder** (and counts toward `province.plots`), so peaks among the near plots push
workable land farther out — a real terrain penalty for rough country, not skipped.
`TERRAIN_HILL`/`TERRAIN_PEAK` from the XML are read as this plot-type axis rather than as
terrain entries.

### Feature (`FeatureInfo`)

| Field | Use |
|---|---|
| `YieldChanges` = `[df, dp, dc]` | additive on the host terrain's triple |
| `iAdvancedStartRemoveCost` | clear cost (the work to remove the feature) |
| `bRequiresFlatlands` / `bRequiresRiver` | generation constraints |
| `TerrainBooleans` | valid host terrains — drives generation |
| `iHealthPercent` / `iGrowth` | stored, **dormant** (health / feature-spread, future) |

Curated **land** subset (impassable / named-wonder / rock-formation / ruins / pollution /
water-reef features dropped):

```
FOREST 0/+1/0         FOREST_ANCIENT +1/+2/0   JUNGLE -1/0/0 (clear 40)
BAMBOO 0/+2/0         SAVANNA 0/+1/0           VERY_TALL_GRASS +1/0/0
CACTUS +1/0/0         OASIS +3/0/+2 (flat)     FLOOD_PLAINS +2/0/0 (river)
SWAMP -1/0/0
```

### Improvement (`ImprovementInfo`, `data/CIV4ImprovementInfos.xml`)

The third leg: what a firm **builds and works on a plot** (a farm, mine, cottage…). An
improvement adds its own yield change on top of terrain + feature. (It carries a
`PrereqTech`, but **tech-gating is deferred** — see *The firm's building*.) Kept fields:

| Field | Use |
|---|---|
| `YieldChanges` = `[df, dp, dc]` | yield the built improvement adds |
| `PrereqTech` | the tech that unlocks building it (ties to the tech tree) |
| `bHillsMakesValid` / `bFreshWaterMakesValid` / valid terrain & feature lists | where it may be built |
| `IrrigatedYieldChange` / `RiverSideYieldChange` / tech-gated yield upgrades | stored, **mostly dormant** (irrigation/river deferred; tech upgrades tie to the `SectorProductivity` effect later) |
| `iAdvancedStartCost` | build cost (work to raise it) |
| `iHealthPercent` | stored, dormant (e.g. `MINE` −50) |

Curated subset (each is the *building* of one on-plot firm type — see *The firm's
building*):

```
FARM   +2 food   (AGRICULTURE)      → necessity / subsistence agriculture
MINE   +4 prod   (MINING, hills)    → extractive capital
QUARRY +5 prod +1 com (MASONRY)     → extractive capital (stone)
LUMBERMILL +6 prod +4 com (MACHINERY, on forest) → managed-forest production
PASTURE (animal bonus, SEDENTARY)   → animal husbandry (food/prod)
WINERY +1 food +1 com (FERMENTATION)→ enjoyment / cash crop
PLANTATION +4 com (on bonus/feature)→ commerce cash crop
COTTAGE→HAMLET→VILLAGE→TOWN (growing com) → trade / housing
CAMP / HUNTING_CAMP +food (works wild, no clearing) → the forage firm
```

### Bonus (`BonusInfo`, `data/CIV4BonusInfos.xml`)

A Civ4 **bonus** is a discrete resource placed on a plot (wheat, iron, gold, horse…), adding
its own F/P/C yield change on top of terrain + feature + improvement and belonging to a
**bonus class** (crop / livestock / strategic / luxury / production / seafood / misc — from
`data/CIV4BonusClassInfos.xml`). Bonuses have **no plot-model role yet** (they are not placed
on plots or read for yield in this cut); the Phase-0 data layer just parses and indexes them.
Unlike the curated terrain/feature/improvement subsets, the **full** set (all 106 bonuses) is
exported. Kept fields:

| Field | Use |
|---|---|
| `BonusClassType` | its `BonusClass` (carries the dormant `iUniqueRange` placement spacing) |
| `YieldChanges` = `[df, dp, dc]` | additive yield the resource adds |
| `TechReveal` / `TechCityTrade` | tech gating, stored **dormant** |
| `iHealth` / `iHappiness` | amenity, stored **dormant** |
| `iMinLatitude` / `iMaxLatitude` | generation latitude band |
| `bHills` / `bFlatlands` / `bPeaks` | generation plot-type constraints |
| `TerrainBooleans` / `FeatureBooleans` / `FeatureTerrainBooleans` | valid host terrains / features / feature-bearing terrains (drives generation) |

The map-generator/AI internals (`iPlacementOrder`, `Rands`, `iTilesPer`, `iConstAppearance`,
`iAITradeModifier`, art/sounds) are dropped. The 11 **bonus classes** are a fixed taxonomy
modeled as the `BonusClass` **enum** (uniqueRange baked in from `CIV4BonusClassInfos.xml`),
not a separate resource — the way `Continent` is an enum rather than a `continents.json`.

This data is **inert in this cut** — bonuses are not placed on plots or read for yield.
Wiring them in is **deferred past Phase 3** to the Production/Commerce activation, where the
extractive/commerce improvements make a resource economically load-bearing (see *Phasing*).

### Provenance

The `terrains.json` / `features.json` / `improvements.json` / `bonuses.json` resources are
**produced by exporters** — `TerrainExporter` / `FeatureExporter` / `ImprovementExporter` /
`BonusExporter` in `com.civstudio.geo.export` (mirroring `ProvinceExporter` et al.) — that
parse the committed `data/CIV4*.xml` (all conforming to the shared
**`data/C2C_CIV4TerrainSchema.xml`**, the Civ4 schema for terrains, features, improvements and
bonuses alike) and emit the **curated subset** (the **full** set for bonuses). The curation
(which types to keep) and the XML→record field mapping live in the exporters; they run
**manually**, like the geo exporters, so the resources are **regenerable** and provenance is
preserved.

## Yield → sector mapping

Civ4 yield indices map onto the existing `tech.Sector` enum plus the builder:

| Yield (index) | eos sectors | Firms |
|---|---|---|
| Food (0) | `Sector.NECESSITY` | `NFirm`, forage firm (future) |
| Production (1) | `CAPITAL`, construction | `CFirm`, `BuilderFirm` |
| Commerce (2) | `EXPORT`, `ENJOYMENT` | `StrategicFirm`, `ScienceFirm`, `EFirm` |

A plot exposes `yieldFactor(Sector)` — a **TFP multiplier**, not a raw yield. To bound the
disturbance to today's calibration, define a per-index **reference yield** so the model's
baseline terrain lands at factor ≈ 1.0:

```
yieldFactor(sector) = max(FLOOR, (terrain.yield[i] + hill.yield[i] + feature.yield[i] + improvement.yield[i]) / REFERENCE[i])
```

`improvement.yield[i]` is the contribution of the improvement the on-plot firm operates
(its building — see *The firm's building* below); a plot with no improvement (none built
yet, or a center-grouped firm) contributes only terrain + feature. `FLOOR` is a small ε (a
0-food desert farms poorly but not at literally zero). `REFERENCE` is chosen so the model's
**baseline plot + its standard improvement** lands at factor ≈ 1.0 (e.g. grassland food 2 +
farm 2 = 4 → reference 4), and then **tuned** so the default Dhenijansar colony's aggregate
food TFP ≈ its current climate-multiplier value (see *Calibration*). A province-less colony
nets to 1.0 by this construction, staying byte-identical.

### The firm's building — improvements

An **on-plot firm operates exactly one improvement** on its plot — the improvement *is* the
"physical building on a plot" that distinguishes it from a center-grouped firm. So the firm
type fixes the improvement, and the improvement's yield is that firm's land-productivity
bonus. The canonical case: a **necessity firm is subsistence agriculture = a `FARM`**, and
**one necessity firm uses one plot** (1 `NFirm` : 1 plot, no sharing). Likewise the **forage
firm operates a `CAMP`/`HUNTING_CAMP` on a *wild* (feature-bearing) plot** — gathering off
the wild land with **no clearing**, the inverse of the farm that needs cleared land.
Extractive capital (`MINE`/`QUARRY` on `ROCKY`/`HILL`) and commerce (`COTTAGE`→`TOWN`,
`PLANTATION`) are the candidates that make the Production/Commerce coupling live later.

Improvements carry a `PrereqTech`, but **tech-gating is deferred in this cut**: every
curated improvement is buildable regardless of researched tech. The `PrereqTech` (and the
tech-gated yield upgrades) are stored for a future tie-in to the existing tech tree and the
`SectorProductivity` effect.

The yield factor applies **only to firms that sit on a plot** (see *On-plot firms vs. the
city center*). A center-grouped firm is land-independent (factor 1.0), so each row of this
table becomes live for a sector only once that sector's firm type is given a
building-on-a-plot. **This cut, only Food is live** (only the `NFirm` farm is placed on a
plot); Production (a mine/quarry) and Commerce (a trading post) are fully plumbed but
dormant until such a firm exists.

## Spatial arrangement: the travel-time ladder

Plots are **ordered by how long it takes a laborer to walk out to them and back**, not by
position on a disc. The **city center is plot 0** — travel time 0 — and is where the daily
labor market clears and (for now, until housing exists) where every laborer lives. **Plot 0
is the civic center, not a worked plot**: on-plot firms (farms) start at **plot 1**
(`T = 1 s`, negligible). Each later plot is one rung farther out, its **one-way travel
time** following the Fibonacci sequence:

```
plot index i:      0  1  2  3  4  5  6   7   8   9   10  11   12   13   14   15   16    17    18    19    20    21     22 ...
travel T(i) (sec): 0  1  1  2  3  5  8  13  21  34   55  89  144  233  377  610  987  1597  2584  4181  6765 10946  17711 ...
```

Travel times are in **seconds**, so the inner plots cost almost nothing and the commute
only bites on the far rungs. So the plots claimed earliest are the closest; growth appends
ever-more-distant plots.

### The economic coupling — commuting eats the working day

Of the day's **work window** `D` (sunrise → sunset, in seconds — defined, with the whole day
schedule, in **[`daily-rhythm.md`](daily-rhythm.md)**), two things are deducted before a
worker on plot `i` produces anything: the **labor market's clearing time** `N` (one second
per participating worker — uniform, the same for everyone) and that worker's **round-trip
commute** `2·T(i)` to its firm's plot (per-plot). So the seconds it actually works are
`max(0, D − N − 2·T(i))`, and the labor it delivers is scaled by

```
workFactor(i) = max(0, 1 − (N + 2·T(i)) / D)
```

— **on top of** the existing skill-productivity and daylight scaling (`LaborMarket`,
`Household.productivityOf`, the `daylight/8` factor). It composes with the seasonal/latitude
daylight model already in place: short winter days make `D` small, so the same `N` and
commute eat a larger fraction and the usable frontier shrinks exactly when output is already
tight (this sharpens — and must be re-validated against — the deep-winter labor-scarcity
concern documented in `CLAUDE.md`).

This is a **Von Thünen** rent gradient (with a population-scaling market overhead `N` on
top): central plots are economically premium (little labor lost to travel), distant plots
progressively worse, and beyond `N + 2·T(i) ≥ D` a plot yields **no useful labor at all** —
a natural, self-imposed frontier. In **seconds** that
frontier sits at a sensible village scale: with an 8 h day (`D = 28 800 s`), the commute is
negligible through the mid teens (plot 17, `T = 1597`, costs ~11% of the day), bites from
the high teens (plot 20, `T = 6765`, ~47%), and closes off around plot 22 (`T = 17 711`,
round trip > a full day → unworkable) — so roughly **the first ~21 plots are usefully
workable**, comfortably more than the ~20 firms a default colony runs. The gradient still
caps useful village size organically and gives the ruler's dynamic firm provisioning a
built-in reason to stop chartering (a new firm on a far plot would barely produce); the day
length `D` and the provisioning stop-rule remain the calibration knobs, but the
grows-too-fast tension the minute scale created is gone.

### On-plot firms vs. the city center

Not every firm sits on a plot. **A firm sits on a plot iff it operates an improvement**
(its building — see *The firm's building*); that firm pays the travel cost and reads its
plot's terrain + feature + improvement yield. Every **other** firm is assumed to be
**grouped in the city center** (plot 0): it operates no improvement, consumes **no plot**,
incurs **no commute** (its `2·T(0) = 0` travel term — though its workers still lose the
uniform market time `N`, like everyone), and is land-independent.

This cleanly separates the *land-working* firms from the *in-town* ones and keeps plots a
scarce resource spent only where an improvement actually stands:

- **On a plot** (operates an improvement, consumes a plot, travel-costed, yield applies):
  the **necessity farm** (`NFirm` = a `FARM` on cleared land — **1 firm : 1 plot**) and the
  **forage firm** (a `CAMP`/`HUNTING_CAMP` on a *wild* plot) — the land-working firms.
  Extractive/production improvements (`MINE`/`QUARRY` on a `ROCKY`/`HILL` plot reading
  **production**, a `COTTAGE`/`PLANTATION` reading **commerce**) are the candidates to join
  them, and are how the Production/Commerce yield coupling becomes live for those sectors.
- **Center-grouped** (plot 0, no plot consumed, no travel): by default the **enjoyment**,
  **capital**, **export/strategic**, **science** and **builder** firms — abstract workshops
  and crafts with no modelled land footprint yet.

Which firm *types* carry a building-on-a-plot is therefore a per-type property (a default
above, tunable). It has two consequences worth noting: a center-grouped firm gets **no
terrain yield factor** (factor 1.0 — the all-three-yields coupling is realized only for the
firm types actually placed on plots), and because most firm types group at the center, the
**plot count a colony needs is much smaller** than its firm count — only the farms (and
forage) draw on the travel-time ladder — which further eases the capacity question.

### The forage firm (future work)

The forage firm is a designed-but-unbuilt **second food source**, the inverse of the farm,
and the chief consumer of the wild/cleared plot state Phase 3 lays down. Its plan:

- **A `CAMP`/`HUNTING_CAMP` on a *wild* plot.** Where the necessity farm needs the builder to
  *clear* the feature and raise a `FARM` on cleared `LAND`, the forage firm works an
  **uncleared, feature-bearing** plot directly — gathering/hunting the hinterland and bringing
  back `Necessity` **every day with no clearing**. It is gated on `Plot.isWild()` (has a
  feature and is not cleared — see *Architecture*); clearing a plot for a farm destroys the
  feature and the forage option, so a wild plot is **either** farmed (cleared, capital,
  higher yield) **or** foraged (left wild, no capital, lower yield) — a land-use choice.
- **Architecturally a `BuilderFirm`-like labor-only firm, *not* an `NFirm` subclass**
  (decided). The firm hierarchy splits on *production structure*: the capital-bearing
  `ConsumerGoodFirm` family (`EFirm`/`NFirm`, `A·L^β·K^(1-β)`) vs. the labor-only firms
  (`BuilderFirm`/`StrategicFirm`/`ScienceFirm`, `A·L^β`). A forage firm — labor-only, no
  capital, **peasant-staffed on the `PeasantLabor` market** (corvée, like the builder) — sits
  with the latter. Subclassing `NFirm` would saddle it with the `K` term and the
  return-on-capital purchase machinery it must then disable (a refused-bequest smell). What it
  shares with `NFirm` is **not the class** but two seams it joins from outside: the **good +
  market** (it produces `Necessity` and sells into the same necessity `ConsumerGoodMarket`, so
  the sector's supply aggregates farms + camps with no extra wiring) and the **on-plot food
  yield** (`Settlement.plotYieldFactor(occupant, NECESSITY)`, keyed by *sector* not by `NFirm`,
  with `occupiesPlot()` true — so a `CAMP` reads its plot's feature-modified food yield). It
  trains a gathering/hunting skill (`PLANTS`, or `SHOOTING`/`ANIMALS` for hunting) rather than
  the builder's `CONSTRUCTION`. If a later refactor finds genuinely-shared farm/forage code,
  the right move is a small shared base/interface both implement — not inheritance from
  `NFirm`.
- **Why it matters — the survival lever.** It puts the colony's otherwise-**idle reserve
  adults to productive food work** instead of letting them drain away on relief: the founding
  reserve's promotable adults deplete in ~year 1 and the surplus peasants die off unused
  (`docs/granary.md` §6.1), so foraging feeds the colony off wild land during the long "adult
  gap" while the land is gradually cleared for higher-yield `NFirm` agriculture. A low-yield,
  no-capital, no-clearing food tap that complements the cleared-land necessity sector.
- **One open seam.** "Wild" is currently defined as *feature present*, so forage is
  feature-gated; foraging bare, featureless wilderness would be a small extension to
  `isWild()`.

### The plot list replaces the disc (no `SlotTable`, no `size`)

**Decided: the disc model is removed.** There is no `SlotTable`, no `SlotInfo`, and no
settlement `size` field. A colony simply holds a **growing `List<Plot>`** ordered by ladder
position (index 0 = the center), and its capacity ceiling is **`province.plots`** directly
(a province-less colony uses a fixed cap). Growth **appends the next plot** (one ladder rung
farther out); `hasRoomToExpand()` becomes `plots.size() < province.plots`. The disc's
`road`/`wall` congestion columns, `wallBuildTimePercent`, and the size→capacity table all
go — travel time is now the entire diminishing-return-on-growth mechanism (the commute
gradient, plus the population-scaling market overhead `N`), and it does the job
economically rather than as a slot count.

What remains of the old `SlotTable` is **re-homed as plain bookkeeping**, not size-tiered:

- **Growth / build cost.** Opening a plot is one `BuildProject` — **clearing any feature then
  raising the improvement** — funded by the firm that wants it. The work total is the
  improvement's **`iAdvancedStartCost`** plus, when a feature must be cleared first, the
  feature's **`iAdvancedStartRemoveCost`**, scaled by the terrain `buildModifier` (rough/
  forested ground takes longer). So the builder *does* raise the improvement (e.g. the
  `FARM`), billed to the requesting firm. The old per-ring `ROAD`/`WALL` ruler-funded public
  works are **dropped** (disc artifacts).
- **Plot selection (net-food tradeoff).** When a new on-plot firm needs land it takes the
  **workable** plot (skipping `PEAK`s) that **maximizes its effective output** =
  `yieldFactor(sector) × travelFactor(index)` — balancing terrain quality against commute,
  so a farm prefers close, fertile land and only reaches for a distant fertile plot (or a
  poor near one) when that wins. This is the new `claimPlot`; if the chosen plot isn't yet
  prepared it queues that plot's prep with the builder (the **chosen plot directly** — no
  intervening rungs, since the ladder is a travel-time ordering, not a path). A plot **freed
  by a dissolved firm keeps its cleared state and improvement** (a durable land investment),
  so re-seating it is cheap — the net-food choice then favours re-using developed close plots
  over preparing fresh land.

**Special sites are dropped this cut** (decided): the `specialSites` list / `claimSpecialSite`
/ `getSpecialSites` machinery and the size-tier unlock schedule go away with the disc. The
village hall — their only planned occupant — finds its home when village-founding lands
(`docs/village-founding.md`), e.g. on plot 0 itself, rather than in a special-site slot.

### The builder under the plot model

The `BuilderFirm` keeps its machinery — a labor-only, **center-grouped** firm staffed by
`Retinue` peasants on the `PeasantLabor` market, converting labor → build-units (`A·L^β`,
`scaffoldCap`), draining its queue, billing each project's sponsor at cost, reimbursing the
peasant wages to the ruler, training `CONSTRUCTION`, idle when the queue is empty — but the
queue and the costing change:

- **The queue holds plot-prep projects, not rings.** One `BuildProject` per plot to open
  (clear feature, then raise the improvement), funded by the requesting firm. `ROAD`/`WALL`
  are gone, so the builder no longer bills the ruler for public works; growth is now **fully
  firm-funded**.
- **Work total = improvement `iAdvancedStartCost`** (+ feature `iAdvancedStartRemoveCost` when
  clearing) × terrain `buildModifier`. The builder **does** raise the improvement (the
  `FARM`); a plot freed by a dissolved firm keeps that improvement, so re-seating skips the
  `iAdvancedStartCost`.
- **The builder's peasants feel the daily coupling too** (decided): the `PeasantLabor`
  market gets the same `workFactor` — the market overhead `N` plus the **commute to the
  build site** (`travelFactor` of the target plot) — so a peasant delivers fewer build-units
  per day on a distant plot. **Distance therefore enters build *speed*, not the work total**:
  a far plot takes the same total work (`iAdvancedStartCost`) but is delivered more slowly
  (and is also costlier to *operate* once farmed). As with all coupling, **province-less
  colonies bypass it** — their builder peasants take `workFactor == 1`.
- `BuilderConfig` drops its `ROAD`/`WALL` per-slot costs; the land/clear cost is recast onto
  the Civ4 feature-removal basis.

## Architecture

With the disc gone, **`Plot` is the unit** — it absorbs the old `Slot`'s occupant role.
A `Plot` is one rung on the ladder: its **index** (→ travel time `T(index)`), its land
(terrain + feature + improvement + cleared state), and its single **`PlotOccupant`** (the
interface is kept — `Agent` implements it — so the rename is `Slot`→`Plot`, not a new
abstraction). `SlotTable`/`SlotInfo` are deleted; the colony holds `List<Plot>` and the
claim/vacate methods become `claimPlot`/`vacatePlot`. Special sites stay a small separate
list (above).

### New files

- `src/main/resources/terrains.json`, `features.json`, `improvements.json`, `bonuses.json` —
  the curated data above (full set for `bonuses.json`), **emitted by the exporters below**
  (committed resources, like `provinces.json`).
- `geo/export/TerrainExporter.java`, `FeatureExporter.java`, `ImprovementExporter.java`,
  `BonusExporter.java` — parse `data/CIV4*.xml` → the curated JSON (subset + field mapping),
  mirroring `geo/export/ProvinceExporter`; run manually to regenerate.
- `geo/Bonus.java` — record `(String type, BonusClass bonusClass, int[] yieldChanges, String techReveal, String techCityTrade, int health, int happiness, int minLatitude, int maxLatitude, boolean hills, boolean flatlands, boolean peaks, List<String> validTerrains, List<String> validFeatures, List<String> validFeatureTerrains)`.
- `geo/BonusClass.java` — enum of the 11 Civ4 bonus classes, each carrying its dormant `iUniqueRange` (from `data/CIV4BonusClassInfos.xml`).
- `geo/Terrain.java` — record `(String type, int[] yields, boolean bFound, int buildModifier, int healthPercent)`.
- `geo/Feature.java` — record `(String type, int[] yieldChanges, int clearCost, boolean requiresFlatlands, boolean requiresRiver, List<String> validTerrains, int healthPercent, int growth)`.
- `geo/Improvement.java` — record `(String type, int[] yieldChanges, String prereqTech, boolean hillsMakesValid, boolean freshWaterMakesValid, List<String> validTerrains, List<String> validFeatures, int buildCost, int healthPercent)`.
- `geo/TerrainRegistry.java` — loads the four JSON via Jackson, **shared per `GameSession`**
  (like `NameRegistry`/`Demography`); type → definition lookups for terrain, feature,
  improvement and bonus.
- `settlement/Plot.java` — **the occupiable unit** (replaces `Slot`): a ladder **index**
  (→ travel time `T(index)`), the land (`Terrain` + a `PlotType` `FLAT`/`HILL`/`PEAK` +
  nullable `Feature` + nullable `Improvement` + `cleared` flag), and one `PlotOccupant` with
  `occupy`/`vacate`/`isVacant`. Plus `yields()` (terrain + hill bonus + feature + improvement),
  `yieldFactor(Sector)`, `isWild()` (has a feature and not cleared — the forage target),
  `isWorkable()` (false for `PEAK`), `clearCost()`.
- `geo/TerrainGenerator.java` — `Climate`/`WinterSeverity`/`Monsoon`/`type` → a weighted
  terrain distribution + plot-type (hill/peak) + feature probabilities; **generates the
  colony's whole plot-map** (all `province.plots` plots) at founding off a **dedicated
  terrain RNG**.
- A **travel-time helper** — `T(index)` (the Fibonacci ladder) and
  `workFactor(index, N, D) = max(0, 1 − (N + 2·T(index)) / D)`. Lives on `Settlement` (or a
  small `TravelLadder` value); pure arithmetic, no state.

### Modified files

- **Delete** `settlement/Slot.java`, `settlement/SlotTable.java`, `settlement/SlotInfo.java`,
  the `/slots.json` resource, and the settlement `size` field — their role is taken by the
  `List<Plot>` capped at `province.plots` (the occupant logic moves onto `Plot`; the
  `PlotOccupant` interface is kept). `SlotTableTest` is removed/retargeted.
- `market/LaborMarket.java` — in `clear()`, compute `N` = the number of participating
  workers in seconds — **each posted person counts one second** (a household's head and each
  working spouse are separate workers) — and `D` =
  `Duration.between(getSunrise(), getSunset()).toSeconds()` (the exact sunrise→sunset span;
  fall back to `getDaylightHours() × 3600` when those `LocalTime`s are `null` at the poles).
  In the per-worker productivity computation (the same place the `daylight/8` factor and
  `relevantLevel` are applied), scale the labor a worker delivers to its employer by
  `workFactor = max(0, 1 − (N + 2·T(firmPlotIndex)) / D)` — the uniform market overhead `N`
  plus that firm's per-plot commute. This is a **labor-input** effect (it reduces the
  effective `L`), distinct from the terrain yield that scales the firm's TFP. The firm's
  plot index comes via the colony's `occupant → Plot` map; a **center-grouped firm** (no
  plot — capital/export/science/builder, and enjoyment, by default) and a
  not-yet-seated/pending firm have no commute term, so their workers lose only the uniform
  `N` (`workFactor = 1 − N/D`). The noble/export labor path (`addEmployee(int, …)`) takes the
  same `N` while the strategic firm stays center-grouped. **For a province-less colony the
  whole computation is skipped — `workFactor == 1`** (no `N`, no travel), so its labor stays
  byte-identical.
- `settlement/Settlement.java`:
  - At **founding, generate the whole plot-map** — all `province.plots` plots (terrain +
    plot-type + feature) — from a `TerrainGenerator` off a **separate terrain `Rng`** (salted
    from the seed, like the naming/mortality/skill streams) so generation **does not perturb
    the economic stream**. The plots exist as data from day 0 (so net-food selection can see
    every candidate); growth / `claimPlot` only mark which plots are *prepared and occupied*
    (building out to the chosen plot), capped at `province.plots`.
  - **Province-less colonies bypass the whole plot coupling** (decided). The analytical sims
    — `SmallOpenEconomy`, the sweeps, `HanseaticEconomy` (bare coordinates, `province == null`)
    — take **no terrain yield factor, no travel cost, and no market-`N` deduction**
    (`plotYieldFactor == 1.0`, `workFactor == 1.0`), exactly as they get no climate multiplier
    today. So the yield/travel/market coupling leaves them untouched. (This is the *economic*
    coupling only — the *structural* disc-removal below still affects any builder-bearing
    colony, province or not; see *Phasing*.)
  - Maintain an `occupant → Plot` map and expose two separate accessors, because terrain and
    travel hit production through different channels: `plotYieldFactor(PlotOccupant, Sector)`
    — the occupied plot's **terrain** yield factor, the **TFP** channel into `effectiveA`
    (`1.0` for a center-grouped/pending firm) — and `plotTravelTime(PlotOccupant)` =
    `2·T(index)`, the commute the labor market folds into `workFactor` (`0` for a
    center-grouped/pending firm). (The market overhead `N` is the labor market's own
    participant count, not per-plot.)
  - `getAgricultureClimateMultiplier()` is **kept** as a residual food-TFP layer (decided):
    climate acts both *through generation* (which terrains appear) **and** as this direct
    multiplier — `NFirm` retains its override. It also helps anchor the default colony near
    its current food TFP (calibration).
  - The plot-prep `BuildProject` work total = the improvement's **`iAdvancedStartCost`** (+
    feature **`iAdvancedStartRemoveCost`** when clearing) × terrain `buildModifier`; the
    builder raises the improvement, billed to the requesting firm.
- `agent/firm/ConsumerGoodFirm.java` — `effectiveA()` becomes
  `config.A() * getColony().getTechMultiplier(sector()) * getColony().plotYieldFactor(this, sector())`
  (terrain TFP only — the travel/market labor loss is applied in `LaborMarket`, not here).
- `agent/firm/NFirm.java` — **keep** the `getAgricultureClimateMultiplier()` override; its
  food TFP becomes `super.effectiveA()` (which now includes the plot yield factor) × the
  climate multiplier — both channels stack.
- `agent/firm/EFirm.java`, `CFirm.java`, `StrategicFirm.java`, `BuilderFirm.java`,
  `ScienceFirm.java` — these are **center-grouped by default**, so `plotYieldFactor` returns
  `1.0` (output unchanged) and their workers carry no commute term; the `plotYieldFactor`
  call can still be threaded through each one's `A`-equivalent term so that *giving* a type a
  building-on-a-plot later (a mine `CFirm` on a `ROCKY` plot, a coastal trading post) makes
  its terrain coupling live with no further wiring. Provisionally the only firms actually
  placed on plots are `NFirm` and the forage firm.
- `agent/firm/BuilderFirm.java`, `settlement/BuildProject.java`, `agent/firm/BuilderConfig.java`,
  `market/PeasantLabor` — the queue holds one **plot-prep** project per plot (clear feature +
  raise improvement; work = `iAdvancedStartCost` + `iAdvancedStartRemoveCost`, × `buildModifier`),
  funded by the requesting firm; `BuildProject.Kind` loses `ROAD`/`WALL` (and `BuilderConfig`
  its road/wall costs). The `PeasantLabor` market applies the same `workFactor` (market-`N` +
  commute to the build site) as `LaborMarket`, so building far plots is slower (skipped for
  province-less colonies).
- `settlement/GameSession.java` — load the shared `TerrainRegistry` and mint the per-colony
  terrain `Rng`, threading both into the `Settlement` constructor; **stop** loading/threading
  the now-deleted `SlotTable`.
- Docs: this note and `docs/daily-rhythm.md`; `docs/settlement-slots.md` was **deleted**
  (the disc model it described is gone); update the *Settlement build plots* / *Goods,
  climate* sections of `CLAUDE.md`.

## Procedural generation from climate

At founding, `TerrainGenerator` maps a province's environment to a weighted terrain pool and
generates the **whole plot-map** (all `province.plots` plots) — each a terrain +
(probabilistically) a plot-type and a feature, honouring the feature's `validTerrains` /
`requiresFlatlands` / `requiresRiver`:

- `TEMPERATE` → grassland / plains / forest-leaning
- `TROPICAL` → lush / jungle / flood-plain
- `ARID` → desert / scrub / dunes / oasis
- `ARCTIC` → tundra / taiga / permafrost
- `WinterSeverity` shifts the pool colder; `Monsoon` adds marsh / flood-plain weight.

It also rolls each plot's **`PlotType`** (`FLAT` / `HILL` / `PEAK`) — a small hill chance, a
smaller peak chance (a `PEAK` plot is unworkable) — and then a feature, honouring the
feature's `validTerrains` / `requiresFlatlands` (features that require flatlands skip hills)
/ `requiresRiver`. Rivers are not modelled per-plot yet, so river-gated features
(`FLOOD_PLAINS`) are rare or disabled until a river/freshwater signal exists (a future plot
attribute). A province-less colony bypasses generation entirely and uses the baseline
terrain (all `FLAT`).

## Calibration & validation

Wiring all three yields is **behavioural, not byte-identical**, for any province-bearing
colony — including the default `HomogeneousEconomy` (founded into Dhenijansar). The flat
climate multiplier is **kept** as a residual layer, which helps anchor the default colony
near its current food TFP. The plan therefore:

- keeps the **yield/travel/market coupling off for province-less colonies** (they bypass it),
  so it never perturbs them — though the **structural disc-removal still touches any
  builder-bearing colony** (the province-less `HanseaticEconomy`/sweeps included), which lose
  the dropped `ROAD`/`WALL` billing;
- tunes `REFERENCE` so the plot yield factor is ≈ 1.0 at the baseline plot+farm, leaving the
  retained climate multiplier as the dominant food-TFP term ≈ its pre-rework value, then
  re-validates the suite and a `CalibrationSweep`-style check, documenting the residual delta;
- treats the calibrated zero-profit-bank stability story as a constraint to re-confirm,
  the same way other couplings (calendar, daylight) were swept.

## Phased implementation plan

- **Phase 0 — data layer (no behaviour change). ✅ Implemented.** The `TerrainExporter`/
  `FeatureExporter`/`ImprovementExporter`/`BonusExporter` (parsing `data/CIV4*.xml` → curated
  JSON — full set for bonuses — via a shared `Civ4Xml` DOM helper); the
  `Terrain`/`Feature`/`Improvement`/`Bonus` records, the `BonusClass` enum; `TerrainRegistry`;
  the committed `terrains.json`/`features.json`/`improvements.json`/`bonuses.json`. Test:
  `TerrainRegistryTest` (registry loads; yields / clear-costs / improvement and bonus yields
  spot-checked against the XML).
- **Phase 1 — structural swap: plot list replaces the disc. ✅ Implemented.** Deleted
  `Slot`/`SlotTable`/`SlotInfo`/`size`/`slots.json` (and special sites); the colony holds a
  `List<Plot>` capped at `province.plots` (`PROVINCE_LESS_PLOT_CAP` when bare). `Plot` absorbs
  the occupant role (carrying its ladder index + terrain); claim/vacate became
  `claimPlot`/`vacatePlot`, the size methods became `getPlotCount`/`getMaxPlots`. `TerrainGenerator`
  generates each appended plot's terrain off a per-colony terrain RNG (salted apart from the
  economic stream); a province-less colony uses the baseline terrain uniformly. The builder grows
  one plot at a time (LAND/clear only — `ROAD`/`WALL` public works dropped, so `BuildProject` lost
  its `Kind` and `BuilderConfig` its road/wall costs; growth is fully firm-funded). Plots are not
  yet read for yield or travel. The capacity ceiling is now `province.plots` directly (74 for
  Dhenijansar, vs the old 29 effective slots), so province-/builder-bearing runs were re-validated
  rather than checksummed. Test: `PlotGenerationTest` (deterministic per seed; province-less
  uniformly baseline).
  - *Deferred to Phase 2b cleanup:* `FOUNDING_SERVICE_SLOTS` and the per-firm plot reservation
    stay until center-grouping lands (every firm still occupies a plot this cut).
- **Phase 2 — terrain yield → TFP (behavioural). ✅ Implemented.** `Plot.yieldFactor(Sector)`
  maps the plot's raw terrain yield through a per-index `YIELD_REFERENCE` (food `1.4`,
  production/commerce placeholders) floored at `YIELD_FLOOR` (`0.1`); `Settlement.plotYieldFactor`
  applies the staging gates (province-less → 1.0; only food/`NECESSITY` live this cut; a
  center-grouped/unseated firm → 1.0) and `ConsumerGoodFirm.effectiveA()` folds it in (`NFirm`
  still stacks the retained climate multiplier on top, both channels). `REFERENCE[food] = 1.4`
  is **Dhenijansar's expected terrain food yield** (its temperate + normal-monsoon pool), so the
  default colony's mean food factor ≈ 1.0 — its aggregate food TFP stays near its pre-rework
  value, with per-plot variation (grassland → 1.43, plains → 0.71) around the mean. Production
  and commerce are plumbed but dormant (gated off until a mine / trading-post firm sits on a
  plot). Re-validated against the full suite. Tests: `PlotYieldTest` (mean food factor ≈ 1.0;
  non-food gated; province-less/unseated → 1.0).
- **Phase 2b — the travel-time ladder → labor + center-grouping. ✅ Implemented.** `TravelLadder`
  gives the Fibonacci `oneWaySeconds(index)` and `workFactor(commute, N, D)`; `LaborMarket.clear()`
  computes `N` (= participant count) and `D` (= `getWorkWindowSeconds()`, sunrise→sunset, with a
  daylight-hours/polar fallback) per day and scales each worker's delivered labor by `workFactor`
  (on top of the skill + daylight scaling). The commute is `Settlement.plotTravelTime(occupant)` =
  `2·T(plotIndex)`. **Center-grouping** landed with it: `Firm.occupiesPlot()` (true only for
  `NFirm`) gates plot-claiming — only the farms sit on plots (and feel `N` + commute); capital/
  enjoyment/export/science/builder firms are center-grouped (consume no plot, feel only `N`).
  This retired `FOUNDING_SERVICE_SLOTS` (the founding necessity sizing now uses the whole plot
  budget) and made `hasRoomToExpand` apply only to the on-plot necessity sector in
  `Ruler.reviewSector`. A **province-less** colony bypasses the whole coupling (`workFactor == 1`,
  no `N`/commute) — byte-identical. The market overhead is ~1–1.5% at the default colony's scale,
  so the shift is small; re-validated against the full suite. Tests: `PlotTravelTest` (ladder,
  `workFactor`, `plotTravelTime`, province-less/unseated → 0). *Deferred:* the builder peasants'
  per-build-site commute (they feel only `N` this cut), the provisioning workFactor stop-rule, and
  the daily-rhythm consumption windows (`docs/daily-rhythm.md`, a separable change).
- **Phase 3 — improvements, clearing & wild plots. ✅ Implemented.** `Plot` now carries the
  three Civ4 land legs — its `Terrain`, an optional wild `Feature`, and the `Improvement` a
  firm raises on it — plus a `cleared` flag, and folds feature (while wild) + improvement into
  `yields()`. `TerrainGenerator.nextFeature` rolls a wild feature per plot from the terrain's
  valid, non-river features (`FEATURE_PROBABILITY` 0.35, a placeholder; river-gated features
  skipped — no per-plot river signal). The builder **raises the improvement the firm type
  fixes** (`Firm.plotImprovement()`; `NFirm` → `IMPROVEMENT_FARM`): `Settlement.requestGrowth`
  fixes the plot's land up front and costs the `BuildProject` at the improvement's
  `iAdvancedStartCost` + the feature's clear cost when wild, × the terrain `buildModifier`
  (`clearanceWork`); `completeFinishedPlots` develops the plot (raises the improvement,
  clearing the feature) before seating. Founding develops genesis plots for free
  (`developPlot`). `Plot.isWild()` (feature-bearing and uncleared) / `isCleared()` /
  `clearCost()` are the wild/cleared state — the seam the **forage firm** (a separate feature)
  will occupy (a `CAMP` raised *without* clearing leaves the plot wild, reading its
  feature-modified food yield). **Behavioural + recalibrated:** folding the `FARM` +2 into food
  TFP moved `YIELD_REFERENCE[food]` from 1.4 → **3.4** (Dhenijansar's terrain food ≈ 1.4 + the
  farm's +2), so the developed-farm mean food factor stays ≈ 1.0 and the full suite (incl. the
  collapse smoke tests) re-validated. A **province-less** colony generates no features and its
  yield factor stays bypassed. Tests: `PlotDevelopmentTest` (the three legs + wild/cleared
  lifecycle), updated `PlotYieldTest` (developed-farm mean ≈ 1.0). *Deferred:* the forage-firm
  agent itself (the substrate is laid, the agent is a separate feature); plot-type
  (hills/peaks) and bonuses (below).
  - *Bonuses are **not** wired here (decided).* Phase 0 exported the full bonus set as inert
    data, but placing bonuses on plots and folding their yield into `yieldFactor` is
    **deferred past Phase 3** — Phase 3's FARM/clearing/forage work is food-only and a FARM or
    `CAMP` needs no resource, so bonuses buy it nothing, while folding them in would force
    another `YIELD_REFERENCE` re-calibration on top of Phase 3's own build-cost change. The
    bonus extension lands with the **Production/Commerce activation** below — the extractive/
    commerce improvements (`PASTURE`/`PLANTATION`/`WINERY`/`MINE`) are where a resource is
    economically load-bearing (in Civ4 the improvement's value often *comes from* the bonus it
    sits on — see the improvement record's per-bonus `BonusTypeStruct` yields, currently scoped
    out by `Civ4Xml`). The two halves can split: generating bonuses onto plots is an inert
    `TerrainGenerator` extension (needs its own salted draw so it doesn't perturb the terrain
    stream) that could land cheaply anytime; the behavioural yield + REFERENCE recalibration
    waits for a bonus-consuming firm.
- **Phase 4 (optional / future).** Production/Commerce activation (an extractive `CFirm` →
  `MINE`/`QUARRY`, a commerce firm → `COTTAGE`/`PLANTATION`) and, with it, the **bonus
  extension** above (place bonuses, fold their yield into `yieldFactor`, recalibrate
  `YIELD_REFERENCE`); `Plots.csv` reporting; coastal/water plots & rivers; plot
  `healthPercent` → disease; feature spread (`iGrowth`).

## Test impact

- **Remove (pure disc/`SlotTable` model, all deleted):** `SlotTableTest` — it verifies the
  slot table vs the spreadsheet, founding at size 3 → 15 effective slots, growth to size 4,
  special sites, and `wallBuildTimePercent`/`claimSlot`/`claimSpecialSite`. None of those
  concepts survive.
- **Retarget (disc-size/effective-slot caps → `List<Plot>` capped at `province.plots`):**
  `SettlementProvinceTest` (the "size 4 → 29 effective slots, 30th occupant rejected" cap →
  "capped at `province.plots`, the `(plots+1)`th rejected"); `DefaultProvinceFoundingTest`
  (`getSize() <= getMaxSize()` / "cap at size 4" → plot count ≤ `province.plots`);
  `MigrantCaravanTest` (drops `session.getSlotTable()` + `maxSizeForPlots(...) >= MIN_SIZE`
  site-viability → a plot-count threshold on `chosen.plots()`).
- **Compile churn only (logic intact):** every test that constructs a `Settlement` directly
  and passes `SlotTable.load()` — `SettlementSolarTest`, `SettlementLifecycleTest`,
  `BankInheritanceTest` — just drop the `SlotTable` arg and add the `TerrainRegistry`.
- **Re-baseline (behavioural, not stale):** the full-run smoke tests
  (`ClosedColonySmokeTest`, the `Simulation*Test`s, `RetinueTest`, `HanseaticEconomyTest`,
  the sweeps) — Phase 1 drops `ROAD`/`WALL` billing and Phase 2/2b add the coupling, so the
  province-/builder-bearing runs shift; their invariants (`assertCollapsed`, etc.) should
  still hold but exact figures move. `LaborMarketTest` is unaffected if its colony is
  province-less (`workFactor == 1`), else re-baseline.

## Accepted limitations (out of scope for this cut)

1. **No 2D adjacency.** Plots are a 1-D ladder ordered by travel time, not a grid — no
   plot-to-plot adjacency, no "work the tiles around the city" radius.
2. **Laborers all live at the center (plot 0).** Until housing exists every worker commutes
   from plot 0, so the travel cost depends only on the *firm's* plot. Houses on plots (a
   worker living near where it works → reduced commute) are future work and are the natural
   next step once the ladder exists.
3. **The forage firm is enabled, not built.** Phase 3 laid the substrate (wild/cleared plot
   state via `Plot.isWild()`/`isCleared()`, generated wild features, the no-clearing
   `raiseImprovement(camp, false)` path); the forage-firm agent itself is a separate later
   feature — a `BuilderFirm`-like labor-only firm producing `Necessity`, *not* an `NFirm`
   subclass (see *The forage firm (future work)* for the full plan).
4. **Special sites dropped.** The special-site machinery is removed; the village hall's home
   is deferred to `docs/village-founding.md`.
5. **Water/coastal plots deferred.** Only land terrains are generated; fishing/coastal
   commerce is future work.
6. **Rivers deferred.** No per-plot river signal yet, so river-gated features are rare/off.
7. **Health, defense, movement, culture dropped or dormant.** Only the F/P/C yields, build
   modifier and clear cost (and the hill production bonus) are live; `iHealthPercent`/
   `iGrowth` are parsed but unused.
8. **Calibration is a deliverable, not free.** Phase 2 disturbs the default colony and must
   be re-tuned and re-validated; it is explicitly not byte-identical.

## Open questions

- The exact `REFERENCE` triple and `FLOOR`, set by the Phase-2 calibration.
- The climate → terrain-distribution weights (the generator tables) — placeholders pending
  a feel for the resulting colony food balance.
- **Climate multiplier — kept (decided).** `getAgricultureClimateMultiplier()` stays as a
  residual food-TFP layer on top of plot yields (belt-and-braces; aids calibration), so
  climate acts through both terrain generation and this multiplier. (Accepts a mild
  double-count of climate as the price of staying near today's calibrated behaviour.)
- **Working-day length `D`.** Now the exact `Duration.between(getSunrise(), getSunset())`
  in seconds (full window, minute/second precision). With seconds the grows-too-fast tension
  is gone (~21 plots workable at an 8 h day). **Decided: the polar `null`-sunrise fallback is
  `getDaylightHours() × 3600`, and the deep-winter penalty is accepted** (rather than flooring
  `D` or capping the targeted latitude) — short winter days legitimately shrink the workable
  frontier when output is already tight; the remaining work is just to confirm it stays
  survivable across the targeted temperate-to-subpolar range when the coupling is swept.
- **Which firm types operate an improvement (and which one).** `NFirm` → `FARM` (1:1 plot)
  and forage → `CAMP` are live/seamed. **Decided: the next activation is an extractive
  `CFirm` → `MINE`/`QUARRY`** on a `ROCKY`/`HILL` plot, which makes the **Production** channel
  live (and brings in the hill/peak `PlotType` and the bonus extension); a commerce firm →
  `COTTAGE`/`PLANTATION` (the **Commerce** channel) follows after. A per-firm-type mapping,
  still open beyond that order.
- **Improvement tech-gating — deferred (decided).** Not enforced; every improvement is
  buildable regardless of tech, and `PrereqTech` is stored dormant. **Decided: stay deferred
  for now** — enforcement (and feeding the tech-gated yield upgrades into `SectorProductivity`)
  waits until the first non-food improvements exist to gate, rather than gating `FARM`/`CAMP`
  this early.
- **Consumption windows — schedule-only (decided).** Dawn→sunrise (necessity) and
  sunset→dusk (enjoyment) place *when* the existing flat consumption happens; they do **not**
  scale quantity (see `docs/daily-rhythm.md`). This mechanic shares the solar clock but is
  **orthogonal to the plot/yield/travel work** and lands as a separate change.
- **`SlotTable`'s fate — removed (decided).** The disc geometry, `SlotTable`/`SlotInfo`, the
  `size` field, and the special-site machinery are deleted; a colony is a `List<Plot>` capped
  at `province.plots`. (The village hall's home is deferred to `docs/village-founding.md`.)
- **The provisioning stop-rule — decided (wire the `workFactor` cutoff).** The ruler's
  dynamic firm provisioning currently stops only at the hard `province.plots` cap; it should
  also stop chartering a new farm when the next available plot's `workFactor` (the commute
  gradient) makes it uneconomic — the principled Von Thünen cutoff, chosen over a plot-cap-only
  or profit/utilization-based rule. Implementation pending: wire into `Ruler.reviewSectors()` /
  `hasRoomToExpand` as its own small change.
