# Implementation plan: caravan trade Phase B — `TradeCaravan` (settlement-sponsored, market-coupled)

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-28
**Implements:** `docs/caravan-trade.md` Phase B (the `TradeCaravan` subclass: a sponsor
funds a venture, buys at the origin market, travels a `WorldMap.path` to a destination
settlement, sells into its market — arbitrage flattening the price gap — and returns the
profit). Phase C (richness) stays out of scope.
**Depends on:** Phase A (the `Caravan` base + province-anchored movement + the
`SessionRunner` band tick — `docs/caravan-trade-phase-a-plan.md`, ✅ implemented); the
consumer markets (`ConsumerGoodMarket`, its `Demand`-based `clear()`); the bank
money-conservation primitives (`injectExternalFunds`/`payFromEquity`/`extractExternalFunds`);
and the ruler/treasury machinery a sponsored venture draws on.

## Goal

Couple two settlements' economies through trade: a sponsor funds a `TradeCaravan` that
**buys a consumer good cheap in its home market** (a real buy offer that *raises* the home
price), carries it one hop/day to a neighbouring settlement, **sells it there** (a real
sell offer that *lowers* the destination price), and **remits the profit to the sponsor**.
Because both legs clear through the actual markets, arbitrage moves the two prices toward
parity — the first genuine inter-settlement economic link. The `HanseaticEconomy` pair
(adjacent Withacen/Hopespeak) is the testbed: a venture running between them should move
both prices toward each other.

## Key facts the plan is built on

- **The day-barrier is a single-threaded window over every colony.** `SessionRunner`'s
  `Phaser.onAdvance` runs `tickBands(session, …)` once, on one thread, *after* every
  colony has finished the day (`act` → `clear` → `print`) and *before* any starts the
  next (`SessionRunner.java:64`, `:104`). While it runs, all colony threads are parked at
  `arriveAndAwaitAdvance`, so a band may read and mutate **any** colony's markets and
  banks with no lock. This is the enabling fact for Phase B — all trade I/O happens here.
