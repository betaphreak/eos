# Design note: CARAVAN (the mobile rung) and collapse-as-migration

**Status:** partially implemented — Phase 0 (the `Retinue` wandering mode) and the
scaffold `Caravan` are in; the Phase 4 `GameSession` re-founding seam has landed (see
the phased plan below). The cycle now runs end to end: a ruler-bearing colony whose
workforce drains past a floor **dissolves into a Caravan** (Phase 2/3 — money→hoard,
households→following, the band departs), and a band **re-founds** a fresh colony seeded
from its leader, people and hoard (Phase 4), the same bloodline ruling again. Still
design-only: the gradual `Ruler → holder` lesser demotion (needs the standalone `holder`
type), the dwell-able `HOLDING` founding phase, and the literal settlement teardown
(a disbanded colony is marked gone — and its households' dynasty surnames return to the
pool — but its stale agent objects are not yet removed). A planned refinement —
**band-as-data**, eliminating the last settlements formed at compile time — is set out in
*Implementation plan: eliminating compile-time-formed settlements* below.
**Date:** 2026-06-18
**Depends on:** the rank ladder (`agent.com.civstudio.Rank`, `RankLadder`, `Estate`,
`RankFactory` — see `docs/rank-ladder.md`), the founding ascent in
`docs/village-founding.md` (CARAVAN is the band that note settles), the peasant pool
(`docs/peasant-pool.md`), `CITY`/`LEAGUE` (`docs/city-and-league.md`), and
`GameSession`'s multi-colony support.
**Relationship to `village-founding.md`:** that note designs the *upward* journey
(a Caravan settling: `CARAVAN → HOLDING → VILLAGE`); this note defines the **Caravan
entity itself** and the *downward* journey (a settlement failing back into a
Caravan). Together they close the ladder into a cycle.

## Motivation — closing the ladder into a cycle

The rank ladder is a **settle ⇄ unsettle axis**, and `CARAVAN` (level 1, plural) is
the mobile rung it pivots on. Promotion up the ladder is *settling* (a band puts down
roots and accretes structure); demotion down is *unsettling* — and the model's
existing **collapse** (a colony that can no longer feed its labor force) *is* the
down-ladder journey.

Today a collapse is **terminal**: the colony dies and its people simply vanish from
the simulation. Realizing `CARAVAN` reframes it: a failing settlement **sheds rank**,
and its survivors **take to the road as a Caravan** — a wandering band that may
**re-found elsewhere** (climb the ladder again). Collapse becomes **migration**, and
the one-way climb becomes a cycle of rise, fall, and resettlement. That cycle is the
reason to build CARAVAN; everything below serves it.

The seed insight: **the settled colony's labour reserve — the `Retinue` — is already
a Caravan's following; it just hasn't left yet.** While settled it is bound to a
`Settlement` and fed from the food market by a `Ruler`; detach it and hand it to a
landless leader and it *is* the wandering band. (See *Retinue and Caravan* below.)

## The model (decided behaviour)

### Retinue and Caravan — the connection

The `Retinue` (the renamed labour pool — see `docs/peasant-pool.md`) and the Caravan
are **not the same thing**, and keeping them distinct is what lets the ladder close
into a cycle:

- A **`Retinue`** is a *following* — a band of unranked peasants plus its larder. It
  is an **asset owned by a patron household** (the same kind of thing as a `Noble`'s
  `Property` list), never a ranked agent in its own right. It **persists across the
  whole lifecycle**.
