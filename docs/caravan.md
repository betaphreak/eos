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
pool — but its stale agent objects are not yet removed).
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

## Architecture mapping

- **The `Caravan` entity** = a colony-less **aggregate** holding a **leader** `Member`
  (the band's Captain, *not* a household class) + its **`Retinue`** (the same
  following-asset, now detached and marketless) + a carried hoard (`double`) + a
  position. The `Retinue` is **reused, not reinvented** — detaching it from the colony
  is the bulk of the work (see *Retinue and Caravan*). The Caravan is a colony-less
  aggregate, so — like the `RankLadder` and the supra-settlement ranks — it lives at
  the **`GameSession`** level, not on any `Settlement`.
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
  in the same session, led by its former sovereign at the band's site). The
  `CaravanEconomy` sim exercises the whole arc start-to-finish: three bands with
  different hoards muster, re-found, collapse, and reform. **Deferred:** the dwell-able
  `HOLDING` phase / `holder` type and the asset-distribution of
  `docs/village-founding.md` (re-founding goes straight to a seated `Ruler`).
- **Phase 5 — (after caravan trade) movement, foraging, trade**, and the
  `HOUSEHOLD → CARAVAN` gather transition.

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
