# Design note: geography (founding settlements into a province map)

**Status:** Phases 1–3 implemented (export + model + load + session cache;
founding a colony into a province with province-sourced climate and a
plots-derived size cap; the dynamic provisioning respects that cap; the default
scenario founds into Dhenijansar; and a multi-province session founds two
adjacent provinces). Phase 4 proposed.
**Date:** 2026-06-20 (Phases 1–3 landed 2026-06-21)
**Depends on:** `GameSession`'s multi-colony support and its per-session shared
services (the `NameRegistry`/`DynastyPool`, the `LiturgicalCalendar`, the lazy
`TechTree` — `com.civstudio.settlement.GameSession`); the `newSettlement(…,
latitude, longitude, …)` seam that already carries a colony's coordinates; the
solar/daylight system that consumes them (`com.civstudio.solar.GeoLocation` and
`Settlement.newDay`'s daily solar recompute — see `docs/solar.md`); the slot
geometry a colony grows on (`com.civstudio.settlement.SlotTable`, founding at
`MIN_SIZE` and growing — see `docs/settlement-slots.md`); and the JSON
resource-loading convention every content table already follows
(`techs.json`, `dynasty-*.json`, loaded via Jackson).
**Related:** `docs/caravan.md` and `docs/village-founding.md` both defer
"wandering" and runtime founding because *"geography/movement does not exist —
settlements are isolated points"*; this note supplies the map they are blocked
on. `docs/race.md` is the sibling content-import (the 70-race roster); both
are fed by the same Strapi export step described below.

## Motivation

A colony today is a **single isolated point**: `SimulationConfig` carries one
hardcoded `latitude`/`longitude` (London, 51.5074 / −0.1278), `newSettlement`
plants the colony there, and the solar system derives that colony's daylight
from it. There is no map, no notion of *where one colony sits relative to
another*, and no adjacency — so a band cannot travel, a caravan cannot route
between settlements, and a village cannot be founded "somewhere on the map."
`docs/caravan.md` and `docs/village-founding.md` each call this out explicitly
as the missing substrate and defer their movement/founding features until it
exists.

Separately, an authored fantasy world already exists as Strapi-managed content
(the `strapi-civbox` Postgres database): a four-level geographic hierarchy of
**29 super-regions → 153 regions → 1375 province-areas → 5264 provinces**, each
province carrying a real latitude, a land/water plot count, a terrain type, and
— critically — a **neighbor adjacency graph**. This note imports that content as
the colony's map: the substrate that turns isolated points into a connected
world.

The guiding constraint, mirroring `docs/race.md`: **the import is additive and
the core sim stays Spring-free.** Content is exported from Strapi to a JSON
resource and loaded by the core through the same Jackson path that already loads
`techs.json`; the running simulation never touches Postgres. Existing scenarios
keep founding at their configured coordinates and are byte-unaffected until they
opt into a province.

## Scope: what comes in, and what is deliberately left out

The Strapi world model has several axes. This note imports **only the
geographic hierarchy and its adjacency**, and deliberately drops two adjacent
tables:

- **`name_groups` is out** — the existing per-race JSON name tables are kept;
  the DB name content is not imported.
- **`cultures` is out** (the mechanical-key role was declined; race carries the
  mechanics — see `docs/race.md`).
- **`countries` is therefore also out.** This is a *consequence*, not a separate
  choice: in the Strapi schema a country's only relational bridge to anything is
  `cultures_primary_country_lnk` (country ← culture). There is **no
  province → country link**. With culture dropped, a country cannot be tied to
  any province or settlement, so the 1450 country rows have no hook into the
  map. Countries return only if a political layer is later wanted; they are not
  part of the geographic substrate.

What remains — the geographic spine — is `super_regions`, `regions`,
`province_areas`, `provinces`, and the `provinces_neighbors_lnk` adjacency.

## The data (what the Strapi tables hold)

A `provinces` row carries, beyond Strapi bookkeeping:

| Column | Type | Meaning | Used for |
| --- | --- | --- | --- |
| `province_id` | int | the game's "used id" | **the map key** and the value the adjacency references (the Strapi surrogate `id` is not used) |
| `name` | text | display name | the settlement's place name |
| `latitude` | numeric(10,2) | decimal degrees, N positive | **solar/daylight** (replaces hardcoded London) |
| `plots` | int | land cells | **carrying capacity → a max-size cap** |
| `water_plots` | int | water cells | coastal/sea access (future fishing/sea trade) |
| `province_type` | text | `LAND` / `SEA` / `LAKE` | settleable (LAND only for now) vs. water |
| `min_x/min_y/max_x/max_y` | int | map bounding box (px) | **longitude**, derived from the box centroid (see below) |
| `r`/`g`/`b`, `hex_code` | int/text | map colour | rendering only (out of scope) |
| `svg_outer_bound` | text | polygon outline | rendering only (out of scope) |

Distribution: **4807 LAND, 388 SEA, 69 LAKE** (5264 total). LAND provinces
average ~672 plots; SEA provinces are large (avg ~12700 plots) and unsettleable.
Adjacency lives in `provinces_neighbors_lnk` (an undirected province↔province
graph keyed on the Strapi surrogate `id`, which the export translates to
`province_id`); `province_relations` (a separate `src`/`dest` province relation)
is a second, typed edge set left for later (see open questions).

**`province_id` is the key, and it is unique** — after a one-time source fix.
The raw table had **two double-imported duplicate `province_id`s** (Gate Islands
1058, Leliathail 2161 — two Strapi documents each, with their *relationships*
split across the copies: one held the region/area link, the other the neighbor
edges). They were merged in the source DB (edges + relations consolidated onto
one row, the redundant row deleted) so `province_id` is a clean unique key; the
export assumes this.

**Longitude is not stored** — only `latitude` is. The export **derives** it from
the province's bounding-box centroid. The whole world spans `x ∈ [0, 5631]` px
(and `y ∈ [0, 2047]`, which the precomputed `latitude` already encodes, so only
longitude is derived), so a province's longitude is a linear map of its centroid
`cx = (min_x + max_x) / 2`:

```
longitude = (cx - X_MIN) / (X_MAX - X_MIN) * 360 - 180        // X_MIN = 0, X_MAX = 5631
```

The global `X_MIN`/`X_MAX` are taken over all provinces at export time and baked
into `provinces.json` as a derived `lon` field, so the core never recomputes the
projection — it loads a latitude *and* a longitude per province.

## The model (proposed behaviour)

- **The world map is per-session, immutable, shared.** A `WorldMap` is loaded
  once per `GameSession` (like the `LiturgicalCalendar` and `TechTree`), holding
  the provinces and their adjacency. Colonies in one session read the same map;
  it is never mutated during a run.

- **A colony is founded *into* a province.** The province supplies the colony's
  geography: its `latitude` (and derived `longitude`) feed the solar system (so
  daylight, and therefore the daylight-scaled labor output, comes from the real
  location), its `plots` cap how large the settlement may grow, and
  `water_plots > 0` marks it coastal. **Only `LAND` provinces are settleable for
  now** — `LAKE` settlement (lakeshore land) and `SEA` are not settled at this
  stage; both are simply the water the travel/trade graph routes over.

- **The neighbor graph is the travel network.** `WorldMap` exposes adjacency and
  a shortest-path query over it. This is the substrate `docs/caravan.md` needs
  for routing a band/caravan between settlements and `docs/village-founding.md`
  needs for a band to *travel to* a spot and settle there. This note builds the
  graph and the queries; it does **not** itself implement movement or trade
  (those are the dependent features).

- **A settlement's slots *are* the province's plots — it cannot exceed them.**
  The simulation's build slots are measured in plots: a colony of a given
  `SlotTable` size occupies a disc of that many total plots. So the province's
  `plots` is a **hard ceiling on settlement size** — the colony may grow only
  while its total plot footprint (`SlotTable` total at the candidate size) stays
  `≤ province.plots`. This is a direct identity, not a tuned curve: the max size
  is the largest `SlotTable` size whose total plots fit in the province,
  composed with the existing `SlotTable.maxSize()`. A small province (the Rahen
  Coast LAND provinces run ~300–600 plots) therefore physically bounds how large
  its settlement can become, while the colony still founds at `MIN_SIZE` and
  grows one size at a time as today.

- **Existing scenarios are unaffected until they opt in.** A colony founded with
  explicit `latitude`/`longitude` (as every current scenario is) behaves exactly
  as today; the map is only consulted when a colony is founded *into a province*.
  The **default founding location moves from London to the Rahen super-region**
  (see *The default province: Rahen* below).

## Architecture mapping

### The export step (Strapi → JSON, core stays Spring-free)

Following `docs/race.md`'s import pattern and the project's Spring-free-core
rule, the geographic tables are **exported to a committed JSON resource**, not
read live. A small standalone JDBC exporter
(`com.civstudio.geo.export.ProvinceExporter`, run via `mvn exec:exec`) flattens
the four-level hierarchy and the adjacency into one `map/provinces.json` (the map
JSON resources live under `src/main/resources/map/`):

```json
[
  { "id": 4411, "name": "Dhenijansar", "lat": 23.16, "lon": 76.43,
    "plots": 74, "waterPlots": 16, "type": "LAND",
    "region": "rahen_coast_region", "area": "inner_rahen_area",
    "continent": "asia", "neighbors": [4385, 4405, 4410, 4412] }
]
```

(The `continent` string deserializes to the `Continent` enum; the `region` shown
is what `AreaExporter` re-derives through the area tier — see the package notes
below.)

The exporter keys each entry on `province_id`, resolves
`province → province_area → region` to the area's and region's stable `raw_key`s
(`null` when absent), derives `lon` from the bounding-box centroid, and
**materializes the `provinces_neighbors_lnk` edges symmetrically** (each stored
edge emitted in both directions, since the table stores each undirected edge
once) while **translating the surrogate-id adjacency to `province_id`**. It runs
on demand; the output is committed; the core loads it through Jackson exactly as
it loads `techs.json`. The running sim has no Postgres dependency and stays
reproducible and offline-capable.

### New package `com.civstudio.geo`

- **`Province`** — an immutable record:
  `Province(int id, String name, double latitude, double longitude, int plots,
  int waterPlots, ProvinceType type, String regionKey, String areaKey,
  Continent continent, Climate climate, WinterSeverity winter, Monsoon monsoon,
  List<Integer> neighbors)`
  (`id` and `neighbors` are `province_id`s; `regionKey`/`areaKey` are the region
  and area `raw_key`s, `continent` is the `Continent` enum (`null` if none), and
  `climate`/`winter`/`monsoon` are the environmental-attribute enums — the
  constructor coerces a `null` to the default `TEMPERATE`/`NONE`/`NONE`, so those
  three are never null). `boolean isSettleable()` and `boolean isPassable()`
  delegate to the `type`; `boolean isCoastal()` (`waterPlots > 0`) keeps the
  coastal policy.
- **`ProvinceType`** — `LAND` / `SEA` / `LAKE` / `IMPASSABLE`, each carrying
  `isSettleable()` (only `LAND`) and `isPassable()` (all but `IMPASSABLE`).
  `IMPASSABLE` (wasteland, overlaid from `climate.txt`) is neither settleable nor
  routable — caravans can't found into it or cross it.
- **`Climate`** (`TROPICAL`/`ARID`/`ARCTIC`/`TEMPERATE`) / **`WinterSeverity`**
  (`NONE`/`MILD`/`NORMAL`/`SEVERE`) / **`Monsoon`** (`NONE`/`MILD`/`NORMAL`/
  `SEVERE`) — per-province environmental attribute enums (peers of `ProvinceType`,
  not `GeoTier` places), overlaid from `data/climate.txt`. Each has
  `rawKey()`/`displayName()` and the default the `Province` constructor falls back
  to.
- **`GeoTier`** — the shared interface (`rawKey()`, `displayName()`) the tier types
  below implement, so the `WorldMap` and callers can treat any tier uniformly.
- **`Region`** / **`Area`** / **`SuperRegion`** — the geographic tiers above the
  province, immutable records (implementing `GeoTier`) loaded from committed
  `map/regions.json` / `map/areas.json` / `map/superregions.json`. `Area(String
  rawKey, String name, List<Integer> provinceIds)` lists the provinces it contains;
  `Region(String rawKey, String name, List<String> areaKeys)` lists its areas; and
  `SuperRegion(String rawKey, String name, List<String> regionKeys)` lists its
  regions — so the nesting is **province → area → region → super-region**.
- **`Continent`** — the coarsest tier, but a **parallel partition** that groups
  provinces *directly* (the source has no continent→region link). Unlike the
  open-ended tiers above it is a small fixed taxonomy, so it is a **`GeoTier`
  enum** (the 7 geographic continents, incl. the underground `serpentspine`) rather
  than a loaded resource: per-province membership lives on `Province.continent`,
  and there is **no `continents.json`**.
- The committed resources come from the Anbennar Clausewitz sources (`data/area.txt`,
  `data/region.txt`, `data/superregion.txt`, `data/continent.txt`) via four
  build-time exporters, `AreaExporter` / `RegionExporter` / `SuperRegionExporter` /
  `ContinentExporter`: `RegionExporter` and `SuperRegionExporter` write
  `map/regions.json` / `map/superregions.json` (no province stamp — these tiers hold
  child keys); `AreaExporter` writes `map/areas.json` **and** stamps each province's
  `area` key into `map/provinces.json` — plus its `region`, **re-derived through the
  area tier** so the committed `region` always matches `regionOf(id)` (overwriting
  the value `ProvinceExporter` took from the DB, a few of which disagreed);
  `ContinentExporter` stamps only the `continent` key (no resource), validating each
  block against the `Continent` enum; `ClimateExporter` stamps the
  `climate`/`winter`/`monsoon` keys (only the non-default values) and overrides
  `type` to `IMPASSABLE` for the wasteland provinces (over `LAND` only). The
  continent/climate fields have **no Strapi table**, so they are file-only (a full
  DB regen of `provinces.json` must rerun `ContinentExporter`/`ClimateExporter`).
  Empty placeholder areas/regions/super-regions (voided EU4-vanilla blocks), the
  `restrict_charter` keyword in super-region bodies, the non-geographic utility
  pseudo-continents (`debug_continent`, `island_check_provinces`, `new_world`), and
  the `equator_y_on_province_image` scalar in `climate.txt` are skipped. The run
  order is `ProvinceExporter → RegionExporter → SuperRegionExporter → AreaExporter
  → ContinentExporter → ClimateExporter` (each later stamp reads the committed
  `map/provinces.json`).
- **`WorldMap`** — loads the four `map/*.json` resources (`provinces`, `areas`,
  `regions`, `superregions`; continents are the enum), holds the province map +
  adjacency graph **and** the area/region/super-region/continent membership indices;
  exposes `province(id)`, `neighbors(id)`, `settleableProvinces()`,
  `findByName(name)`, `path(from, to)` (the travel-network BFS), plus the tier
  queries `areas()`/`regions()`/`superRegions()`/`continents()`,
  `area(key)`/`region(key)`/`superRegion(key)`,
  `provincesInArea`/`areasInRegion`/`provincesInRegion`/`regionsInSuperRegion`/
  `provincesInSuperRegion`/`provincesInContinent(Continent)`, and
  `areaOf(id)`/`regionOf(id)`/`superRegionOf(id)`/`continentOf(id)`, plus the
  environmental overlays `provincesInClimate`/`provincesInWinter`/
  `provincesInMonsoon` and `climateOf(id)`/`winterOf(id)`/`monsoonOf(id)`. **Areas
  are the source of truth for region membership** — `provincesInRegion`/`regionOf`
  resolve through the area tier (the union of a region's areas' provinces); super-
  regions resolve on through the region tier, and continents directly from each
  province's `continent`. The `path(from, to)` BFS routes only over **passable**
  provinces (impassable wasteland is never crossed, and an impassable endpoint is
  unreachable) — caravans likewise skip impassable provinces when seeking a site.
  Immutable after load.

### `GameSession` owns the map

`GameSession` gains a lazily-loaded `WorldMap` (cached, shared across the
session's colonies), beside the existing per-session `LiturgicalCalendar`/
`TechTree` caches. A new founding overload threads a province through to the
existing coordinate seam:

```java
Settlement newSettlement(String name, LocalDate startDate, …,
                         Province province, Race foundingRace, Map<Race,Double> raceMix);
```

which resolves the province's `latitude` and `longitude` and forwards to today's
`newSettlement(…, latitude, longitude, foundingRace, raceMix)`. So the import
**reuses the coordinate seam that already exists** rather than adding a parallel
one; the province is the new *source* of those coordinates. The current
explicit-coordinate overloads stay for the scenarios (and sweeps) that found at
a fixed point.

### `Settlement` learns its province

`Settlement` gains an optional `Province province` (null for a coordinate-founded
colony, preserving today's behaviour). When present it drives:

- **solar** — `latitude` already flows into the solar recompute; nothing new
  there beyond sourcing it from the province;
- **the size cap** — the colony's plot footprint (`SlotTable` total at its size)
  is held `≤ province.plots`; the growth loop already stops at a ceiling, and the
  province lowers it to the largest size that physically fits in the province;
- **coastal flags** — `isCoastal()` exposed for the future fishing/sea-trade
  features (no consumer yet).

### The default province: Dhenijansar (in Rahen)

The default founding location moves from London to **Dhenijansar** (Strapi
`province_id` 4411), a coastal LAND province in the **Rahen Coast** region of the
**Rahen** super-region — the South-Asian-flavoured tropical area (home of the
Raheni cultures, thematically adjacent to the Harimari of `docs/race.md`).
Its geography:

| Field | Value | Effect |
| --- | --- | --- |
| `latitude` | 23.16°N | warm, low-latitude daylight into the solar system |
| `longitude` | ~76°E (derived, `cx ≈ 4011`) | — |
| `plots` | **74** | hard size ceiling |
| `water_plots` | 16 | coastal (`isCoastal()` true) |
| `province_type` | LAND | settleable |

The 74-plot ceiling is **illustratively tight**, which is the point: a colony
founds at `SlotTable.MIN_SIZE` (size 3, **28** total plots, 15 effective slots) —
which fits — and may grow to **size 4** (50 plots, 29 effective slots) but **not
size 5** (78 plots > 74). So the default scenario exercises the province plot
ceiling, capping `HomogeneousEconomy` at a small settlement rather than letting it
grow unbounded as it did at London: the necessity sector ramps to ~22 food firms
(plus the capital/builder/export firms, ≈27 of the 29 slots) and then the dynamic
provisioning stops chartering — the size-4 cap holds. **This repoint is live**
(Phase 2.5): the provisioning respects the cap (`Settlement.hasRoomToExpand`)
instead of overrunning it. The analytical sweeps and any scenario that founds at
explicit coordinates are unchanged.

### How the dependent features consume it

- **Caravan trade (`docs/caravan.md`)** routes a band over `WorldMap`'s
  adjacency (`path(from, to)`), settlements being province-anchored nodes.
- **Village founding (`docs/village-founding.md`)** has a band *travel to* a
  settleable province and found there via the `Caravan` `newSettlement` overload
  (already in tree); the province supplies the new colony's climate and capacity
  "for free," exactly as that note anticipates ("its `lat/long` flows into
  `newSettlement` and the solar/latitude system gives the new village its climate
  for free").
- **`HanseaticEconomy`** (two colonies in one session) is the natural first
  multi-province test: reseat its two colonies onto two **neighboring** real
  provinces so their adjacency is exercised.

## Accepted limitations (out of scope for this cut)

1. **No movement or trade here.** This note builds the *map and its queries*; it
   does not move anything over it. Caravan routing and village founding are the
   dependent features (their own notes), unblocked by — not implemented in —
   this one.
2. **No rendering.** The map-colour (`r`/`g`/`b`/`hex`) and polygon outline
   (`svg_outer_bound`) columns are exported-or-skipped but unused by the sim;
   visualising the world is a separate concern.
3. **`countries` and `cultures` are dropped** (see *Scope*) — no political layer,
   no culture-keyed content. Countries have no map hook without culture.
4. **`province_relations` (typed src/dest edges) is deferred.** The first cut
   uses only the plain undirected `neighbors` adjacency; weighted/typed edges
   (trade routes, borders) come with the trade feature if needed.
5. **`LAKE` provinces are not settleable yet.** Only `LAND` is settled at this
   stage; lakeshore settlement is future work.

## Phased implementation plan

- **Phase 1 — export + model + load. (Implemented.)** The exporter
  (`com.civstudio.geo.export.ProvinceExporter`, a standalone JDBC `main` reading
  `GEO_DB_URL`/`GEO_DB_USER`/`PGPASSWORD`, run via `mvn exec:exec
  -Dsim.main=…`) writes the committed `/map/provinces.json`; `com.civstudio.geo`
  holds the `Province` record (Jackson, with `isSettleable()`/`isCoastal()`),
  the `ProvinceType` enum, and `WorldMap` (load + `province`/`neighbors`/
  `settleableProvinces`/`findByName`/`path` BFS); `GameSession.getWorldMap()`
  caches it lazily like the tech tree. No colony consumes it yet — pure additive
  load, every run byte-identical. `com.civstudio.geo.WorldMapTest` asserts the
  map loads (5264 provinces), the adjacency is symmetric and dangling-free,
  Dhenijansar carries its pinned geography (`province_id` 4411, region
  `rahen_coast_region`, neighbors `[4385, 4405, 4410, 4412]`), region keys are
  stable strings or null, and `path` walks the graph. **The map keys on the
  game's `province_id`** (the "used id"), not the Strapi surrogate `id`; the
  adjacency's surrogate references are translated to `province_id` at export.
  This required a **one-time source fix**: `province_id` had two double-imported
  duplicates (Gate Islands 1058, Leliathail 2161) whose relationships were split
  across the copies, merged in the DB so the key is unique (5266 → 5264 rows).
  **167 open-ocean provinces have no region** (exported as `null`).
- **Phase 2 — found a colony into a province. (Implemented.)**
  `GameSession.newSettlement(…, Province, …)` (and a mono-human overload) resolve
  the province's `latitude`/`longitude` and thread the `Province` through the
  shared `buildSettlement` body into `Settlement.province` (nullable —
  coordinate-founded colonies keep `null`). `Settlement` computes a `maxSize`
  ceiling = `min(slotTable.maxSize(), slotTable.maxSizeForPlots(province.plots()))`
  (new `SlotTable.maxSizeForPlots` — the largest size whose `total` plots fit),
  and the two growth paths (`foundOnto` genesis, `requestGrowth` live) cap on it;
  a province too small for the founding floor (`MIN_SIZE`) is rejected.
  `SimulationHarness.create(cfg, seed, Province)` lets a scenario found into a
  province. `com.civstudio.settlement.SettlementProvinceTest` founds into
  Dhenijansar and asserts the province-sourced lat/long, the size cap (`maxSize`
  4; the 30th occupant cannot be seated past size 4's 29 slots), a milder
  tropical winter daylight than London, that a coordinate-founded colony stays
  uncapped, and that a too-small province is rejected. Existing scenarios found
  with `province == null`, so `maxSize == slotTable.maxSize()` — byte-identical
  (full suite green, 141 tests).
- **Phase 2.5 — provisioning respects the cap; default founds into Dhenijansar.
  (Implemented.)** Founding a *live* colony into a small province would otherwise
  crash: the ruler's monthly provisioning ramps to many firms, and at the size
  cap `requestGrowth` throws ("cannot grow past its maximum size"). The fix is a
  capacity gate — `Settlement.hasRoomToExpand()` (a vacant slot now, or `size <
  maxSize`) — that `Ruler.reviewSector` checks before chartering: a colony full at
  its plots-capped maximum simply stops chartering (the sector stays
  supply-constrained, but the province has no room), rather than failing to grow.
  With the gate in place, `HomogeneousEconomy` is repointed to found into
  Dhenijansar (`SimulationHarness.create(cfg, seed, provinceId)` resolving the
  province from the session's world map): it ramps the food sector to ~22 firms,
  fills the size-4 footprint, and runs cleanly to collapse. A colony with no
  province cap never trips the gate (its `maxSize` is the slot table's own
  ceiling), so the analytical sweeps and coordinate-founded scenarios are
  byte-identical. Covered by `DefaultProvinceFoundingTest` (the default founds
  into Dhenijansar, `maxSize` 4, no crash) and the existing
  `ClosedColonySmokeTest` (the full run no longer throws and still departs as a
  caravan). No recalibration of the macro parameters proved necessary — the
  colony collapses cleanly under the tighter footprint.
- **Phase 3 — multi-province session. (Implemented.)** `HanseaticEconomy`'s two
  colonies are reseated from the old hardcoded Lübeck/Schwartau coordinates onto
  two **adjacent world-map provinces** — **Withacen** (`province_id` 515, 55.97°N,
  189 plots) and **Hopespeak** (519, 54.83°N, 255 plots), direct neighbours at a
  northern ~55°N (Hanseatic-like) latitude — founded via the new province-aware
  `newSettlement`. The colonies are renamed to their provinces; both still found
  from one `GameSession` with disjoint surname slices and run concurrently in
  lockstep to caravan departure (the size cap and the Phase 2.5 provisioning gate
  apply per colony). `HanseaticEconomyTest` now also asserts the returned colony
  was founded into Withacen at its latitude, and that the two provinces are
  adjacent (`WorldMap.path(515, 519) == [515, 519]`, a one-step route) — the
  travel-network substrate caravan trade will route over. Full suite green (143
  tests).
- **Phase 4 — hand off to the dependent features. (Designed — see
  [`docs/caravan-trade.md`](caravan-trade.md).)** Geography itself is complete at
  Phase 3; the travel-network consumers are designed in their own note: a
  `Caravan` superclass anchored to a province that moves along the neighbor graph,
  with a **settler** subclass (the migration band wanders to a settleable province
  and re-founds *into* it) and a **trade** subclass (a settlement-sponsored
  merchant convoy that buys at one settlement's market and sells at a neighbour's,
  arbitrage coupling the two economies). Phase A of that note (province-anchored
  movement + the settler band) is the concrete realization of this hand-off.

## Decided (folded in above)

- **Longitude** — derived from the bounding-box centroid at export time and baked
  into `provinces.json` as a `lon` field (see *The data*).
- **`plots` → size** — not a curve: settlement slots *are* plots, so
  `SlotTable` total ≤ `province.plots` is a hard ceiling (see *The model*).
- **Default province** — **Dhenijansar** (`province_id` 4411, Rahen Coast)
  replaces London (see *The default province: Dhenijansar*).
- **`LAKE`** — not settleable at this stage; `LAND` only.

## Open questions deferred to later

- **Whether `water_plots` factors into the size ceiling** — currently only `plots`
  (land) bounds size; coastal land's water capacity is unused until sea trade.
- **`province_relations`** — whether to import the typed src/dest edges as a
  second graph (trade/border relations) when the trade feature lands.
- **Map scale vs. colony scale.** Dhenijansar is only 74 plots (caps the colony at
  size 4); other LAND provinces run into the hundreds, and SEA provinces into the
  tens of thousands. Whether one colony fills a province, or a province holds
  several settlements, is a modelling decision the founding/trade features will
  force — and the wide plot range means the answer may differ by province.