- A **Caravan** is a *band on the move* — the **`CARAVAN`-rank entity** itself: a
  **leader** (the band's *Captain*, the title `Rank.CARAVAN` carries), the Retinue it
  commands, a carried hoard and a position, with **no settlement**. It is a colony-less
  **aggregate**, *not* a household type — the leader is a `Member` the Caravan holds,
  not a distinct `Captain` class (see *CARAVAN — the band, concretely*).

So **a Caravan contains a Retinue, but a Retinue is not always a Caravan.** The very
same Retinue object is, at different points in the cycle:

- the **settlement's labour reserve** while its patron is a settled `Ruler`/`Mayor` —
  fed from the food market, promoted out of into laborer households (today's behaviour);
- the **wandering band's following** while its patron is the mobile `Caravan` — fed from
  its carried larder, marketless.

The organizing principle: **the band — `{patron + Retinue + larder + treasury}` — is
the persistent core; a settlement is infrastructure wrapped around it.** Settling
**wraps** the band in banks, markets, firms and slots; collapse **strips** them away.
So founding and collapse neither create nor destroy the Retinue — they only
**re-attach** it across the settle/unsettle hinge: a dying settlement hands its
Retinue to the departing band (its `Ruler` now the band's `Caravan`), and a settling
Caravan binds its Retinue to a fresh settlement. The Retinue is the thread; the rest
is scaffolding raised and torn down around it.

### CARAVAN — the band, concretely

A **Caravan** is a colony-less **aggregate** — the `CARAVAN`-rank entity, not a
household type — holding:

- a **leader** — the band's *Captain* (the title `Rank.CARAVAN` carries): the dynasty
  `Member` that led the band out and becomes the holder/`Ruler` if it re-founds. It is
  **not** a separate household class; it is a `Member` the `Caravan` ages, succeeds
  (drawing a same-dynasty heir, as a household does) and dwindles to a lone household if
  its line dies out. CARAVAN being a *plural* rank (`Rank.isPlural()` — a collective of
  households, i.e. the Retinue), an aggregate suits it better than a lone household
  class would, and matches how the plural ranks above it are collectives too;
- its **`Retinue`** — the following (the band's asset, not a ranked agent; "the pool
  becomes a Caravan" means *the Retinue's patron is now the `Caravan` and the Retinue
  has detached from any settlement*, not that the peasants gained a rank);
- a **carried hoard** — the band's money, a copper amount held **on the `Caravan`**,
  outside any bank (see *The bankless rung*);
- a **carried larder** — the `Retinue` already holds this (its `necessity` store);
  while wandering it simply consumes from it without restocking;
- a **carried tech tree** — a `ResearchSnapshot` of the abandoned colony's research
  (what it knew, the techs it had researched, and its in-progress focus/buffered
  points), restored onto the colony the band re-founds so progress is not lost (see
  `docs/tech-tree.md`);
- a **position** — deferred to the caravan-trade geography work.

So the only thing the `Retinue` itself needs to gain is **tolerance of being
marketless** (larder-only consumption, no `Bank`, the `Caravan` as patron instead of a
`Ruler`) — and it already guards those couplings defensively (a null builder market,
a null/absent ruler), so detaching it is mostly *removing bindings*, not adding
structure (`docs/village-founding.md` anticipated this).

### The settle/unsettle axis — the four symmetric transitions

| | Transition | Trigger | What moves |
| --- | --- | --- | --- |
| ↑ gather | `HOUSEHOLD → CARAVAN` | a lone household takes in the landless | gains a following |
| ↑ consolidate (**settle**) | `CARAVAN → HOLDING` | readiness: enough people + hoard + a viable spot | banks chartered, hoard deposited; pool binds to the new settlement; the leader becomes a landed holder |
| ↓ disperse (**unsettle**) | `HOLDING → CARAVAN` | workforce below the rung floor | **dissolution**: banks close, balances → hoard, food → larder, every household → following; the holder becomes the band's leader (its Captain) |
| ↓ dwindle | `CARAVAN → HOUSEHOLD` | the following empties | the leader left as a lone household, hoard only |

`CARAVAN ↔ HOLDING` is the **hinge** of the whole ladder: at/above `HOLDING`
everything is **settled** (banked, marketed, fixed in place); `CARAVAN` (and a
dispersed lone `HOUSEHOLD`) is **mobile** (bankless, marketless). This also satisfies
the singular/plural grammar: `HOUSEHOLD→CARAVAN` gathers households into a collective;
`CARAVAN→HOLDING` consolidates that collective into one landed asset — and the reverse
disperses it.

### Collapse as gradual decline (the decided shape)

A failing settlement does **not** jump straight to a Caravan; it **declines one rung
at a time over many steps**, the sovereign's dynasty leading the whole way down:

```
VILLAGE  ──(workforce < floor_V)──►  HOLDING  ──(workforce < floor_H)──►  CARAVAN  ──►  (re-found, or dwindle/die)
 Ruler                                holder                              Captain
```

- **Trigger: a workforce floor, per rung.** When the living laborer-household count
  falls below a rung's floor (each floor `> 0`, so survivors always remain to form a
  viable band), the settlement demotes **one rung**. As the workforce keeps shrinking
  (deaths outpacing the now-broken pool recruitment), it crosses successive lower
  floors and demotes again. The decline is gradual because the population is.
- **`VILLAGE → HOLDING`** is the *lesser* demotion: still a settled, banked place, but
  no longer a network of holdings — the sovereign reforms from a `Ruler` into a
  **holder** (the same founder/holder realization of `HOLDING` that
  `docs/village-founding.md` uses mid-founding — see *Architecture* for why this, not
  a vassal `Noble`).
- **`HOLDING → CARAVAN`** is the **dissolution** — the dramatic event: the settlement
  crosses the hinge from settled to mobile. **Every bank closes; the colony's
  circulating money nets into a single gold hoard on the new `Caravan`; the remaining
  food consolidates into the band's larder; every surviving household — laborers and
  nobles alike — collapses into the following; and the holder becomes the band's leader
  (its Captain).** The band departs.
- **The settlement then vanishes entirely.** No ruins are left behind; the
  `Settlement` is gone from the world, and a re-founding (when the band settles again)
  is a fresh colony anywhere — see *Architecture → re-founding*.

**Who leaves: the ruler leads, and everyone left joins.** At dissolution the former
sovereign leads the band as its **Captain**; every other surviving household
**disbands into the following** — its people become pool `Member`s, its balances fold
into the hoard, its food into the larder. This keeps the following-is-unranked
doctrine exact: at dissolution all settled households collapse back into raw
following-population, and only the leader leads. (Disbanding a household into the
following is the **inverse of recruitment** — pool ↔ household membership — and like
recruitment it is a pool operation, *not* a rank reform.) Note the descent is **not**
three uniform rank reforms: only the `Ruler → holder` step runs through the rank engine
(both ends are banked households); the final `holder → CARAVAN` step **is** the
dissolution itself — a bespoke settle/unsettle operation, *not* a `RankLadder` reform,
because what comes out the other side is a colony-less `Caravan` aggregate, not a banked
household. Forcing it through the reform engine is exactly what would manufacture a
"bankless household" special case; keeping it a dedicated operation avoids one.

### The bankless rung (the one genuinely new requirement)

Every settled reform moves money between bank tiers (copper↔silver↔gold). A Caravan has
**no `Settlement`, hence no `Bank`** — so its money is a **carried hoard**: the
colony's circulating money (all account balances plus the banks' equity), conserved
into a single amount held **on the `Caravan`**, outside the banking system. Internally
it stays a copper-denominated figure (all accounting is copper, currencies fungible at
the fixed rate); "converted to gold" is the portable-wealth framing of the same value.
The dissolution is **money-conserving** — the hoard equals what the banks held, no
haircut, and no FX toll fires (a closing bank is handing over balances, not
transacting).

Consequences:
- **Settling deposits, unsettling withdraws.** `CARAVAN → HOLDING` charters the
  settlement's banks and deposits the hoard; `HOLDING → CARAVAN` drains every account
  into the hoard and closes the banks. Both directions are the bespoke settle/unsettle
  operation, not a rank reform (see above).
- **The hoard lives on the `Caravan`; neither `Estate` nor `RankLadder.reform` is
  touched.** The new money code is the **dissolution/settle operation** that moves money
  between the colony's bank accounts and the Caravan's hoard — a plain `double` on the
  aggregate. Because the hinge is *not* routed through the rank engine, there is no
  bankless household for `reform` to tolerate and nothing to generalize in `Estate`: the
  reforms that *do* use the engine (`Ruler ↔ holder`, ennoblement/demotion) are all
  between **banked** households, exactly what it already handles. `Estate` stays as it
  is; the rest is reuse.

### While wandering — a decaying asset

A Caravan can only **consume** (the larder depletes), not produce (no firms, no
markets), so it is a **decaying asset with a clock**: it must settle or trade before
the larder runs out. That urgency is the stake that makes re-founding meaningful.
Foraging/trade-fed sustenance and real movement ride on the **caravan-trade** geography
work (the same dependency `docs/village-founding.md` notes); until that lands, a band
founds in place at a hardcoded location with no real wandering.

#### When the clock runs out — the band is deleted (2026-07-16)

The decay has an end state, and it used to leak. A band that ate its last ration is
**spent**: `following.act()` starves the unfed, and once nobody is left it can never
march, settle, forage or re-found. `Caravan.isSpent()` names that condition — it was
already computed inside `MarchingCaravan.tick` and *thrown away* — and
`GameSession.pruneSpentCaravans()` buries it. The drivers (`SessionRunner.tickBands`,
`HostedSession.tickBands`) call it once a day, after ticking the bands.

This closes a real leak: `GameSession.caravans` was **append-only** (`addCaravan` had no
counterpart anywhere), so a dead band was re-ticked every remaining day of the run and
still shipped to the browser as a live marker with `bandSize: 0`. The colony-level
counterpart, `Settlement.tickExcursions`, already pruned levies that **returned home**
(`removeIf(hasArrived)`) but had the same hole for ones that **died on the road** — a
spent levy never reaches `Phase.DONE`, so `hasArrived()` stays false; it now prunes on
`hasArrived() || isSpent()` and logs the loss.

The band's hoard and cargo die with it, exactly as they already did — a hoard is only
ever spent by a *living* band, so the money was unreachable the moment the last member
was. This deletes the corpse, not the assets. Covered by `SpentCaravanPruneTest`.

## Architecture mapping

- **The `Caravan` entity** = a colony-less **aggregate** holding a **leader** `Member`
  (the band's Captain, *not* a household class) + its **`Retinue`** (the same
  following-asset, now detached and marketless) + a carried hoard (`double`) + a
  position. The `Retinue` is **reused, not reinvented** — detaching it from the colony
  is the bulk of the work (see *Retinue and Caravan*). The Caravan is a colony-less
  aggregate, so — like the `RankLadder` and the supra-settlement ranks — it lives at
  the **`GameSession`** level, not on any `Settlement`. *(Refined below: the band holds
  its following as **data**, not a live `Retinue` object — a `Retinue` is colony-bound
  and cannot exist before a settlement does. See *Implementation plan: eliminating
  compile-time-formed settlements*.)*
- **The reforms & the two realizations of `HOLDING`.** The `HOLDING` rung has *two*
  realizations: the within-settlement **vassal `Noble`** (a rentier owning firms, the
  ennoblement target) and the standalone **holder** (a settlement-owner mid-founding
  or mid-collapse, with the founder/holder config from `docs/village-founding.md`). A
  declining `Ruler` reforms into the **holder**, not a vassal `Noble` — symmetric with
  founding, where the holder ascends to `Ruler`. So the holder is the transitional
  state of a settlement that is *a single holding, not (yet / any longer) a village*,
  in both directions of travel. This `Ruler ↔ holder` step **is** a rank-engine reform
  (both ends banked); only the `holder ↔ CARAVAN` hinge below is handled outside it.
- **The hoard, on the `Caravan`** (the bankless rung, above): the band's money is a
  plain copper `double` on the aggregate, outside any bank. The **dissolution/settle
  operation** — not `RankLadder.reform` — moves it between the colony's accounts and
  the hoard. `Estate` and the reform engine are **untouched**: the hinge isn't a rank
  reform, so no bankless household ever reaches `reform`, and `Estate` (which already
  carries money as a plain copper amount) needs no change.
- **Determinism & re-founding.** A colony-less Caravan needs a deterministic stream
  (session-level salt, as flagged in `docs/village-founding.md`); when it re-founds, the
  new colony takes the next `GameSession` colony index, so "same seed → identical run"
  holds. Because the old settlement **vanishes entirely**, there is no ruin state to
  persist — re-founding is an ordinary fresh `newSettlement`, seeded from the band's
  surviving `Member`s and hoard.
- **`RankLadder` stays single-rung and untouched.** The gradual decline is a *sequence*
  of single-rung demotions fired over time by the per-rung workforce floors — no
  multi-rung leap, and **no engine change at all**: the rank engine only ever reforms
  banked households (`Ruler ↔ holder`, ennoblement/demotion), and the `holder ↔ CARAVAN`
  hinge is the dissolution/settle operation, handled outside it.

## Lifecycle: rise, fall, and rise again

```
Caravan  ──settle──►  HOLDING  ──►  VILLAGE  ──►  CITY (permanent)  ──►  LEAGUE
   ▲                                   │
   │                                   │ workforce decline (per-rung floors)
   └────────── dissolution ◄───────────┘
        (banks close, money→hoard, food→larder,
         households→following, settlement vanishes)
```

A band settles and climbs; if it never reaches the **permanence** of `CITY`
(`docs/city-and-league.md` — the open-economy inflow that stops the collapse), its
workforce can drain, and it declines back to a Caravan and migrates. A `CITY` is the
escape velocity that breaks the cycle; below it, settlements rise and fall.

## Worked example

A `VILLAGE` (e.g. a `HomogeneousEconomy`-style colony, which today simply collapses)
drains its peasant reserve. Its workforce falls below `floor_V`: the `Ruler` reforms
into a holder, the settlement now a single `HOLDING`. Deaths continue; the workforce
falls below `floor_H`: **dissolution** — the banks close into a gold hoard on a new
`Caravan`, the food piles into its larder, the surviving laborer and noble households
disband into the following, the holder becomes the band's leader (its Captain), and the
settlement vanishes. The Caravan wanders (in place, for now) on its larder until it
either re-founds a fresh colony (climbing `CARAVAN → HOLDING → VILLAGE` again) or its
larder runs out and it dwindles (`CARAVAN → HOUSEHOLD`, or the leader's dynasty dies
out).

## Accepted limitations (out of scope for this cut)

1. **Movement/foraging is deferred to caravan trade.** A band founds in place, on a
   depleting larder, with no real wandering or trade-fed sustenance.
2. **`HOUSEHOLD → CARAVAN` is the weakest-motivated transition** and is left
   reserved-but-realizable: the collapse/settle transitions (`HOLDING ↔ CARAVAN`,
   `CARAVAN → HOLDING`) come first; "a lone household gathers a following" needs a
   gameplay trigger that does not exist yet.
3. **Collapse reframing reworks the smoke tests.** Today `assertCollapsed` expects a
   terminal death; gradual decline means a dying settlement spawns a departing Caravan
   instead. The collapse tests and the `Settlement` lifecycle (`isDead()` now marks
   "this settlement is gone", while the *people* persist in the band) need deliberate
   rework — the same class of interaction as the `CITY`-permanence seam.
4. **The per-rung workforce floors are uncalibrated** placeholders, as is the
   founder/holder config governing the declining holder.

## Phased implementation plan

- **Phase 0 — `Retinue` tolerates marketless/larder-only consumption. (Implemented.)**
  The enabling refactor; byte-identical for settled pools (markets present). The
  `Retinue` composes a `Provisioning` strategy swapped on `detach()`/`settle()`, and a
  scaffold `Caravan` (a detached following + a position + the lean wandering ration)
  exists.
- **Phase 1 — *(removed — was a mis-scoped "bankless `Estate`")*.** There is no
  separate `Estate`/reform phase: `Estate` already carries money as a plain copper
  amount (not a bank reference), and the `holder ↔ CARAVAN` hinge is **not** a rank
  reform, so the rank engine never sees a bankless household and needs no change. The
  genuinely new money code is the **hoard on the `Caravan` + the dissolution/settle
  operation** that moves money between the colony's bank accounts and that hoard; it
  lands **with the Caravan entity in Phase 2**.
- **Phase 2 — the `Caravan` entity + the dissolution/settle operation.** The
  session-level `Caravan` aggregate (a **leader `Member`** — the band's Captain, not a
  household class — + the detached `Retinue` + a hoard `double` + a position), and the
  bespoke **settle/unsettle operation** that crosses the hinge: on unsettle it nets the
  colony's money into the hoard, folds every household into the following, and closes
  the banks; on settle it reverses that. There is **no `CARAVAN` `RankFactory`** — the
  hinge is handled outside the rank engine precisely to avoid a bankless household.
  (The within-colony `Ruler ↔ holder` reform, which *does* use the engine, is part of
  Phase 3's gradual decline.) Realizes `HOLDING ↔ CARAVAN` and `CARAVAN → HOUSEHOLD`.
  *First cut implemented (the **unsettle** direction):* the `Caravan` entity carries its
  leader + hoard, `Bank.getTotalMoney`/`drainAllMoney` net a colony's circulating money
  with conservation, and `Caravan.dissolve(Settlement)` builds the wandering band —
  draining every bank into the hoard, folding each surviving household's members into the
  following (`Retinue.absorb`) and its larder into the band's (`Retinue.stockLarder`),
  and detaching the following. Covered by `CaravanDissolutionTest` (money conserved, the
  ruler leads, every other household member joins the following). **Deferred:** the
  **settle** reverse (re-founding, with Phase 4), `CARAVAN → HOUSEHOLD` dwindle, and the
  actual settlement teardown/vanish + smoke-test rework (with the Phase 3 trigger) —
  `dissolve` mutates but does not yet *remove* the colony.
- **Phase 3 — collapse-as-decline.** The per-rung workforce-floor triggers, the
  `Ruler → holder` lesser demotion, and the `HOLDING → CARAVAN` **dissolution**
  (banks→hoard, food→larder, households→following, settlement vanishes). Reworks the
  collapse smoke tests: a colony that today collapses instead **departs as a Caravan**.
  *First cut implemented (the trigger + the departure):* `Settlement.updateLifecycle`
  now flags a ruler-bearing colony for dissolution once its living workforce falls below
  `DISSOLUTION_WORKFORCE_FLOOR` (an uncalibrated placeholder, kept `> 0` so survivors
  remain), and `Settlement.run` performs the dissolution after the final step's clearing
  (`Caravan.dissolve`), registering the band with the owning `GameSession`
  (`Settlement.getDepartedBand` exposes it). A pool-less colony (no ruler/`Retinue` to
  form a band) still dies terminally at zero laborers, as before. The closed-colony smoke
  test now asserts the colony **departs as a Caravan** (`assertDepartedAsCaravan`) rather
  than collapsing terminally. **Deferred:** the gradual `VILLAGE → HOLDING` (`Ruler →
  holder`) lesser demotion — the colony crosses straight from settled to a band at the
  floor — which awaits the standalone `holder` type (`docs/village-founding.md`); and the
  literal teardown of the colony's stale agents — `dissolve` drains the banks, folds the
  households (recycling their dynasty surnames back to the session pool, so a band can
  re-found without exhausting the name table), and the settlement is marked gone and stops
  running, but its now-stale agent objects are not removed (nothing holds a live-colony
  registry to remove it from yet).
- **Phase 4 — re-founding. (Implemented.)** The Caravan settles (`CARAVAN → HOLDING`)
  as a fresh colony (next index), reusing the founding foundry — a band that fell can
  rise again. The `GameSession` seam (`addCaravan`/`getCaravans`, and
  `newSettlement(Caravan band, …)` raising a fresh colony at the band's position on the
  next colony index, so the run stays deterministic) is joined by the foundry seeding:
  `SimulationHarness.reFoundStandardColony(band, …)` runs the standard founding sequence
  but seeded *from the band* — the leader becomes the gold-banking `Ruler` adopting its
  `Member` (an adopt-style `Ruler(Member, …)` constructor) with the carried hoard as its
  treasury (`createRulerFromLeader`), and a fresh `Retinue` adopts the band's following
  `Member`s + larder (`createRetinueFromBand`, an adopting `Retinue` constructor) — out
  of which the initial labor force is promoted as usual. So the same bloodline rules the
  colony it re-founds, on the wealth and people it carried. Covered by
  `CaravanRefoundTest` (a colony is dissolved into a band, which re-founds a viable colony
  in the same session, led by its former sovereign at the band's site). The caravan-journey
  tests (`DhenijansarToWexkeepTest`, `ParallelCaravansTest`) exercise the whole arc
  start-to-finish: bands muster, march the graph, forage/gather cargo, and settle.
  **Deferred:** the dwell-able
  `HOLDING` phase / `holder` type and the asset-distribution of
  `docs/village-founding.md` (re-founding goes straight to a seated `Ruler`).
- **Phase 5 — movement, foraging, trade** (and, later, the `HOUSEHOLD → CARAVAN`
  gather transition). **Designed in [`docs/caravan-trade.md`](caravan-trade.md):**
  now that the province graph exists (`docs/geography.md`), the `Caravan` becomes a
  province-anchored entity that moves along the neighbor graph — a `Caravan`
  superclass with a **settler** subclass (this note's migration band, now wandering
  the graph to a settleable province and re-founding *into* it) and a **trade**
  subclass (a settlement-sponsored merchant convoy that couples two settlements'
  economies through their real markets). The band's `position`/`moveTo` seam this
  note deferred is realized there. The **daily movement's logistics** — a metric,
  **daylight-bounded** march (distance = speed × effective daylight hours, correlated with
  `docs/solar.md`), the **column-length overhead** that makes big bands slow, the
  **fixed-order march enum** with per-stage timing, and the **nightly transient-plot-claim
  camp** (which becomes the founding `HOLDING` on the settle decision) — are designed in
  **[`docs/caravan-march.md`](caravan-march.md)**.

  These are **admin-flavored** caravans — the settler (a Civ4-settler analogue that
  founds settlements) and the merchant convoy. A future **military-flavored** flavor —
  an **army** / `WarBand`, a band whose following is soldiers that moves to *project
  force* rather than to settle — is the natural third subclass, plugging into the
  `Rank` enum's **military register** (`TitleMode.MILITARY`) and `CasusBelli`/`Relation`
  vocabulary (dormant in `agent/Rank.java`) and the war/conquest triggers of
  `docs/rank-ladder.md` Phase 5. The **band-as-data** refinement below serves this
  directly: once the following is data rather than a settlement-bound `Retinue`, an army
  is the *same carrier* with a different `tick`/objective that never materializes a
  colony — so the `Caravan` base is kept **flavor-agnostic** (leader, following-as-data,
  hoard, position, movement) and each flavor specializes only `tick()` and its goal.

## Caravan types (realized 2026-07-12)

The flavor split forecast above has **landed** (structurally — the three new missions are
scaffolds). Between the flavor-agnostic `Caravan` base and the concrete bands sits a new
abstract **`MarchingCaravan`**, which owns everything a band needs to *march and forage*:
the `Retinue` following + larder (the larder clock), the daylight-bounded march
(`docs/caravan-march.md`), the route walking, forage + gather, tech-gated resource
identification, and the nightly camp. The four concrete land flavors extend it and override
only their **goal seam** — `arrive()` (the C2C-style *mission* run on reaching the goal),
`journeyComplete()`, and `chooseWanderTarget()` — plus, via a `CaravanRole` enum, their
order-of-march column.

The roles mirror the **Caveman2Cosmos `UnitAI` families** (from the C2C unit XML — a unit's
`<DefaultUnitAI>` is exactly this discriminator):

| Type | `CaravanRole` | C2C `UnitAI` (representative units) | Mission | Status |
| --- | --- | --- | --- | --- |
| `SettlerCaravan` | `SETTLER` | `UNITAI_SETTLE` (`UNIT_BAND`, `UNIT_TRIBE`, `UNIT_SETTLER`) | found a colony (`MISSION_FOUND`) | **live** — the dissolution/migration band, `dissolve()` factory |
| `WorkerCaravan` | `WORKER` | `UNITAI_WORKER` (`UNIT_WORKER`) | build routes/improvements (`MISSION_BUILD` → `CIV4BuildInfos.xml`) | scaffold |
| `ExplorerCaravan` | `EXPLORER` | `UNITAI_EXPLORE` (`UNIT_SCOUT`, `UNIT_EXPLORER`) | scout the map / identify resources | scaffold |
| `MilitaryCaravan` | `MILITARY` | the combat AIs (`UNITAI_ATTACK`, `UNITAI_ATTACK_CITY`, …) | move to project force; fields the full march column | scaffold |

The three non-settler flavors **march and forage exactly like the settler** (that machinery
is all on `MarchingCaravan`), but their arrival missions are deliberate **no-op stubs** —
there is no persisted plot route/improvement state (worker), no fog-of-war/discovered-map
(explorer), and no combat/army model (military) for them to act on yet. Each stub carries a
`TODO` naming the subsystem it awaits. The C2C sea/air `UnitAI` variants are out of scope
(land-only); `UNITAI_MERCHANT` is reserved for the proposed `TradeCaravan`
(`docs/caravan-trade.md`), which carries goods rather than a following and so extends
`Caravan` directly, beside `MarchingCaravan`.

## Implementation plan: eliminating compile-time-formed settlements

The cycle above lets a colony be **born at runtime** (a band settles — Phase 4,
implemented). But most colonies are still **formed at compile time**: a fixed bootstrap
at scenario setup, not a band settling. Two concrete instances remain:

1. **Every standard scenario** — `HomogeneousEconomy`, `TwinSettlementEconomy`,
   `ElvenEconomy`, `HarimariEconomy`, `OpenColonyEconomy`, `SurvivalExperiment` (and
   the MCP `run_scenario`/`sweep` tools via `CalibrationRun`) — form their colony via
   `SimulationHarness.foundStandardColony(…)`,
   no band.
2. **The caravan-journey tests' throwaway `muster` colony** (e.g. `ParallelCaravansTest`,
   `DhenijansarToWexkeepTest`) — a real `Settlement` conjured only to host the bands'
   `Retinue`s before they wander; it never runs, but it is a settlement created at setup
   purely as a data holder.

The end state: **no `Settlement` comes into being except by a band settling.** A scenario
that "starts established" musters a band and settles it *in place at t=0* (a zero-length
wander) rather than bootstrapping a colony directly.

### Root cause — and a refinement of the Caravan entity

This note's model carries the settled colony's **live `Retinue` object** out into the
band ("detach it and hand it to a landless leader"). But `Retinue extends Agent`,
`Agent.colony` is **`final`**, and `Retinue`'s constructor wires an account + markets + a
columnar skill store against a `Settlement` — so **a band cannot exist before a colony
does.** That is exactly why the `muster` colony is needed, and why the standard sims
cannot be band-first.

The fix — **band-as-data** — supersedes "detach the live Retinue": while wandering, the
`Caravan` holds its following as **data** (a `List<Member>` + a larder amount; the leader,
hoard and position already on the aggregate), and the `Retinue` `Agent` is **materialized
only on settle**, seeded from that data via the existing adopt constructor
`Retinue(List<Member>, double, Bank, Settlement)`. The `Member`s and larder are the
persistent thread at the **data** level; a fresh `Retinue` is built per settled colony.
So "the Retinue is the thread" (above) holds — as data across the hinge, one `Retinue`
Agent per settlement — and the `Retinue` reverts to **settled-only**, dropping its
`detach()` / wandering-provisioning mode.

This sharpens **the bankless rung**: a wandering band holds only its **gold** hoard —
copper and silver are *bank denominations* that come into being only when the banks are
chartered on settle. Band-as-data holds `Member`s with **no accounts**, making the
invariant literal: **no `Bank` (of any currency) is constructed before a colony is
founded.** Deleting the `muster` colony (Phase A) also deletes its premature copper
`musterBank`, enforcing it.

### Phases

- **Phase A — band-as-data (removes the `muster` colony; byte-identical target).** A data
  carrier (`List<Member>` + larder) on the `Caravan`; a session-level muster generator
  (`GameSession.musterFollowing(n, spec)`) drawing peasants as data on the
  demographic/naming RNGs in the same `Retinue.newPeasant` order (gender → age → skills →
  name), so reproducible and needing **no colony**; `SettlerCaravan.tick`/`dissolve`
  operate on the data; the settle seam (`SimulationHarness.createRetinueFromBand`) reads
  it; and the caravan drivers muster bands with **no** throwaway colony/bank. Verify
  byte-identical with a full-run CSV checksum diff (the never-run muster colony perturbs
  nothing economic). Self-contained, and the enabler for Phase B.
- **Phase B — band-first standard sims (re-validate calibration).** Reuse
  `reFoundStandardColony` (generalized to a fresh, non-dissolution band) so each standard
  sim musters a band as data and settles it in place at t=0 into its province, instead of
  `foundStandardColony`. Same people, same opening money (`DEFAULT_RULER_GOLD` → the band
  hoard), same province. **Not** guaranteed byte-identical — treat like any
  calibration-touching change (checksum diff + suite). Keep a `foundStandardColony(…)`
  convenience that internally musters an in-place band so scenarios stay one-liners.
  `SmallOpenEconomy` (bare, pool-less, `createLaborers` directly) stays the documented
  exception.
- **Phase C — deferred.** The dwell-able `HOLDING` phase + founder/holder config + village
  hall (`docs/village-founding.md` Phase 3) is out of scope here: a **zero-length**
  `HOLDING` (founder → `Ruler` at t=0) already satisfies "no compile-time settlement."

**Suggested first step: Phase A alone** — self-contained, removes a genuine compile-time
settlement, byte-identical target, and unblocks Phase B without committing to the
calibrated-run reroute. Most of the surrounding machinery (the `Property` interface, the
foundry, `reFoundStandardColony`, runtime caravan founding) is already built; band-as-data
is the missing enabler.

## Open questions deferred to later

- The **per-rung workforce floors** (and whether `VILLAGE → HOLDING` should have any
  consequence beyond the rank marker before the dissolution at the hinge).
- Whether a **noble** caught in a collapse disbands into the following like a laborer
  (the decided default) or has any distinct fate (it is a rentier with no land of its
  own once the settlement is gone).
- How a band **decides** to settle vs. keep wandering once movement exists (readiness
  signal + site choice), and how that composes with determinism.
- Whether the hoard should ever take a **haircut** on a catastrophic collapse (the
  current default is full conservation) — a lever for making collapse costlier.

### Decided (2026-07-02) — from the RimWorld caravan comparison

A comparison against RimWorld's caravan architecture (the porting reference at
`C:/Code/RimWorldDebug/docs/caravan-system.md`) raised entity-level questions, all
now resolved as below (none implemented yet). The march/routing decisions live in
`docs/caravan-march.md` §Decided; the trade/arrival-action ones in
`docs/caravan-trade.md` §Decided.

- **Off-road demographics — a full daily pass on a per-band salted stream.** Band
  members currently neither age nor die while wandering (verified: no mortality or
  aging pass anywhere in `Caravan` / `SettlerCaravan.tick`), contradicting the
  "mortality is always on — there is no toggle" doctrine and quietly making the road
  *safer* than settlement. Decided: a session-level daily pass applies the
  Coale-Demeny draw and aging to every band `Member`, drawing on a **new salted
  per-band demographic stream** (per the convention that new draws get their own
  stream) — colony streams untouched, so band-free runs stay byte-identical.
  (RimWorld's precedent: off-map pawns keep ticking at full fidelity; the caravan
  only *satisfies* needs.)
- **Leader succession on the road — heir, else ablest follower.** When the leader
  dies mid-march, the same-dynasty heir draw runs there and then (as households do);
  if the line is extinct, the **ablest following `Member` is promoted** to leader
  under a new dynasty. The band always survives leader death and the march continues.
- **Births and weddings on the march — mortality only.** Biology beyond death is
  suspended while wandering: no weddings, no conceptions, no births until the band
  settles (mirroring RimWorld suspending jobs but not health). This keeps the
  marriage market a settlement mechanism, off the band's data.
- **Starvation kills — a ramping multiplier.** When the larder empties, each member's
  mortality draw takes a hunger multiplier that grows with consecutive unfed days —
  gradual, per-member, composing with the demographics pass above. The
  decaying-asset clock becomes literal deaths, not an abstract dwindle.
- **Warband visibility — deferred to the military flavor.** Nothing reads visibility
  until raid/interception targeting exists, so no code now; the formula to adopt then
  is RimWorld's (a curve over total band size, ×0.3 while stationary) — big slow
  bands are seen, lean ones slip through.
- **Serialize intent, not derived state — recorded as the future save contract.**
  When save/load arrives (the playable-game direction), a band saves its target
  province + progress spent, never the computed route/corridors, which are recomputed
  on load — RimWorld's pattern. Noted in `docs/architecture.md`.
