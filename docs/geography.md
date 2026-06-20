# Design note: geography (founding settlements into a province map)

**Status:** Phase 1 implemented (export + model + load + session cache + test);
Phases 2–4 proposed.
**Date:** 2026-06-20 (Phase 1 landed 2026-06-21)
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
the four-level hierarchy and the adjacency into one `provinces.json`:

```json
[
  { "id": 4411, "name": "Dhenijansar", "lat": 23.16, "lon": 76.43,
    "plots": 74, "waterPlots": 16, "type": "LAND",
    "region": "rahen_coast_region", "neighbors": [4385, 4405, 4410, 4412] }
]
```

The exporter keys each entry on `province_id`, resolves
`province → province_area → region` to the region's stable `raw_key`
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
  int waterPlots, ProvinceType type, String regionKey, List<Integer> neighbors)`
  (`id` and `neighbors` are `province_id`s; `regionKey` is the region `raw_key`,
  `null` if none). A `boolean isSettleable()` (true for `LAND` only at this
  stage) and `boolean isCoastal()` (`waterPlots > 0`) keep the policy on the type.
- **`ProvinceType`** — `LAND` / `SEA` / `LAKE`.
- **`WorldMap`** — loads `provinces.json`, holds `Map<Integer, Province>` plus
  the adjacency graph; exposes `province(id)`, `neighbors(id)`,
  `settleableProvinces()`, `findByName(name)`, and a `path(from, to)` BFS over the
  neighbor graph (the travel-network query). Immutable after load.

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
size 5** (78 plots > 74). So the default scenario now actually exercises the
province plot ceiling, capping `HomogeneousEconomy` at a small settlement rather
than letting it grow unbounded as it did at London. The analytical sweeps and any
scenario that still founds at explicit coordinates are unchanged.

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
  -Dsim.main=…`) writes the committed `/provinces.json`; `com.civstudio.geo`
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
- **Phase 2 — found a colony into a province.** The `newSettlement(…, Province,
  …)` overload; `Settlement.province`; province `latitude` → solar and `plots` →
  size cap. A single scenario (or a new demo) founds into a real province and
  runs; the solar times match the province's latitude. Existing
  coordinate-founded scenarios untouched.
- **Phase 3 — multi-province session.** Reseat `HanseaticEconomy`'s two colonies
  onto two neighboring provinces; assert they found at the right latitudes and
  that `WorldMap.path` connects them. This is the substrate ready for caravan
  routing.
- **Phase 4 — hand off to the dependent features.** Expose the travel-network
  queries to caravan trade and village founding (their notes own the movement);
  geography itself is complete at Phase 3.

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
