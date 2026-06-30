# Implementation plan: caravan trade Phase A — caravans on the map

**Status:** ✅ **implemented** (2026-06-22). Steps A1–A6 all landed; the full suite
(148 tests) is green. The only deferred item is deterministic mid-run re-founding
under `SessionRunner` (see Risk 1) — bands created mid-run register off-barrier, which
has no observable effect today and is left for the Phase-B integration.
**Date:** 2026-06-21 (implemented 2026-06-22)
**Implements:** `docs/caravan-trade.md` Phase A (the `Caravan` superclass +
province-anchored movement + the settler band wandering and re-founding *into a
province*). Phase B (`TradeCaravan`) and Phase C (richness) are out of scope.
**Depends on:** the world map (`com.civstudio.geo.WorldMap` / `Province`, the
neighbor adjacency + `path` query — `docs/geography.md`), the existing `Caravan`
machinery (`com.civstudio.agent.Caravan`, its dissolve→re-found cycle —
`docs/caravan.md`), `GameSession`'s multi-colony support and re-founding seam, and
`SessionRunner`'s lockstep day advance.

## Goal

Make a `Caravan` a citizen of the province graph — sit at a province, move along
neighbor edges one hop/day, and settle *into* a province — by extracting a
`Caravan` base + `MigrantCaravan` subclass, anchoring position to a `provinceId`,
and driving a per-day band tick on a session RNG. The existing dissolve→re-found
tests stay green.

## Key facts the plan is built on

- `Caravan` (`agent/Caravan.java`) is today a **concrete** class: `leader`,
  `following` (Retinue), `hoard`, `latitude`/`longitude`, `research`; static
  `dissolve(Settlement)` factory and a stub `moveTo(lat,lng)`.
- `Settlement.getProvince()` exists (`@Getter`), **null for bare-coordinate
  colonies**. `dissolveIntoCaravan()` (Settlement.java ~1093) calls
  `Caravan.dissolve(this)` then `session.addCaravan(band)`.
- `GameSession` already has `getWorldMap()` (lazy), `addCaravan`/`getCaravans()`,
  and `newSettlement(Caravan band, …)` (GameSession.java ~489) — which currently
  re-founds at the band's **raw coords** — plus a `newSettlement(…, Province)`
  overload (~377).
- `WorldMap` gives `province(id)`, `neighbors(id)`, `path(from,to)`,
  `settleableProvinces()`; `Province.isSettleable()`/`plots()`.
- **No session-level RNG exists yet** — `GameSession` only mints per-colony
  economic streams.
- `SessionRunner` advances colonies in lockstep via a `Phaser`; the coordinating
  party deregisters, so today nothing runs "between" colony days.
- **Backward-compat constraint:** `CaravanDissolutionTest` and `CaravanRefoundTest`
  both build **bare-coordinate** colonies (no province), so dissolution and
  re-founding must still work off-graph.

## Design decisions (resolve these first)

1. **Province id is optional on the base; lat/long stays as the off-graph
   fallback.** Base `Caravan` carries `int provinceId` (sentinel `-1` = off-graph)
   **and** `latitude`/`longitude`. When on-graph, lat/long are *derived* from the
   province; when off-graph (legacy/tests), they are the stored raw coords.
   `onGraph()` gates movement. This is what keeps the two existing tests green
   without forcing them onto the map.
2. **The band holds a `WorldMap` reference** (the session-shared, immutable graph)
   so it can derive its coordinates and validate moves. Passed in at construction
   for on-graph bands.
3. **Movement is one neighbour/day**, validated against `WorldMap.neighbors`. A
   multi-hop move walks a cached `WorldMap.path`.
4. **A new session-level band RNG** (salted, distinct from every per-colony
   economic stream) drives all wander/settle randomness, so a session with no
   bands draws nothing and stays byte-identical.
5. **Split the runtime responsibility:** `SessionRunner` ticks band *movement* each
   lockstep day (deterministic, single-threaded). Automatic re-founding of a
   settled band as a new concurrent colony is the riskiest integration — **keep
   re-founding driven explicitly by the scenario** (the reworked `CaravanEconomy`,
   which is sequential) for Phase A, and expose the settle *decision* so a
   concurrent runner can act on it later. (See Risks.)

## Work breakdown

### A1 — Extract the `Caravan` superclass + `MigrantCaravan`
**Files:** `agent/Caravan.java` (now abstract), new `agent/MigrantCaravan.java`;
update callers.

- Base `Caravan` (abstract): `leader`, `hoard`, `provinceId`, `worldMap`, derived
  `latitude`/`longitude`, `moveTo(int)`, `step(path)`, abstract `tick(Rng)`.
  - Two constructors: `(leader, hoard, provinceId, worldMap)` (on-graph) and
    `(leader, hoard, latitude, longitude)` (off-graph, `provinceId = -1`).
  - `moveTo(int dest)`: assert `onGraph()` and
    `worldMap.neighbors(provinceId).contains(dest)`, else throw; set
    `provinceId = dest`.
- `MigrantCaravan extends Caravan`: move `following` (Retinue), `research`, the
  static **`dissolve(Settlement)`** (returns `MigrantCaravan`), and the new
  wander/settle logic down here.
  - `dissolve` captures `colony.getProvince()` → `provinceId` (and the `WorldMap`
    from `colony.getSession().getWorldMap()`); if the colony has no province, fall
    back to raw coords + `provinceId = -1` (off-graph) so the legacy tests pass.
