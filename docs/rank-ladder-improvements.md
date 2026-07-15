# Plan: rank-ladder improvements (R1–R5)

**Status:** PLAN — no code. Five improvements to the `Rank` promotion/demotion mechanic, motivated by
the friction Phases D–E exposed (the settlement-head reforms Captain→Ruler now bypass the ladder and
hand-roll their own estate/money handling). Companion to [`docs/rank-ladder.md`](rank-ladder.md) (the
mechanic), [`docs/settlement-tier-ladder-plan.md`](settlement-tier-ladder-plan.md) (the tier ladder
that drives head ranks) and [`docs/city-and-league.md`](city-and-league.md) (the `CITY`/Mayor design).

## Current state (what's there now)

- **`agent/Rank`** — the 16-rung enum (`HOUSEHOLD`(0) `CARAVAN`(1) `HOLDING`(2) `VILLAGE`(3) `CITY`(4)
  … `HEGEMONY`(15)), with `promoted()`/`demoted()` adjacency, titles, casus belli (mostly dormant).
- **`agent/RankLadder`** — per-colony reform engine. `promote(h)`/`demote(h)` walk to the **nearest
  realized rung** in that direction (`nextRealized`, skipping ranks with no factory) and `reform`
  it: snapshot the `Estate` (members + copper balances), build the target type via its `RankFactory`,
  close the old account (money conserved), swap the agent (add now, remove end-of-step).
- **`agent/RankFactory`** — `Household reform(Estate, Settlement)`, one per **realized** rank.
- **`agent/Estate`** — `record(List<Member> members, double checking, double savings)` (copper).
- **`simulation/SocialMobility`** — owns the ladder (`rankLadder()`, built lazily), registers **two**
  factories: `HOLDING` (Laborer→silver-banking Noble — ennoblement) and `HOUSEHOLD` (Noble→copper
  Laborer — ruin demotion). Ennoblement = `rankLadder().promote(best)` (HOUSEHOLD→skip CARAVAN→HOLDING);
  ruin = `rankLadder().demote(noble)` (HOLDING→skip CARAVAN→HOUSEHOLD).
- **Realized types:** `Laborer`(HOUSEHOLD), `Noble`(HOLDING), `Ruler`(VILLAGE), plus **`Captain`
  (CARAVAN)** newly realized in Phase D1 — but **not** registered as a ladder factory (that would make
  `promote(Laborer)` land on Captain, breaking ennoblement). `Mayor`(CITY) is **not realized**.
- **The Phase D/E gap:** Captain→Ruler is hand-rolled in `SimulationHarness.bootRulerEconomy` (mints a
  fresh treasury, reuses `createRulerFromLeader`) rather than going through the ladder; the head's
  `rank()` is hardcoded per type; `TOWN→METROPOLIS` never reforms Ruler→Mayor.

---

## R1 — Targeted `reformTo(household, rank)` (the enabler)

**Goal.** Replace the adjacency-walk `promote/demote` with an explicit `reformTo(Household, Rank)`
primitive, so every rank change names its target. This removes the "skip unrealized rungs" workaround
(and the ennoblement-collision trap it exists to dodge) and lets **all** reforms — ennoblement, ruin,
and the tier-driven head changes — use one money-conserving path instead of the boot duplicating it.

