# Design note: caravans on the map — movement, settling, and trade

**Status:** Phase A **implemented** (the `Caravan` superclass + `MigrantCaravan`,
province-anchored movement, and the settler band wandering the graph and re-founding
*into a province*); Phase B (the settlement-sponsored `TradeCaravan` that couples two
economies) and Phase C remain proposed. See the implementation plan in
`docs/caravan-trade-phase-a-plan.md`.
**Date:** 2026-06-21 (Phase A landed 2026-06-22)
**Depends on:** the world map (`com.civstudio.geo.WorldMap` / `Province`, the
neighbor adjacency and `path` query — see `docs/geography.md`, Phases 1–3); the
existing `Caravan` machinery (`com.civstudio.agent.Caravan`, its dissolve→wander→
re-found cycle — see `docs/caravan.md`, Phases 0–4); `GameSession`'s multi-colony
support and the re-founding seam (`newSettlement(Caravan, …)`,
`SimulationHarness.reFoundStandardColony`); `SessionRunner`'s lockstep day
advance; and the consumer markets (`ConsumerGoodMarket`, its `Demand`-based
clearing) plus the ruler/treasury + noble-ownership machinery a sponsored venture
reuses.
**Related:** `docs/caravan.md` (the migration cycle this gives a *map* to — its
Phase 5 "movement, foraging, trade" is realized here), `docs/geography.md` (its
Phase 4 "hand the adjacency graph to the dependent features" is this note),
`docs/village-founding.md` (a settling band ascends `CARAVAN → HOLDING →
VILLAGE`).

## Motivation

Geography (Phases 1–3) built the map: 5264 provinces, a neighbor adjacency graph,
a `WorldMap.path` query, and colonies founded *into* provinces. The caravan cycle
(`docs/caravan.md`) is built too — a failing colony dissolves into a `Caravan`
that re-founds elsewhere. But the two have **never met**: a `Caravan`'s position
is a raw `latitude`/`longitude` pair disconnected from the graph, its
`moveTo(lat, lng)` is an explicit "seam for the future caravan-trade geography
work," nothing drives a band to actually move, and re-founding
(`newSettlement(Caravan, …)`) plants the new colony at the band's *raw
coordinates* rather than into a province. So a band "wanders" in name only — it
re-founds in place, off the graph.

This note connects them. A `Caravan` becomes a **citizen of the province graph**:
it sits at a province, moves along neighbor edges, and settles into a province.
That single change unlocks two features sharing one entity:

- **Settler bands actually migrate** — a collapsed colony's band wanders the graph
  to a viable site and re-founds there (closing `docs/caravan.md`'s deferred
  movement).
- **Trade caravans link economies** — a settlement funds a merchant convoy that
  carries goods to a neighbour and sells them, arbitrage flattening the price gap
  between the two markets (the first genuine inter-settlement economic coupling).

## The core decision: one `Caravan` superclass, specific subclasses

A single `Caravan` base type carries what every band on the map shares; concrete
subclasses add their purpose-specific payload. This keeps movement, position, and
the deterministic tick in one place and lets the migration band and the merchant
convoy diverge only where they genuinely differ.

```
Caravan (abstract)                     // a led band at a province that moves on the graph
  ├─ leader : Member                   // the band's captain (a Member, not a household)
  ├─ hoard : double                    // carried money, copper, outside any bank
  ├─ provinceId : int                  // its node on the graph (lat/long derived from it)
  ├─ moveTo(neighbour) / step(path)    // movement, validated against WorldMap adjacency
  └─ tick()                            // one day: advance and/or act; abstract hook
  │
  ├─ MigrantCaravan                    // the dissolution-born band that re-founds (docs/caravan.md)
  │    + following : Retinue           // the carried population (the asset/larder)
  │    + research : ResearchSnapshot   // the abandoned colony's tech, restored on re-founding
  │    + a settle decision (site choice) + re-found into a province
  │
  └─ TradeCaravan                      // a settlement-sponsored merchant convoy
       + sponsor : <settlement/owner>  // who funded it; profit returns here
       + cargo : goods manifest        // what it carries to sell
       + route : origin → destination  // a WorldMap.path it runs and returns along
       + buy at origin / sell at destination through the real markets
```

