# Design & plan: caravans on the map — movement, settling, and trade

**Status (by phase):**
- **Phase A — `Caravan` superclass + province-anchored movement + the settler band
  migrates.** ✅ **Implemented** (2026-06-22).
- **Phase B — `TradeCaravan` (settlement-sponsored, market-coupled trade).** Proposed
  (design + implementation plan only — not yet built).
- **Phase C — richness (real travel cost, naval routing, independent merchants, raid
  bands, typed edges).** Proposed / later.

**Date:** design 2026-06-21; Phase A landed 2026-06-22; Phase B plan 2026-06-28.
Consolidated 2026-07-01 (merged the former `caravan-trade.md`,
`caravan-trade-phase-a-plan.md`, and `caravan-trade-phase-b-plan.md` into this one
document).

**Depends on:** the world map (`com.civstudio.geo.WorldMap` / `Province`, the neighbor
adjacency and `path` query — see `docs/geography.md`, Phases 1–3); the existing
`Caravan` machinery (`com.civstudio.agent.Caravan`, its dissolve→wander→re-found cycle
— see `docs/caravan.md`, Phases 0–4); `GameSession`'s multi-colony support and the
re-founding seam (`newSettlement(Caravan, …)`, `SimulationHarness.reFoundStandardColony`);
`SessionRunner`'s lockstep day advance; and the consumer markets (`ConsumerGoodMarket`,
its `Demand`-based clearing) plus the ruler/treasury + noble-ownership machinery a
sponsored venture reuses.