- Update references: `Settlement.dissolveIntoCaravan` (`Caravan.dissolve` →
  `MigrantCaravan.dissolve`; `departedBand` field type → `MigrantCaravan`),
  `GameSession.newSettlement(Caravan,…)`/`addCaravan`/`getCaravans` (keep `Caravan`
  type — `MigrantCaravan` is-a `Caravan`), and the two tests' `Caravan band =
  Caravan.dissolve(...)` → `MigrantCaravan`.
- **Checkpoint:** `mvn test` green (pure refactor, no behavior change yet).

### A2 — Session-level band RNG
**File:** `GameSession.java`.

- Add `SESSION_BAND_SEED_SALT` and a lazily-built `Rng bandRng()` (salted from
  `seed`), distinct from `COLONY_SEED_SALT`/mortality/skill/name salts.
  Synchronized access (bands on different colony threads may dissolve the same
  day).
- **Checkpoint:** no draws taken unless a band ticks → existing runs
  byte-identical.

### A3 — Province-anchored re-founding
**Files:** `GameSession.newSettlement(Caravan band,…)`,
`SimulationHarness.reFoundStandardColony`.

- Route `newSettlement(Caravan band,…)` through the **`Province` overload** when
  `band.onGraph()`, using `worldMap.province(band.getProvinceId())` — so the
  re-founded colony inherits that province's lat/long **and plots cap**. Keep the
  raw-coords path when off-graph (preserves `CaravanRefoundTest`).
- `reFoundStandardColony` is otherwise unchanged (it already rebuilds
  ruler/retinue/firms from the band).

### A4 — Wander + settle decision on `MigrantCaravan`
**File:** `MigrantCaravan.java`.

- `tick(Rng)`: consume the larder (the existing wandering ration), then either
  **move toward a target** or **settle**.
- **Target choice (deterministic, on band RNG):** on forming/arriving, pick the
  nearest **settleable** province with `plots ≥` the founding floor that isn't the
  abandoned one (BFS over `WorldMap.path`, ties broken on the band RNG). Cache the
  path; `step` one hop/day.
- **Readiness/settle gate:** settle on arrival at a viable province once readiness
  holds (following size + hoard above thresholds; urgency rising as the larder
  drains so a starving band settles for a worse site). Expose `isReadyToSettle()` /
  `chosenProvince()` so a runner can re-found it.
- **Determinism:** all randomness on the session band RNG; a band that never moves
  draws nothing.

### A5 — Drive the band tick from `SessionRunner`
**File:** `SessionRunner.java`.

- Subclass/override the `Phaser`'s `onAdvance(phase, parties)` to run, once per
  lockstep day on the tripping thread, `for (Caravan b : session.getCaravans())
  b.tick(session.bandRng())` — the natural single-threaded, deterministic host (it
  already owns the day barrier). Requires the runner to hold the `GameSession`
  (thread it through `runConcurrently`).
- **Recommended scope:** SessionRunner advances *movement* only this phase; settled
  bands are surfaced via `isReadyToSettle()`. Mid-run re-founding into a new live
  thread is deferred (see Risks).

### A6 — Rework `CaravanEconomy` to wander the graph
**File:** `CaravanEconomy.java`.

- Muster the three bands anchored at three **starting province ids** (instead of
  London/Paris/Rome raw coords). After dissolution each band **wanders** (`tick`
  loop on the band RNG) to a viable province, then re-founds *into that arrival
  province* (size now capped by its plots) and runs to collapse — demonstrating
  graph movement + province re-founding end to end. This is "geography Phase 4"
  made concrete and is the sequential testbed where re-founding is explicit.

## Test plan

- **Keep green:** `CaravanDissolutionTest`, `CaravanRefoundTest` (off-graph paths),
  `TwinSettlementEconomyTest`, the smoke suite.
- **New `CaravanMovementTest`:** a band seeded at a known province steps to a listed
  neighbour; `moveTo` a non-neighbour throws; a *k*-hop `path` takes *k* days.
- **New `MigrantCaravanSettleTest`:** a band wanders to a settleable province and
  settles; the re-founded colony's `getProvince()` == the arrival province and its
  `maxSize` reflects that province's plots; money/food conserved across
  dissolve→wander→re-found.
- **Determinism:** same seed → identical band path (assert the visited province
  sequence).

## Risks / things to confirm before coding

1. **Mid-run re-founding under `SessionRunner`** (adding a colony thread to a live
   Phaser run) is the genuinely hard part and is *not* required to demonstrate
   Phase A — `CaravanEconomy` is sequential. The plan defers automatic concurrent
   re-founding (surface the settle decision now, wire the thread join later).
2. **Off-graph bands must remain first-class** so the two existing bare-coordinate
   tests pass — that is why province id is a sentinel-`-1` optional rather than
   mandatory.
3. **`MigrantCaravan.dissolve` needs the `WorldMap`** from `colony.getSession()`; a
   session-less colony (some unit tests build `Settlement` directly) must fall back
   to off-graph — verify no direct-`Settlement` test dissolves.
4. **One-hop/day + nearest-viable-site** are explicit placeholders (per
   `docs/caravan-trade.md`'s accepted limitations); travel cost, naval routing, and
   `TradeCaravan` are Phases B/C.