**What lives where.** The base holds only the universal band state — leader,
hoard, current province, movement, the daily tick. The **`Retinue` following and
larder are migration-specific** (a trade convoy carries goods, not a population to
resettle), so they move to `MigrantCaravan`; the **cargo/route/sponsor are
trade-specific**, on `TradeCaravan`. This is a refactor of today's `Caravan`
(which *is* the migration band): its `following`/`research`/`dissolve`/re-found
move down into `MigrantCaravan`, and the base keeps `leader`/`hoard`/position +
the new movement. The existing dissolve→re-found tests (`CaravanDissolutionTest`,
`CaravanRefoundTest`, `CaravanEconomy`) must stay green across the refactor.

## Architecture mapping

### Province-anchored position + movement (the enabling change)

- **`Caravan` gains `int provinceId`** — its node on the graph — and its
  `latitude`/`longitude` become **derived** from `worldMap.province(provinceId)`
  rather than stored. The band needs the `WorldMap` to move; it is a session-level
  aggregate and `GameSession` owns the map, so the map (or a small movement
  service) is handed to the band.
- **`moveTo(int provinceId)`** replaces `moveTo(lat, lng)` and **validates the
  destination is a neighbour** of the current node (`worldMap.neighbors`); a
  multi-hop move is a `step` along a `worldMap.path`. Movement is **one neighbour
  per day** (a placeholder rate — graph distance becomes travel time), so a route
  of length *k* takes *k* days.
- **`Caravan.dissolve(colony)` captures `colony.getProvince()`** as the band's
  starting node (today it captures raw `colony.getLatitude()/getLongitude()`), so
  a band born from a province-founded colony starts on the graph. (A colony
  founded at bare coordinates with no province cannot spawn a graph-anchored band;
  since the standard ruler-bearing colonies now found into provinces, this is the
  normal path.)

### The band tick — who moves a band each day

Bands live at the `GameSession` level, outside any colony's step loop;
`SessionRunner` advances only colonies. Movement adds a **per-day band update**:
after the colonies' lockstep day, each live `Caravan` ticks — consuming its larder
(migration) or advancing its route (trade), and acting on arrival. This rides a
**deterministic session-level RNG** (a salt distinct from the per-colony economic
streams, as `docs/village-founding.md`/`docs/caravan.md` flag) so "same seed →
identical run" holds with bands on the map. `SessionRunner` is the natural host
(it already coordinates the session's day barrier).

### `MigrantCaravan` — wander, choose a site, re-found into a province

- **Wandering** is graph movement on the larder clock: each day the band may step
  to a neighbour, consuming its carried larder (a decaying asset — the urgency
  `docs/caravan.md` describes, now spatial).
- **Site choice (the settle decision)** answers `docs/caravan.md`'s open question:
  the band settles when it reaches a **settleable `LAND` province** (per
  `Province.isSettleable`) with adequate `plots`, gated by a readiness signal
  (enough people + hoard; urgency rising as the larder drains). Until then it
  keeps moving. The choice must be deterministic (drawn on the session RNG).
- **Re-founding founds *into* the chosen province**: `newSettlement(Caravan, …)`
  routes through the Phase-2 `newSettlement(…, Province)` overload using the
  band's current province — so the re-founded colony gets that province's climate
  and plots cap (and a graph node), exactly like any founded colony. This replaces
  today's raw-coordinate re-founding; `SimulationHarness.reFoundStandardColony`
  is otherwise unchanged.

### `TradeCaravan` — settlement-sponsored, coupling economies through real markets

A trade caravan is a **merchant venture a settlement funds**, reusing the
ruler/treasury + ownership machinery:

- **Sponsorship.** A sponsor (the ruler from its gold treasury, or a merchant
  noble) funds the venture: it withdraws a stake (a hoard) and the caravan **buys
  export goods at the origin settlement's real `ConsumerGoodMarket`** (an actual
  `addBuyOffer`, so the purchase *raises* the origin price). Money and goods are
  conserved — the caravan is the courier.
- **Travel.** The caravan runs a `WorldMap.path` from the origin to a destination
  settlement (a neighbouring colony in the session), one hop/day; the cargo rides
  along.