**Seams.** `RankLadder.reform(household, Optional<Rank> target)` already exists as the core (it just
isn't exposed targeted); `RankFactory`; `SocialMobility.rankLadder()` (factory registration);
`SimulationHarness.bootRulerEconomy` (the hand-rolled Captain→Ruler).

**Steps.**
1. `RankLadder`: add `public Household reformTo(Household h, Rank target)` = `reform(h, Optional.of(target))`
   (return `null` if `target` has no factory). Keep `promote/demote` as thin adjacency wrappers for now
   (callers migrate to `reformTo`), or delete once no caller walks.
2. **Register the head factories** the ladder was missing, guarded so they can't be reached by an
   adjacency walk from a laborer (they won't be — everyone uses `reformTo`): `CARAVAN`→`new Captain(estate…)`,
   `VILLAGE`→`new Ruler(estate…)`, `CITY`→`new Mayor(estate…)` (R2). Each factory **carries the estate**
   (money conserved); it does **not** inject founding capital.
3. Migrate ennoblement to `reformTo(best, Rank.HOLDING)` and ruin to `reformTo(noble, Rank.HOUSEHOLD)`
   (behaviour-identical — same factories, explicit target instead of skip-walk).
4. Rework `bootRulerEconomy`: replace the hand-rolled Captain→Ruler with `reformTo(captain, Rank.VILLAGE)`,
   then **separately inject** the founding treasury into the resulting ruler (the injection is *not*
   conserved money — it's the same bootstrap capital a mature founding mints; keep it a distinct step so
   the reform stays a pure identity/money carry). The pool reform (camp→settled reserve) stays as is for
   now (it becomes Property in R5).

**Money note.** The reform conserves what the household *held*; the **founding injection**
(`DEFAULT_RULER_GOLD`) is a separate capitalization step (mature founding mints it too). Keeping them
separate is the key insight — don't fold the injection into the factory.

**Risk / tests.** Low. Behaviour-identical for ennoblement/ruin (covered by `LaborerEnnoblementTest`,
`NobleDemotionTest`, `RuinedNobleDemotionTest`). Full reactor green.

> **SHIPPED (R1 core) 2026-07-15.** `RankLadder.reformTo(household, rank)` added; ennoblement
> (`reformTo(best, HOLDING)`) and ruin demotion (`reformTo(noble, HOUSEHOLD)`) migrated off the
> adjacency walk — behaviour-identical, so registering the intermediate `CARAVAN` rung later won't
> break them. **Moved to R2:** registering the `CARAVAN`/`VILLAGE`/`CITY` head factories and migrating
> `bootRulerEconomy`'s hand-rolled Captain→Ruler — the boot *is* the `CAMP→SMALLHOLDING` crossing, so it
> belongs with R2's "reform the head on band crossings" machinery (the factories need the harness's
> ruler params, which R2 wires alongside `Mayor`). Doing it here then reworking in R2 would be churn.

---

## R2 — Derive the head rank from the tier + realize Mayor

**Goal.** Make the reconciled "the head's Rank is *derived* from the tier" real, and realize the missing
**Mayor** (`CITY`) at `METROPOLIS`. One place maps tier→rank; the head is reformed (via R1) whenever it
crosses a rank band.

**Seams.** `settlement/SettlementTier`; `Settlement.onTierAdvance` (the Phase D callback, currently
cleared after the SMALLHOLDING boot); `agent/ruler/Ruler`; `docs/city-and-league.md` (the Mayor design —
`Mayor extends Ruler`, same gold bank, balances carried 1:1).

**Steps.**
1. Add `Rank SettlementTier.headRank()` (or a helper): `CAMP/COTTAGE/HAMLET → CARAVAN`,
   `SMALLHOLDING/TOWN → VILLAGE`, `METROPOLIS → CITY`.
2. **Realize `Mayor`** — `agent/ruler/Mayor extends Ruler` (`rank()`=`CITY`, same gold bank, inherits the
   ruler economy; the light urbanize reform of `docs/city-and-league.md`). Register its `CITY` factory
   (R1 step 2). `SimulationHarness` gains a `Mayor` successor/printer wiring parallel to `Ruler`'s.
3. **Reform the head on band crossings.** Generalize the `onTierAdvance` callback from "boot at
   SMALLHOLDING" to "when the tier crosses a `headRank()` boundary, `reformTo(head, newRank)`":
   `CAMP→…→SMALLHOLDING` boots (Captain→Ruler, the R1 boot); `TOWN→METROPOLIS` reforms Ruler→Mayor.
   Stop clearing the callback after the boot (it must keep firing for the METROPOLIS crossing).
4. `Household.rank()` can then default to `colony.getTier().headRank()` for the head (or stay per-type —
   the two agree by construction once R2 reforms on every crossing).

**Risk / tests.** Medium — realizing Mayor + keeping the callback live past SMALLHOLDING.

> **SHIPPED (R2) 2026-07-15.** `SettlementTier.headRank()` (CAMP/COTTAGE/HAMLET→CARAVAN,
> SMALLHOLDING/TOWN→VILLAGE, METROPOLIS→CITY); `agent/ruler/Mayor extends Ruler` (rank CITY, role
> "Mayor", same gold economy, same-dynasty Mayor successor); the harness registers the **CITY factory**
> in `installRuler` (so every ruler-bearing colony can urbanize) and gains `reformRulerToMayor` (the
> gold→gold reform, treasury carried 1:1 — money conserved); the found-at-Camp `onTierAdvance` callback
> now also fires it at the `METROPOLIS` crossing and **stays live past the SMALLHOLDING boot**.
> `MayorReformTest` drives the reform end-of-step and asserts Mayor/CITY + conserved treasury + the
> economy keeps running. Full reactor green.
>
> **Deferred (mature-founding consistency):** a colony **founded mature** at METROPOLIS (e.g.
> `HomogeneousEconomy`/Dhenijansar) still creates a `Ruler`, not a `Mayor` — so `headRank(METROPOLIS)`
> and the actual head briefly disagree there. Only the found-at-Camp *climb* reforms to Mayor today.
> Making `createDefaultRuler` pick `Mayor` vs `Ruler` by founding tier is a small change but ripples the
> default scenario's head *label* (Ruler→Mayor in logs/roster/CSVs), so it's deferred to avoid
> destabilizing the smoke baseline. Nothing reads `headRank()` yet except the crossing logic, so the
> inconsistency is latent.

---

## R5 — Unify assets behind a `Property` interface (do before R3)

**Goal.** Replace the ad-hoc `Captain.following` (the pool) and `Noble.firms` with a first-class,
transferable **holding** concept, so a reform (and later inheritance/conquest) moves assets uniformly —
and so **banks become holdings** resolvable from the colony (the precondition R3 needs). `docs/rank-ladder.md`
§"Decided since" already calls for this ("Holdings are a first-class concept, distinct from `Estate`").

**Seams.** `agent/noble/Noble` (`firms`, `transferPropertyTo` — already exists for the ruin hand-off),
`agent/Captain` (`following`), `agent/Retinue`, `bank/Bank`, `SocialMobility.demoteRuinedNoble`
(reassigns holdings today).

**Steps.**
1. Define `agent/Property` (a productive asset owned by a household): implementors wrap a `Retinue`
   (pool), a firm, a `Bank`, the village hall. Minimal surface: an owner ref + `transferTo(Household)`.
2. Give `Household` an optional `holdings()` list (default empty); `Noble.firms`/`Captain.following`
   become `Property` holdings. `transferPropertyTo` generalizes to move `holdings()`.
3. **Estate vs holdings:** a reform carries the **`Estate`** (liquid identity — members + balances) as
   today; **holdings transfer separately** (the reformed household adopts the predecessor's holdings).
   Wire this into `RankLadder.reform` (R1) so Captain→Ruler carries the pool, Noble→Laborer sheds its
   firms to another owner, etc., uniformly — deleting the boot's hand-rolled pool move.
4. **Banks-as-holdings:** model the copper/silver/gold banks as colony holdings resolvable by tier
   (`colony.bankFor(rank)`), so a factory can open the reformed household's account without capturing
   harness state — the R3 precondition.

**Risk / tests.** Medium — touches asset ownership across `Noble`/`Captain`/`Retinue`/`Bank`. Behaviour
should stay identical (the same assets move, just through one path). `RuinedNobleDemotionTest` (holdings
reassignment) and the caravan dissolution tests are the guardrails.

---

## R3 — Move the `RankLadder` to `GameSession`

**Goal.** One session-scoped ladder so a **colony-less** wandering `Caravan` can hold a `CARAVAN` rank —
unifying the mobile band and the settled camp's `Captain` (the Phase D "same entity" that's only
conceptual today). `docs/rank-ladder.md` §"Decided since" specifies this and its precondition.

**Seams.** `settlement/GameSession`, `agent/RankLadder` (constructor takes a colony today),
`SocialMobility` (owns/builds it), the factories (capture per-colony banks today).

**Steps.**
1. Depends on **R5** (banks-as-holdings): the factories must resolve banks from the **passed scope**
   (the `Settlement` in `reform`, or the session) rather than capturing `SocialMobility`/harness state.
2. Build **one** `RankLadder` on `GameSession`, registered once with tier/colony-parametric factories.
   `SocialMobility` delegates to it (or is retired for the reform bits, keeping only the ennoblement
   *selection* + step actions).
3. A wandering `Caravan`/`SettlerCaravan` (colony-less) can then be reformed on the session ladder
   (e.g. a band's Captain is a real `CARAVAN` household on the session, not just a `Member` leader) —
   the seam that finally makes "the Captain *is* the band across mobile↔settled" literal.

**Risk / tests.** **Highest** — changes where the ladder lives and how factories resolve resources; touches
every reform caller and the multi-colony `SessionRunner`. Do last, behind R1+R5. Full reactor + the
concurrency/twin-settlement tests are the guardrails.

---

## R4 — Tier collapse-descent → head demotion (the symmetric un-boot)

**Goal.** Close the ladder's bidirectionality: on tier **descent** across a rank band, **demote** the head
(the Phase E remainder). `METROPOLIS→TOWN` demotes Mayor→Ruler; `SMALLHOLDING→HAMLET` demotes Ruler→Captain
(the **un-boot**: tear the ruler economy back down to a foraging camp). Today demotion has one trigger
(noble insolvency); tier descent is the natural second.

**Seams.** `Settlement.grow()` shrink (fires nothing on descent today) — add an `onTierDescent` callback
symmetric to `onTierAdvance`; `SimulationHarness.bootRulerEconomy` (write its inverse); the Phase E
descent floor (`grow()` floors booted colonies at SMALLHOLDING — R4 lifts that floor once the un-boot exists).

**Steps.**
1. Add `Settlement.onTierDescent(Consumer<SettlementTier>)`, fired from the `grow()` shrink loop per rung
   descended (mirror of `onTierAdvance`).
2. **Head demotion** (cheap with R1): at a rank-band descent, `reformTo(head, newRank)` — Mayor→Ruler,
   Ruler→Captain.
3. **`unbootRulerEconomy`** (the hard part): the inverse of the boot — dissolve firms (reuse the
   `FirmFactory.dissolve`/`DynamicFirmProvisioner` path), drain silver/gold banks, reform Ruler→Captain,
   switch the pool back to `Retinue.camp()` mode. With R5, most of this is "shed the holdings"; the
   remainder is dissolving firms and re-homing the pool.
4. Lift the Phase E "booted colonies floor at SMALLHOLDING" guard once the un-boot exists, so descent is
   truly symmetric down to CAMP→depart-as-caravan.

**Risk / tests.** High — the un-boot teardown (money conservation across bank drains, firm dissolution
mid-run). Rebaseline the collapse smoke tests (a colony now steps Mayor→Ruler→Captain→depart). Add a
`SettlementDescentTest` driving a Mayor down to a departing caravan.

> **SHIPPED (R4 — the clean half) 2026-07-15.** The symmetric head descent among the **booted** tiers:
> `Settlement.onTierDescent` (fired from `grow()`'s shrink loop) + `reformMayorToRuler`
> (`reformTo(mayor, VILLAGE)`, gold→gold, treasury conserved) wired to the `METROPOLIS→TOWN` crossing —
> the exact inverse of R2. Registered the `VILLAGE` factory alongside `CITY` in `installRuler`.
> `MayorReformTest` covers both directions. Full reactor green.
>
> **STILL DEFERRED (the hard half — the un-boot):** steps 3–4 above — tearing a booted colony back down
> into a foraging camp below `SMALLHOLDING` (Ruler→Captain, dissolve firms + silver/gold banks,
> pool→`camp()`). Booted colonies still **floor their starvation-descent at `SMALLHOLDING`** (the Phase E
> guard) and dissolve into a caravan there rather than reverting to a camp — a large, low-frequency
> teardown best done after R5 (Property/holdings) makes the asset shedding clean.

---

## Recommended sequence & dependencies

```
R1 (reformTo)  ──►  R2 (tier→rank + Mayor)  ──►  R4 (descent demotion + un-boot)
      │                                             ▲
      └──►  R5 (Property/holdings)  ──►  R3 (session ladder)
                     └──────────────────────────────┘  (R5 also cleans R1's asset moves)
```

> **Status 2026-07-15:** **R1, R2, R4-core SHIPPED** (the high-value core). **R5 and R3 DEFERRED** by
> the user in favour of the concrete Phase G forage work — R5 is largely cosmetic without R3, and R3 is
> high-risk (SessionRunner/concurrency, factory bank resolution) for a mostly-architectural payoff. Both
> remain valid future work behind the R1/R2/R4 foundation; R4's un-boot half is also still deferred.

- **R1 first** — the enabler; pays down real Phase D/E debt (the hand-rolled boot reform) on its own.
- **R2** next — realizes Mayor, closes the head ladder; small and high-value.
- **R5** — the holdings refactor; unblocks R3 and cleans R1's asset transfers.
- **R4** — falls out of R1+R2 for the head reform; the un-boot teardown is the real work (needs R5 to be clean).
- **R3 last** — highest-risk; do only behind R1+R5.

**Smallest valuable slice:** R1 + R2 (targeted reform + Mayor). That alone unifies all head reforms on one
money-conserving path and completes Captain→Ruler→Mayor — worth shipping before the deeper R3/R4/R5 work.

## Risks (cross-cutting)
- **Money conservation** — every reform must conserve carried balances; founding **injections**
  (`DEFAULT_RULER_GOLD`) stay separate capitalization steps, never folded into a factory. The
  money-supply invariants in the smoke tests are the guardrail.
- **Callback lifecycle** — R2 keeps `onTierAdvance` live past SMALLHOLDING; R4 adds `onTierDescent`. Both
  defer their heavy agent swaps to end-of-step (as the boot does).
- **Not byte-identical** past R2 (head types change by tier); rebaseline collapse/founding smoke tests.