- **A market offer's participant is an `Agent`.** `ConsumerGoodMarket.addBuyOffer(Agent,
  Demand)` / `addSellOffer(Agent, double)` and `clear()` move money via
  `offer.buyer.getBank().withdraw(offer.buyer.getID(), …)` and deliver goods via
  `offer.buyer.getGood(good).increase(qty)` (`ConsumerGoodMarket.java:122`, `:212`). The
  offer participant therefore needs `getBank()` / `getID()` / `getGood(name)` — but it
  does **not** need to be registered in the colony's agent loop; `clear()` just walks the
  offer lists. A `Caravan` is deliberately **not** an `Agent`, so it cannot be a
  participant directly.
- **Offers posted at the barrier settle in the next day's clear.** `tickBands` runs after
  the day's `clear()`, so an offer the band posts there sits in the market's `buyOffers`/
  `sellOffers` list until the colony's **next** `newDay()` clears it. A one-day settlement
  lag, which the venture's state machine absorbs naturally.
- **Money is conserved across colonies by the hoard, exactly as in dissolution.** Each
  colony has its own money supply. `Bank.injectExternalFunds(amt)` adds outside money to a
  colony (into equity), `payFromEquity(id, amt)` moves equity into an account,
  `extractExternalFunds(id, amt)` destroys money out of a colony (`Bank.java:200`, `:220`,
  `:246`). A band's `hoard` is money held *outside* any bank — the same session-level
  carrier `MigrantCaravan.dissolve` uses (`Bank.drainAllMoney` → hoard). Trade moves money
  between two colony supplies *through* the hoard: conserved session-wide, while each
  colony's local supply legitimately rises (goods sold in) or falls (goods bought out).
- **Phase A already gives the band a position and a tick.** `Caravan` carries
  `provinceId` + derived lat/long, `moveTo(int)`/`step(path)` validated against
  `WorldMap`, and an abstract `tick(Rng)` driven from the barrier. `GameSession` has
  `getBandRng()`, `addCaravan`/`getCaravans()`, and `getWorldMap()`/`getSlotTable()`.
- **`MigrantCaravan` is the sibling subclass.** `TradeCaravan` slots in beside it under
  the `Caravan` base with no change to the base or to `MigrantCaravan`.

## Design decisions (resolve these first)

1. **The market participant is a `TradeAgent` the caravan owns — not the caravan itself.**
   Because a market offer needs an `Agent`, the `TradeCaravan` holds a lightweight
   `TradeAgent extends Agent` that carries the **cargo `Good`s** and presents
   `getBank()`/`getID()`/`getGood()` for one clear. It is **never registered** in any
   colony's agent loop (it has no `act()` behaviour and is never stepped) — it exists
   only to satisfy the offer interface and to hold a transient account at the colony being
   traded with. The caravan re-points the proxy's `(bank, id)` to whichever colony it is
   transacting at on a given leg (home for the buy, destination for the sell). *Alternative
   considered:* generalize `ConsumerGoodMarket` to accept a non-`Agent` counterparty
   (a `MarketParticipant` interface) — cleaner long-term but touches the calibrated clear
   path; deferred. **Recommend the proxy.**

2. **All venture lifecycle lives at the barrier (single-threaded), not in `Ruler.act`.**
   Launch, movement, buy/sell, and remittance all run inside `tickBands` in a **fixed
   settlement order**, so two colonies launching the same day cannot race on
   `session.addCaravan` and the caravan list order stays deterministic. The ruler is the
   *funding source and decision input* (a `Ruler.tradePolicy()` query reading its
   treasury), but the band tick is the *driver*. This keeps every caravan mutation
   single-threaded and reproducible. *Rejected:* launching from the concurrent
   `Ruler.act` — `addCaravan` is synchronized so it is memory-safe, but the order two
   colonies add bands in would depend on thread scheduling, breaking "same seed →
   identical run."

3. **The launch decision is deterministic and RNG-free for Phase B.** A sponsor launches a
   venture on a fixed cadence (every `TRADE_REVIEW_DAYS`, e.g. 30) when its treasury
   clears a stake threshold and a profitable partner exists; the **partner and traded good
   are chosen by a deterministic rule** — among neighbouring settlements, the one with the
   largest positive price gap for a tradable good (ties broken by lowest `province_id`).
   No band-RNG draw in the launch, so determinism does not hinge on cross-thread RNG
   ordering. Movement reuses Phase A's `step(path)` (no RNG needed for a known route).
   *Deferred to C:* stochastic route/partner choice on the band RNG.

4. **Phase B trades one elastic consumer good, export-only.** The venture **buys at home,
   sells abroad** (exports the home good where it is dearer); the optional **return leg**
   (buy the destination's cheap good, sell at home) is a stretch within B or deferred to C.
   The traded good defaults to **Enjoyment** — elastic, and selling it *out* of a colony
   cannot starve anyone, unlike draining Necessity. A config knob can switch the good; the
   plan flags Necessity trade as stability-risky.

5. **Trade volume is a calibrated, capped fraction of market size.** The stake (hence cargo
   quantity) is sized as a fraction of the home market's recent volume, capped, so a
   venture nudges prices toward parity without collapsing the two markets into one or
   destabilizing a solvent colony. This is the Phase B calibration risk (below) and gets
   the same sweep treatment as the rest-day/daylight constants.

## Work breakdown

### B1 — `TradeAgent`: the market-participant proxy
**Files:** new `agent/TradeAgent.java`.

- A minimal `Agent` (or the smallest existing base that gives `getID`/`getBank`/`getGood`)
  that holds the cargo as `Good` instances (an `Enjoyment`/`Necessity` it accumulates),
  returns a `(bank, id)` set per trading leg, and has an inert `act()` (never called — it
  is not added to any colony). `getGood(name)` returns the matching cargo good.
- It is **not** added via `Settlement.addAgent`; it only ever appears as an offer's
  `buyer`/`seller`. Confirm `clear()` never assumes the participant is in `colony.getAgents()`
  (it does not — it walks the offer lists only).
- **Checkpoint:** compiles; a unit test posts a `TradeAgent` buy offer into a throwaway
  `ConsumerGoodMarket`, clears it, and asserts the proxy's account was debited and its
  cargo good increased.

### B2 — `TradeCaravan extends Caravan`
**Files:** new `agent/TradeCaravan.java`.

- Fields: `sponsor` (the home `Ruler`/owner the profit returns to), `homeProvinceId`,
  `destProvinceId`, the tradable good name, the `route` (cached `WorldMap.path`), a
  `TradeAgent proxy`, and a `Phase` state machine:
  `BUYING → TRAVELING_OUT → SELLING → TRAVELING_HOME → REMIT → DONE`.
- Constructor: built at the home province (on-graph), with the stake already withdrawn
  from the sponsor's treasury into the band's `hoard` (see B4).
- `tick(Rng)` implements the state machine (B3). `DONE` bands are reaped by `SessionRunner`
  (B5).
- **Checkpoint:** compiles; `getCaravans()` can hold a `TradeCaravan` beside a
  `MigrantCaravan` (both are `Caravan`).

### B3 — the trade state machine (buy → travel → sell → travel → remit)
**Files:** `TradeCaravan.java`; small read accessors on `Settlement` if missing.

Each `tick` (at the barrier, all colonies paused) advances one step. Money moves only
through the conserving primitives so session-wide money is invariant:

- **BUYING** (at home): fund the proxy from the hoard —
  `homeCopper.injectExternalFunds(stake)` then `payFromEquity(proxyId, stake)` — point the
  proxy at the home copper bank, and `homeMkt.addBuyOffer(proxy, demand)` with a
  `Demand` that spends the whole stake at the going price (`price -> stake/price`). Advance
  to **AWAIT_BUY**. Next tick (after the home colony's clear settled it): reclaim any
  unspent checking into the hoard (`extractExternalFunds`), record the cargo quantity now
  in the proxy good, compute the route `worldMap.path(home, dest)`, advance to
  **TRAVELING_OUT**.
- **TRAVELING_OUT:** `step(route)` one hop/day until `provinceId == destProvinceId`, then
  **SELLING**.
- **SELLING** (at destination): point the proxy at the destination copper bank (a fresh
  per-colony id), `destMkt.addSellOffer(proxy, cargoQty)`. Advance to **AWAIT_SELL**. Next
  tick: the proceeds credited to the proxy at the destination are pulled into the hoard
  (`extractExternalFunds(proxyId, proceeds)`); advance to **TRAVELING_HOME**.
- **TRAVELING_HOME:** `step(reverse route)` until home, then **REMIT**.
- **REMIT:** deposit the hoard back into the sponsor's treasury (the gold bank): the
  conserving inverse of the launch withdrawal — `goldBank.injectExternalFunds(hoard)` +
  `payFromEquity(sponsorId, hoard)` (or a direct credit) — so stake + profit lands in the
  treasury. Advance to **DONE**.
- **Determinism & timing:** the buy/sell *post* and the *reclaim* are on consecutive ticks
  (post at tick N, the colony clears at its day N+1, reclaim at tick N+1) — the
  AWAIT_\* states encode that one-day lag explicitly. No RNG is drawn.
- **Conservation accounting:** each colony's money supply rises/falls legitimately; the
  band hoard absorbs the difference; the session total is unchanged. A test asserts this
  end to end (B6).

### B4 — sponsor launch (deterministic, at the barrier)
**Files:** `Ruler.java` (a `tradePolicy`/`shouldSponsorTrade` query + stake withdrawal),
`SessionRunner.tickBands` (the launch loop), maybe a `TradeConfig` record.

- In `tickBands`, **before** moving existing bands, walk the session's settlements in a
  **fixed order** (e.g. by colony index). For each living, solvent sponsor whose cadence
  is due (`date.dayOfMonth == 1`, say) and that has no venture already in flight: query
  `ruler.chooseTradePartner(worldMap, session)` — the neighbouring settlement with the
  largest positive price gap for the tradable good — and if the expected spread clears a
  threshold, **withdraw the stake** from the treasury into a new `TradeCaravan`'s hoard
  (`goldBank.extractExternalFunds(rulerId, stake)` — the money leaves the treasury and
  becomes the carried hoard) and `session.addCaravan(it)`.
- The stake is sized off the home market's recent volume (decision 5), capped.
- `chooseTradePartner` needs a session **province → settlement** lookup; add
  `GameSession.settlementAt(int provinceId)` (or iterate the session's colonies) if absent.
- **Determinism:** the launch loop is single-threaded and order-fixed; no RNG. A session
  with no eligible sponsor adds nothing (band-free runs stay byte-identical).

### B5 — drive trade ticks + reap finished ventures from `SessionRunner`
**Files:** `SessionRunner.java`.

- `tickBands` already iterates `session.getCaravans()` and calls `band.tick(rng)`
  (`SessionRunner.java:104`). `TradeCaravan.tick` rides this unchanged.
- Add the **launch pass** (B4) and a **reap pass**: remove `DONE` `TradeCaravan`s from the
  session list (needs a `session.removeCaravan`/filter — confirm one exists or add it;
  `MigrantCaravan`s already need reaping when they settle, so this may already be wanted).
- Keep it all single-threaded in `onAdvance`.

### B6 — a two-settlement trade testbed
**Files:** new `simulation/TradeEconomy.java` (or extend `HanseaticEconomy`),
new `TradeCaravanTest`.

- Two adjacent colonies (the Withacen/Hopespeak pair — already asserted adjacent by
  `HanseaticEconomyTest`) with a deliberate **standing price gap** in the tradable good
  (e.g. seed one colony's Enjoyment sector smaller so its price runs higher), a sponsor on
  the low-price side, and the trade policy enabled.
- Run a window and assert: a venture completes a full cycle (`DONE`), the **price gap
  narrows** over runs vs. a no-trade control, the sponsor's treasury **nets a profit** (or
  at least the stake returns), and **money is conserved session-wide**
  (Σ `bank.getTotalMoney()` over both colonies + Σ live band hoards is invariant across
  the cycle, within float tolerance).
- **Determinism:** same seed → identical venture province sequence and identical CSVs.

## Test plan

- **Keep green:** the whole Phase A suite (`CaravanMovementTest`, `MigrantCaravanSettleTest`,
  `CaravanDissolutionTest`, `CaravanRefoundTest`, `HanseaticEconomyTest`), and the smoke
  suite — band-free runs draw nothing new and stay byte-identical (assert with a
  CSV-checksum diff of `HomogeneousEconomy`).
- **New `TradeAgentTest`** (B1): proxy buy/sell against a throwaway market debits/credits
  its account and moves cargo.
- **New `TradeCaravanTest`** (B6): full cycle completes; price gap narrows; profit returns;
  session money conserved; deterministic province sequence.
- **Calibration smoke:** a Hanseatic-scale run with trade on stays solvent (trade does not
  destabilize an otherwise-healthy colony) — the binding Phase B risk.

## Risks / things to confirm before coding

1. **Trade-volume calibration is the real risk.** Too large a stake couples the two markets
   into one (or starves/floods a colony); too small is cosmetic. Needs a sweep of
   stake-vs-market-size, and trade must not destabilize a solvent colony (per
   `docs/caravan-trade.md`'s accepted limitations). Start conservative (a small capped
   fraction of recent volume) and tune.
2. **The one-day settlement lag** (offer posted at the barrier, cleared next colony-day)
   must be modelled explicitly in the state machine (the AWAIT_\* states), or the caravan
   will read stale cargo/proceeds. Confirm no path reads the proxy good before the colony
   has cleared.
3. **Per-colony id space for the proxy.** Buying (home) and selling (destination) happen at
   colonies with **independent id/account spaces**; the proxy must take a fresh id from the
   colony it is transacting at (`colony.nextAgentID()`) for each leg, and its transient
   account must be drained back to the hoard so no money is stranded in a foreign colony.
4. **Reaping finished bands.** The session caravan list grows with each venture; confirm a
   removal path exists (`MigrantCaravan` settling has the same need) so the list does not
   leak `DONE` bands.
5. **Necessity trade is stability-risky.** Default to Enjoyment; only enable Necessity
   trade behind a flag once calibrated, since exporting food can starve the source colony.
6. **Mid-run launch under the concurrent runner.** Phase A deferred *automatic concurrent
   re-founding*; Phase B's launch/trade all happen at the barrier (single-threaded), so it
   sidesteps that — but confirm `tickBands` reading a colony's markets/treasury at the
   barrier never races a colony thread (it cannot: all are parked at `arriveAndAwaitAdvance`).

## Open questions deferred to later (Phase C)

- **Return leg** (buy the destination's cheap good, sell home) — a stretch within B or C.
- **Stochastic / multi-hop route and partner choice** on the band RNG; price-gap-weighted
  destination selection beyond nearest neighbour.
- **Independent merchant households** owning caravans for their own account (Phase B is
  treasury-sponsored only).
- **Real travel cost/time and naval routing** — one hop/day and land-only adjacency stay
  placeholders.
- **A dedicated merchant noble** as sponsor instead of the ruler.