- **Sale + coupling.** At the destination it **sells the cargo into that
  settlement's market** (a real sell offer, *lowering* the destination price),
  pocketing the spread. Because both legs clear through the actual markets,
  **arbitrage flattens the price difference** — the origin price rises, the
  destination falls — which is the genuine inter-settlement economic link (not a
  cosmetic profit). The venture may run a **return leg** carrying the
  destination's cheap goods back.
- **Profit returns to the sponsor**, closing the loop (treasury → venture →
  treasury), exactly as firm dividends flow to a noble today.
- **Calibration is the risk.** Trade volume relative to each market's size sets how
  hard arbitrage moves prices; too much couples them into one market, too little is
  cosmetic. This needs the same sweep treatment as the other calibrated couplings
  (the rest-day/daylight constants), and trade must not destabilize a colony that
  is otherwise solvent.

### Determinism & conservation

- All band movement/decisions ride the **session-level RNG**, never a colony's
  economic stream; a session with no bands draws nothing (byte-identical to today).
- Money is **conserved** end to end: a sponsor's stake leaves its treasury, becomes
  cargo at the origin market, returns as sale proceeds at the destination market,
  and the profit lands back in the treasury — no money created or destroyed, the
  same conservation the dissolve/re-found hoard already maintains.

## Accepted limitations (out of scope for this cut)

1. **One-neighbour-per-day movement is a placeholder.** Real travel cost/time
   (terrain, distance weighting, `province_relations` typed edges) is deferred;
   the first cut treats every edge as one day.
2. **`SEA`/`LAKE` routing is land-only first.** Bands route over `LAND` adjacency;
   naval legs (coastal `isCoastal` provinces, sea provinces as routable water) come
   later.
3. **Trade is settlement-sponsored only.** An independent merchant household (a
   merchant that owns caravans for its own account) is a later extension; the first
   cut funds ventures from a settlement's treasury.
4. **No `HOUSEHOLD → CARAVAN` gather and no raid/war band.** This note covers the
   settler and trade subclasses; a warband that gathers a following is reserved.
5. **Trade calibration is unproven.** The coupling's volume/price sensitivity is a
   tuning problem that may need the firm/market parameters revisited.

## Phased implementation plan

- **Phase A — `Caravan` superclass + province-anchored movement + the settler band
  migrates.** ✅ **Implemented** (2026-06-22; `MigrantCaravan`, the session band RNG,
  the `SessionRunner` band tick, the wander/settle decision, and the reworked
  `CaravanEconomy`). Extract the `Caravan` base (leader + hoard + `provinceId` + movement
  + tick); move `following`/`research`/dissolve/re-found into `MigrantCaravan`;
  anchor position to a province and validate `moveTo` against `WorldMap`
  adjacency; drive a per-day band tick from `SessionRunner` on the session RNG;
  make wandering + the settle decision (site choice) real; re-found *into* the
  chosen province. Reworks `CaravanEconomy` to wander the graph and keeps the
  existing dissolve/re-found tests green. This is the concrete "geography Phase 4."
- **Phase B — `TradeCaravan` (settlement-sponsored, market-coupled).** The trade
  subclass: a sponsor funds a venture, buys at the origin market, travels a
  `WorldMap.path` to a destination settlement, sells into its market (arbitrage
  flattening the gap), and returns the profit. A two-settlement scenario across two
  adjacent provinces (e.g. Withacen/Hopespeak) is the natural testbed: a
  trade caravan running between them should move both prices toward parity. Needs
  calibration of trade volume vs. market size.
- **Phase C — richness (later).** Real travel cost/time, naval/`SEA` routing,
  independent merchant households, the `HOUSEHOLD → CARAVAN` gather and raid bands,
  and `province_relations` as typed trade/border edges.

## Open questions deferred to later

- **Movement rate & travel cost** — one hop/day is a placeholder; what sets travel
  time and whether edges carry weights (`province_relations`).
- **Trade volume calibration** — how large a venture is, relative to market size,
  so coupling is meaningful but not destabilizing.
- **Who sponsors trade** — the ruler by default; whether a dedicated merchant noble
  (and later an independent merchant household) is the better actor.
- **Route/destination choice** — how a sponsor picks a destination (biggest price
  gap? nearest neighbour? a known partner?), and how that stays deterministic.
- **Settle-vs-keep-wandering** for the migrant band — the readiness signal and site
  scoring, and how they compose with determinism (shared with `docs/caravan.md`).