**Related:** `docs/caravan.md` (the migration cycle this gives a *map* to — its Phase 5
"movement, foraging, trade" is realized here), `docs/geography.md` (its Phase 4 "hand
the adjacency graph to the dependent features" is this note), `docs/village-founding.md`
(a settling band ascends `CARAVAN → HOLDING → VILLAGE`).

---

## Motivation

Geography (Phases 1–3) built the map: 5264 provinces, a neighbor adjacency graph, a
`WorldMap.path` query, and colonies founded *into* provinces. The caravan cycle
(`docs/caravan.md`) is built too — a failing colony dissolves into a `Caravan` that
re-founds elsewhere. But the two had **never met**: a `Caravan`'s position was a raw
`latitude`/`longitude` pair disconnected from the graph, its `moveTo(lat, lng)` was an
explicit "seam for the future caravan-trade geography work," nothing drove a band to
actually move, and re-founding (`newSettlement(Caravan, …)`) planted the new colony at
the band's *raw coordinates* rather than into a province. So a band "wandered" in name
only — it re-founded in place, off the graph.

This note connects them. A `Caravan` becomes a **citizen of the province graph**: it
sits at a province, moves along neighbor edges, and settles into a province. That single
change unlocks two features sharing one entity:

- **Settler bands actually migrate** (Phase A, done) — a collapsed colony's band wanders
  the graph to a viable site and re-founds there (closing `docs/caravan.md`'s deferred
  movement).
- **Trade caravans link economies** (Phase B, proposed) — a settlement funds a merchant
  convoy that carries goods to a neighbour and sells them, arbitrage flattening the price
  gap between the two markets (the first genuine inter-settlement economic coupling).

---

## The core decision: one `Caravan` superclass, specific subclasses

A single `Caravan` base type carries what every band on the map shares; concrete
subclasses add their purpose-specific payload. This keeps movement, position, and the
deterministic tick in one place and lets the migration band and the merchant convoy
diverge only where they genuinely differ.

```
Caravan (abstract)                     // a led band at a province that moves on the graph
  ├─ leader : Member                   // the band's captain (a Member, not a household)
  ├─ hoard : double                    // carried money, copper, outside any bank
  ├─ provinceId : int                  // its node on the graph (lat/long derived from it)
  ├─ worldMap : WorldMap               // the session-shared, immutable graph
  ├─ moveTo(neighbour) / step(path)    // movement, validated against WorldMap adjacency
  └─ tick(Rng)                         // one day: advance and/or act; abstract hook
  │
  ├─ MigrantCaravan                    // the dissolution-born band that re-founds (docs/caravan.md)
  │    + following : Retinue           // the carried population (the asset/larder)
  │    + research : ResearchSnapshot   // the abandoned colony's tech, restored on re-founding
  │    + a settle decision (site choice) + re-found into a province
  │
  └─ TradeCaravan (proposed)           // a settlement-sponsored merchant convoy
       + sponsor : Ruler/owner         // who funded it; profit returns here
       + proxy : TradeAgent            // the Agent that carries cargo + posts market offers
       + route : origin → destination  // a WorldMap.path it runs and returns along
       + buy at origin / sell at destination through the real markets
```

**What lives where.** The base holds only the universal band state — leader, hoard,
current province, the map reference, movement, the daily tick. The **`Retinue` following
and larder are migration-specific** (a trade convoy carries goods, not a population to
resettle), so they live on `MigrantCaravan`; the **cargo/route/sponsor are
trade-specific**, on `TradeCaravan`. Phase A was a refactor of the old concrete
`Caravan` (which *was* the migration band): its `following`/`research`/`dissolve`/
re-found moved down into `MigrantCaravan`, and the base kept `leader`/`hoard`/position +
the new movement.

---

## Shared architecture (the enabling change)

### Province-anchored position + movement

- **`Caravan` carries `int provinceId`** — its node on the graph — with a sentinel
  `-1` = **off-graph**, and `latitude`/`longitude` retained as the off-graph fallback.
  On-graph, lat/long are **derived** from `worldMap.province(provinceId)`; off-graph
  (legacy / bare-coordinate colonies and their direct-`Settlement` unit tests), they are
  the stored raw coords. `onGraph()` gates movement. Keeping province id *optional* is
  what let the existing bare-coordinate tests stay green without forcing them onto the
  map.
- **The band holds a `WorldMap` reference** (session-shared, immutable) so it can derive
  its coordinates and validate moves; passed in at construction for on-graph bands.
- **`moveTo(int provinceId)`** replaces `moveTo(lat, lng)` and **validates the
  destination is a neighbour** of the current node (`worldMap.neighbors`); a multi-hop
  move is a `step` along a cached `worldMap.path`. Movement is **one neighbour per day**
  (a placeholder rate — graph distance becomes travel time), so a route of length *k*
  takes *k* days.
- **`MigrantCaravan.dissolve(colony)` captures `colony.getProvince()`** as the band's
  starting node (the old code captured raw `getLatitude()/getLongitude()`), so a band
  born from a province-founded colony starts on the graph. A colony founded at bare
  coordinates with no province (`getProvince() == null`) falls back to raw coords +
  `provinceId = -1`; since the standard ruler-bearing colonies now found into provinces,
  the graph path is the normal one.

### The band tick — who moves a band each day

Bands live at the `GameSession` level, outside any colony's step loop; `SessionRunner`
advances only colonies. Movement adds a **per-day band update**: after the colonies'
lockstep day, each live `Caravan` ticks — consuming its larder (migration) or advancing
its route (trade), and acting on arrival. `SessionRunner` hosts it by overriding the
`Phaser`'s `onAdvance(phase, parties)`, which runs **once, on the tripping thread, after
every colony has finished the day (`act` → `clear` → `print`) and before any starts the
next** — a single-threaded window in which every colony thread is parked at
`arriveAndAwaitAdvance`. The loop is `SessionRunner.tickBands(session, …)`:
`for (Caravan b : session.getCaravans()) b.tick(session.getBandRng())`.

### Determinism & conservation

- All band movement/decisions ride a **session-level band RNG** (`GameSession.getBandRng()`,
  salted `RngSeed.Stream.BAND`, distinct from every per-colony economic/mortality/skill/
  naming stream), so a session with no bands draws nothing and stays byte-identical to a
  band-free run. Synchronized access, since bands on different colony threads may be
  created the same day.
- Money is **conserved end to end**. A band's `hoard` is money held *outside* any bank —
  the same session-level carrier `MigrantCaravan.dissolve` uses (`Bank.drainAllMoney` →
  hoard). The conserving primitives are `Bank.injectExternalFunds(amt)` (adds outside
  money to a colony's equity), `payFromEquity(id, amt)` (moves equity into an account),
  `extractExternalFunds(id, amt)` (destroys money out of a colony via an account), and
  `extractExternalEquity(amt)` (destroys equity directly). Migration moves a whole colony's
  money through the hoard; trade moves money *between* two colony supplies through the
  hoard — conserved session-wide, while each colony's local supply legitimately rises
  (goods sold in) or falls (goods bought out).

---

## Phase A — the settler band migrates ✅ (implemented 2026-06-22)

### What it does

- **Wandering** is graph movement on the larder clock: each day the band may step to a
  neighbour, consuming its carried larder (a decaying asset — the urgency
  `docs/caravan.md` describes, now spatial).
- **Site choice (the settle decision)** answers `docs/caravan.md`'s open question: the
  band settles when it reaches a **settleable `LAND` province** (per
  `Province.isSettleable`) with adequate `plots`, gated by a readiness signal (following
  size + hoard above thresholds; urgency rising as the larder drains, so a starving band
  settles for a worse site). Until then it keeps moving. The choice is deterministic
  (drawn on the band RNG).
- **Re-founding founds *into* the chosen province**: `newSettlement(Caravan, …)` routes
  through the `newSettlement(…, Province)` overload using the band's current province — so
  the re-founded colony gets that province's climate and plots cap (and a graph node),
  exactly like any founded colony. This replaced the old raw-coordinate re-founding;
  `SimulationHarness.reFoundStandardColony` is otherwise unchanged (it already rebuilds
  ruler/retinue/firms from the band).

### Key facts the plan was built on

- `Caravan` (`agent/Caravan.java`) was a **concrete** class: `leader`, `following`
  (Retinue), `hoard`, `latitude`/`longitude`, `research`; static `dissolve(Settlement)`
  factory and a stub `moveTo(lat,lng)`.
- `Settlement.getProvince()` exists (`@Getter`), **null for bare-coordinate colonies**;
  `dissolveIntoCaravan()` calls `Caravan.dissolve(this)` then `session.addCaravan(band)`.
- `GameSession` already had `getWorldMap()` (lazy), `addCaravan`/`getCaravans()`, and
  `newSettlement(Caravan band, …)` (which re-founded at the band's raw coords) plus a
  `newSettlement(…, Province)` overload.
- `WorldMap` gives `province(id)`, `neighbors(id)`, `path(from,to)`,
  `settleableProvinces()`; `Province.isSettleable()`/`plots()`.
- **No session-level RNG existed yet** — `GameSession` minted only per-colony economic
  streams.
- `SessionRunner` advances colonies in lockstep via a `Phaser`; the coordinating party
  deregisters, so nothing ran "between" colony days.
- **Backward-compat constraint:** `CaravanDissolutionTest` and `CaravanRefoundTest` build
  **bare-coordinate** colonies (no province), so dissolution and re-founding must still
  work off-graph.

### Design decisions

1. **Province id is optional on the base; lat/long stays as the off-graph fallback**
   (sentinel `-1`), so on-graph bands derive coords from the province while legacy/tests
   keep raw coords. `onGraph()` gates movement.
2. **The band holds a `WorldMap` reference**, passed at construction for on-graph bands.
3. **Movement is one neighbour/day**, validated against `WorldMap.neighbors`; a multi-hop
   move walks a cached `WorldMap.path`.
4. **A session-level band RNG** (`getBandRng()`, salted, distinct from every per-colony
   stream) drives all wander/settle randomness, so a band-free session stays
   byte-identical.
5. **Split the runtime responsibility:** `SessionRunner` ticks band *movement* each
   lockstep day (deterministic, single-threaded). Automatic re-founding of a settled band
   as a new concurrent colony is the riskiest integration and was **kept explicit in the
   scenario** (the sequential `CaravanEconomy`) for Phase A, exposing the settle
   *decision* (`isReadyToSettle()` / `chosenProvince()`) so a concurrent runner can act on
   it later.

### Work breakdown (as landed)

- **A1 — Extract the `Caravan` superclass + `MigrantCaravan`.** `Caravan` became abstract
  (`leader`, `hoard`, `provinceId`, `worldMap`, derived `latitude`/`longitude`,
  `moveTo(int)`, `step(path)`, abstract `tick(Rng)`), with on-graph
  `(leader, hoard, provinceId, worldMap)` and off-graph `(leader, hoard, lat, lng)`
  constructors. `moveTo(int dest)` asserts `onGraph()` and neighbour membership, else
  throws. `MigrantCaravan extends Caravan` took `following` (Retinue), `research`, the
  static `dissolve(Settlement)` (now returning `MigrantCaravan`, capturing
  `colony.getProvince()` and the `WorldMap`, falling back to raw coords + `-1` off-graph),
  and the wander/settle logic. Callers updated (`Settlement.dissolveIntoCaravan`'s
  `departedBand` field type, `GameSession` caravan APIs keeping the `Caravan` type, the
  two legacy tests).
- **A2 — Session-level band RNG.** `GameSession.getBandRng()` (lazily built, salted
  `RngSeed.Stream.BAND`, synchronized), distinct from the colony/mortality/skill/name
  salts. No draw unless a band ticks.
- **A3 — Province-anchored re-founding.** `newSettlement(Caravan band, …)` routes through
  the `Province` overload when `band.onGraph()` (using
  `worldMap.province(band.getProvinceId())`), so the re-founded colony inherits the
  province's lat/long **and plots cap**; the raw-coords path stays for off-graph bands.
- **A4 — Wander + settle decision on `MigrantCaravan`.** `tick(Rng)` consumes the larder,
  then moves toward a target or settles. Target choice (deterministic on the band RNG):
  the nearest **settleable** province with enough `plots` that isn't the abandoned one
  (BFS over `WorldMap.path`, ties broken on the band RNG); cache the path, `step` one
  hop/day. Readiness/settle gate on following size + hoard, urgency rising as the larder
  drains; `isReadyToSettle()` / `chosenProvince()` expose the decision.
- **A5 — Drive the band tick from `SessionRunner`.** Override the `Phaser`'s
  `onAdvance` to run the band tick once per lockstep day on the tripping thread
  (`tickBands`); the runner holds the `GameSession` (threaded through `runConcurrently`).
  This phase advances *movement* only; settled bands are surfaced for the scenario to
  re-found.
- **A6 — Rework `CaravanEconomy` to wander the graph.** It musters bands anchored at
  starting **province ids** (not raw London/Paris/Rome coords); after dissolution each
  band **wanders** to a viable province and re-founds *into that arrival province* (size
  capped by its plots), running to collapse — graph movement + province re-founding end to
  end, the concrete "geography Phase 4" and the sequential testbed where re-founding is
  explicit.

### Tests (as landed)

- **Kept green:** `CaravanDissolutionTest`, `CaravanRefoundTest` (off-graph paths),
  `TwinSettlementEconomyTest`, the smoke suite.
- **`MigrantCaravanTest`:** a band seeded at a known province steps to a listed neighbour;
  `moveTo` a non-neighbour throws; a *k*-hop `path` takes *k* days; the Withacen/Hopespeak
  provinces are asserted adjacent (reused by the Phase-B testbed).
- **`CaravanRefoundIntoProvinceTest`:** a band wanders to a settleable province and
  settles; the re-founded colony's `getProvince()` == the arrival province and its plots
  cap reflects that province; money/food conserved across dissolve→wander→re-found;
  determinism (same seed → identical visited-province sequence).

> **Note (consolidation):** the Phase-A plan originally proposed the test names
> `CaravanMovementTest` and `MigrantCaravanSettleTest`; what actually landed is
> `MigrantCaravanTest` and `CaravanRefoundIntoProvinceTest`. This section uses the real
> names.

### Deferred out of Phase A

Deterministic **mid-run re-founding under `SessionRunner`** (adding a colony thread to a
live `Phaser` run) is the genuinely hard integration and was not needed to demonstrate
Phase A (`CaravanEconomy` is sequential). Bands created mid-run register off-barrier,
which has no observable effect today; the concurrent thread-join is left for Phase B
integration.

---

## Phase B — `TradeCaravan`: settlement-sponsored, market-coupled trade (proposed)

### Goal

Couple two settlements' economies through trade: a sponsor funds a `TradeCaravan` that
**buys a consumer good in its home market** (a real buy offer that *raises* the home
price), carries it one hop/day to a neighbouring settlement, **sells it there** (a real
sell offer that *lowers* the destination price), and **remits the profit to the sponsor**
(treasury → venture → treasury, exactly as firm dividends flow to a noble). Because both
legs clear through the actual markets, arbitrage moves the two prices toward parity — the
first genuine inter-settlement economic link. A two-settlement pair across the adjacent
**Withacen/Hopespeak** provinces is the testbed: a venture running between them should
move both prices toward each other.

### Key facts the plan is built on

- **The day-barrier is a single-threaded window over every colony.** `tickBands` runs
  once, on one thread, after every colony has finished the day and before any starts the
  next; all colony threads are parked at `arriveAndAwaitAdvance`, so a band may read and
  mutate **any** colony's markets and banks with no lock. This is the enabling fact — all
  trade I/O happens here.
- **A market offer's participant is an `Agent`.** `ConsumerGoodMarket.addBuyOffer(Agent,
  Demand)` / `addSellOffer(Agent, double)` and `clear()` move money via
  `offer.buyer.getBank().withdraw(offer.buyer.getID(), …)` and deliver goods via
  `offer.buyer.getGood(good).increase(qty)`. The participant needs `getBank()` /
  `getID()` / `getGood(name)` but **does not** need to be registered in the colony's agent
  loop (`clear()` walks the offer lists only). A `Caravan` is deliberately **not** an
  `Agent`, so it cannot be a participant directly.
- **Offers posted at the barrier settle in the next day's clear.** `tickBands` runs after
  the day's `clear()`, so an offer the band posts sits in the market's offer list until
  the colony's **next** `newDay()` clears it — a one-day settlement lag the venture's
  state machine absorbs.
- **Money is conserved across colonies by the hoard, exactly as in dissolution** (see
  *Determinism & conservation* above): the hoard carries money between two colony supplies
  through `injectExternalFunds`/`payFromEquity`/`extractExternalFunds`.
- **Phase A already gives the band a position and a tick** (`provinceId` + derived
  lat/long, `moveTo(int)`/`step(path)`, abstract `tick(Rng)` driven from the barrier;
  `GameSession.getBandRng()`, `addCaravan`/`getCaravans()`, `getWorldMap()`).
- **`MigrantCaravan` is the sibling subclass** — `TradeCaravan` slots in beside it under
  the `Caravan` base with no change to the base or to `MigrantCaravan`.

### Design decisions

1. **The market participant is a `TradeAgent` the caravan owns — not the caravan itself.**
   Because a market offer needs an `Agent`, the `TradeCaravan` holds a lightweight
   `TradeAgent extends Agent` that carries the **cargo `Good`s** and presents
   `getBank()`/`getID()`/`getGood()` for one clear. It is **never registered** in any
   colony's agent loop (inert `act()`, never stepped) — it exists only to satisfy the
   offer interface and to hold a transient account at the colony being traded with. The
   caravan re-points the proxy's `(bank, id)` to whichever colony it is transacting at
   (home for the buy, destination for the sell). *Alternative considered:* generalize
   `ConsumerGoodMarket` to accept a non-`Agent` `MarketParticipant` — cleaner long-term
   but touches the calibrated clear path; deferred. **Recommend the proxy.**
2. **All venture lifecycle lives at the barrier (single-threaded), not in `Ruler.act`.**
   Launch, movement, buy/sell, and remittance all run inside `tickBands` in a **fixed
   settlement order**, so two colonies launching the same day cannot race on
   `session.addCaravan` and the caravan list order stays deterministic. The ruler is the
   *funding source and decision input* (a `tradePolicy()` query reading its treasury), but
   the band tick is the *driver*. *Rejected:* launching from the concurrent `Ruler.act` —
   `addCaravan` is synchronized (memory-safe) but the add order would depend on thread
   scheduling, breaking "same seed → identical run."
3. **The launch decision is deterministic and RNG-free for Phase B.** A sponsor launches
   on a fixed cadence (every `TRADE_REVIEW_DAYS`, e.g. 30, or `date.dayOfMonth == 1`) when
   its treasury clears a stake threshold and a profitable partner exists; the **partner
   and good are chosen by a deterministic rule** — among neighbouring settlements, the one
   with the largest positive price gap for a tradable good (ties broken by lowest
   `province_id`). No band-RNG draw in the launch. Movement reuses Phase A's `step(path)`.
   *Deferred to C:* stochastic route/partner choice on the band RNG.
4. **Phase B trades one elastic consumer good, export-only.** The venture buys at home,
   sells abroad (exports the home good where it is dearer); the good defaults to
   **Enjoyment** — elastic, and selling it *out* cannot starve anyone, unlike draining
   Necessity. A config knob can switch the good, flagging Necessity trade as
   stability-risky. **The return leg (buy the destination's cheap good, sell at home) is
   out of Phase B — deferred to Phase C** (decided 2026-07-01).
5. **Trade volume is a calibrated, capped fraction of market size.** The stake (hence
   cargo quantity) is sized as a fraction of the home market's recent volume, capped, so a
   venture nudges prices toward parity without collapsing the two markets into one or
   destabilizing a solvent colony. This is the Phase B calibration risk (below).

### Work breakdown

- **B1 — `TradeAgent`: the market-participant proxy** (new `agent/TradeAgent.java`). A
  minimal `Agent` holding the cargo as `Good` instances, returning a `(bank, id)` set per
  trading leg, with an inert `act()` (never called — not added to any colony).
  `getGood(name)` returns the matching cargo good. **Checkpoint:** a unit test posts a
  `TradeAgent` buy offer into a throwaway `ConsumerGoodMarket`, clears it, and asserts the
  proxy's account was debited and its cargo good increased.
- **B2 — `TradeCaravan extends Caravan`** (new `agent/TradeCaravan.java`). Fields:
  `sponsor` (the home `Ruler`/owner), `homeProvinceId`, `destProvinceId`, the tradable
  good name, the cached `route`, a `TradeAgent proxy`, and a `Phase` state machine
  `BUYING → TRAVELING_OUT → SELLING → TRAVELING_HOME → REMIT → DONE`. Built at the home
  province (on-graph) with the stake already withdrawn into the band's `hoard`.
  `tick(Rng)` implements the state machine (B3). `DONE` bands are reaped by `SessionRunner`
  (B5).
- **B3 — the trade state machine** (`TradeCaravan.java`; small read accessors on
  `Settlement` if missing). Each `tick` (at the barrier) advances one step; money moves
  only through the conserving primitives:
  - **BUYING** (home): fund the proxy from the hoard —
    `homeCopper.injectExternalFunds(stake)` then `payFromEquity(proxyId, stake)` — point
    the proxy at the home copper bank, `homeMkt.addBuyOffer(proxy, price -> stake/price)`,
    advance to **AWAIT_BUY**. Next tick (after the home colony's clear): reclaim unspent
    checking into the hoard (`extractExternalFunds`), record the cargo quantity, compute
    `worldMap.path(home, dest)`, advance to **TRAVELING_OUT**.
  - **TRAVELING_OUT:** `step(route)` one hop/day until `provinceId == destProvinceId`,
    then **SELLING**.
  - **SELLING** (destination): point the proxy at the destination copper bank (a fresh
    per-colony id), `destMkt.addSellOffer(proxy, cargoQty)`, advance to **AWAIT_SELL**.
    Next tick: pull the proceeds credited at the destination into the hoard
    (`extractExternalFunds(proxyId, proceeds)`), advance to **TRAVELING_HOME**.
  - **TRAVELING_HOME:** `step(reverse route)` until home, then **REMIT**.
  - **REMIT:** deposit the hoard back into the sponsor's treasury (the gold bank), the
    conserving inverse of the launch withdrawal — `goldBank.injectExternalFunds(hoard)` +
    `payFromEquity(sponsorId, hoard)` — so stake + profit lands in the treasury. Advance
    to **DONE**.
  - The `AWAIT_*` states encode the one-day settlement lag (post at tick N, colony clears
    at day N+1, reclaim at tick N+1); no RNG is drawn. A test asserts session-wide money
    conservation end to end (B6).
- **B4 — sponsor launch (deterministic, at the barrier)** (`Ruler.java` `tradePolicy`/
  `shouldSponsorTrade` + stake withdrawal; `SessionRunner.tickBands` launch loop; maybe a
  `TradeConfig` record). In `tickBands`, **before** moving existing bands, walk the
  session's settlements in a **fixed order** (by colony index). For each living, solvent
  sponsor whose cadence is due and that has no venture in flight: query
  `ruler.chooseTradePartner(worldMap, session)` (the neighbouring settlement with the
  largest positive price gap); if the expected spread clears a threshold, **withdraw the
  stake** (`goldBank.extractExternalFunds(rulerId, stake)` → hoard) and
  `session.addCaravan(it)`. The stake is sized off recent home volume, capped.
  `chooseTradePartner` needs a **province → settlement** lookup — add
  `GameSession.settlementAt(int provinceId)` if absent. Single-threaded, order-fixed, no
  RNG.
- **B5 — drive trade ticks + reap finished ventures** (`SessionRunner.java`). `tickBands`
  already iterates `getCaravans()` and calls `band.tick(rng)`; `TradeCaravan.tick` rides
  it unchanged. Add the **launch pass** (B4) and a **reap pass** removing `DONE`
  `TradeCaravan`s (needs `session.removeCaravan`/filter — `MigrantCaravan`s settling have
  the same need). Keep it all single-threaded in `onAdvance`.
- **B6 — a two-settlement trade testbed** (new `simulation/TradeEconomy.java`, à la the
  removed `HanseaticEconomy` but kept for trade; new `TradeCaravanTest`). Two adjacent
  colonies (the Withacen/Hopespeak pair, asserted adjacent by `MigrantCaravanTest`) with a
  deliberate **standing price gap** in the tradable good (e.g. seed one colony's Enjoyment
  sector smaller so its price runs higher), a sponsor on the low-price side, trade policy
  enabled. Run a window and assert: a venture completes a full cycle (`DONE`); the **price
  gap narrows** vs. a no-trade control; the sponsor's treasury **nets a profit** (or at
  least the stake returns); **money is conserved session-wide** (Σ `bank.getTotalMoney()`
  over both colonies + Σ live band hoards is invariant across the cycle, within float
  tolerance); same seed → identical venture province sequence and CSVs.

### Test plan

- **Keep green:** the whole Phase A suite (`MigrantCaravanTest`,
  `CaravanRefoundIntoProvinceTest`, `CaravanDissolutionTest`, `CaravanRefoundTest`,
  `TwinSettlementEconomyTest`) and the smoke suite — band-free runs draw nothing new and
  stay byte-identical (assert with a CSV-checksum diff of `HomogeneousEconomy`).
- **New `TradeAgentTest`** (B1): proxy buy/sell against a throwaway market
  debits/credits its account and moves cargo.
- **New `TradeCaravanTest`** (B6): full cycle completes; price gap narrows; profit
  returns; session money conserved; deterministic province sequence.
- **Calibration smoke:** a two-settlement run with trade on stays solvent (trade does not
  destabilize an otherwise-healthy colony) — the binding Phase B risk.

### Risks / things to confirm before coding

1. **Trade-volume calibration is the real risk.** Too large a stake couples the two
   markets into one (or starves/floods a colony); too small is cosmetic. Needs a
   stake-vs-market-size sweep; trade must not destabilize a solvent colony. Start
   conservative (a small capped fraction of recent volume) and tune.
2. **The one-day settlement lag** must be modelled explicitly (the `AWAIT_*` states), or
   the caravan reads stale cargo/proceeds. Confirm no path reads the proxy good before the
   colony has cleared.
3. **Per-colony id space for the proxy.** Buying (home) and selling (destination) happen
   at colonies with **independent id/account spaces**; the proxy must take a fresh id from
   the colony it transacts at (`colony.nextAgentID()`) per leg, and its transient account
   must be drained back to the hoard so no money is stranded in a foreign colony.
4. **Reaping finished bands.** The session caravan list grows with each venture; confirm a
   removal path exists (`MigrantCaravan` settling has the same need) so the list does not
   leak `DONE` bands.
5. **Necessity trade is stability-risky.** Default to Enjoyment; only enable Necessity
   trade behind a flag once calibrated, since exporting food can starve the source colony.
6. **Mid-run launch under the concurrent runner.** Phase A deferred *automatic concurrent
   re-founding*; Phase B's launch/trade all happen at the barrier (single-threaded), so it
   sidesteps that — but confirm `tickBands` reading a colony's markets/treasury at the
   barrier never races a colony thread (it cannot: all are parked at
   `arriveAndAwaitAdvance`).

---

## Phase C — richness (later)

Real travel cost/time (terrain, distance weighting, `province_relations` typed
trade/border edges), naval/`SEA` routing (coastal `isCoastal` provinces, sea provinces as
routable water), independent merchant households owning caravans for their own account,
the `HOUSEHOLD → CARAVAN` gather and raid/war bands, a dedicated merchant noble as
sponsor, the trade **return leg** and stochastic / price-gap-weighted route and partner
choice.

---

## Accepted limitations (out of scope for the current cut)

1. **One-neighbour-per-day movement is a placeholder.** Real travel cost/time (terrain,
   distance weighting, `province_relations` typed edges) is deferred; every edge is one
   day.
2. **`SEA`/`LAKE` routing is land-only first.** Bands route over `LAND` adjacency; naval
   legs come later.
3. **Trade is settlement-sponsored only.** An independent merchant household is a later
   extension; the first cut funds ventures from a settlement's treasury.
4. **No `HOUSEHOLD → CARAVAN` gather and no raid/war band.** This note covers the settler
   and trade subclasses; a warband that gathers a following is reserved.
5. **Trade calibration is unproven.** The coupling's volume/price sensitivity is a tuning
   problem that may need the firm/market parameters revisited.

---

## Open questions deferred to later

- **Movement rate & travel cost** — one hop/day is a placeholder; what sets travel time
  and whether edges carry weights (`province_relations`).
- **Trade volume calibration** — how large a venture is, relative to market size, so
  coupling is meaningful but not destabilizing.
- **Who sponsors trade** — the ruler by default; whether a dedicated merchant noble (and
  later an independent merchant household) is the better actor.
- **Route/destination choice** — how a sponsor picks a destination (biggest price gap?
  nearest neighbour? a known partner?), kept deterministic.
- **Settle-vs-keep-wandering** for the migrant band — the readiness signal and site
  scoring, and how they compose with determinism (shared with `docs/caravan.md`).

> **Resolved (2026-07-01):** the **trade return leg** — scoped inconsistently by the two
> source documents (the design overview treated it as part of the venture; the Phase-B
> plan called it "a stretch within B or deferred to C") — is **deferred to Phase C**.
> Phase B is export-only, one good, one direction.
