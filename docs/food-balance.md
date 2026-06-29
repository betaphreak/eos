# Investigation note: the food-balance collapse

**Status:** investigation findings (2026-06-28). No code changed — this records *why*
ruler colonies collapse at ~6–10 years, established by instrumenting the default
colony's necessity economy over time. Companion to `docs/births.md` (which found
births cannot fix collapse because it precedes the 15-year maturation window) and the
`colony-collapse-accepted` memory.

## Method

A temporary probe traced the default closed colony (Dhenijansar, seed 7654321) at
60-day resolution — laborer count, pool size, necessity firm count, necessity price,
supply offered, volume traded, smoothed unmet demand, necessity output, firm stock,
and total laborer larders — and swept founding food capacity, pool size, founder age,
mean skill, and births on/off. The probe was deleted after; findings below.

## The collapse is two compounding failure modes

### A. Founding under-provisioning → a violent early crunch (months ~6–18)

The default colony founds with **one necessity firm** for **~900 mouths** (405
laborers + a 495 standing pool reserve). The deep larders (laborers open with ~5900
units total; the pool with a 150-day larder) mask the production deficit for ~8
months, after which:

- the **pool reserve starves out almost entirely** (495 → 0 by day ~240) — it defers
  to the working laborers when food is scarce (a price-sensitive relief budget), so the
  reserve is sacrificed first and is gone within 8 months;
- then **laborers themselves starve** as larders deplete and the necessity price
  spirals (2 → 5 → 14 → 44): the workforce crashes 405 → ~150 by year 1.3.

Dynamic firm provisioning *does* grow the necessity sector, but reactively and far too
slowly to cover the founding population before the larders run out.

**This mode is straightforwardly fixable.** Founding with ~12 necessity firms (sized to
the population) **eliminates the early crunch**: the workforce then holds **stable at
~126 for ~3 years** with low food prices, instead of crashing in months.

### B. Replacement ratchet + a food-price spiral → the residual decline (years ~3–8)

Even with adequate founding food, the colony still collapses by ~year 7–8:

- once the **pool reserve drains** (~year 3 with adequate food; the reserve drains in
  only ~2 years once **marriages** pull spouses out of it and **births** add child
  mouths), laborer deaths go **unreplaced** — the documented replacement-only ratchet;
- necessity **output sits at bare parity with consumption** (~200–250/day for ~300
  people), so any growth in mouths tips the balance and the **price spirals** (→ 44–72);
- the workforce then declines monotonically to the dissolution floor.

**The late-stage price spiral — diagnosed.** In the death throes the necessity price
runs away to 44–72. Mechanism:
- It is **not** firms throttling supply. A firm offers its *entire* product stock
  (`ConsumerGoodFirm.act` → `addSellOffer(product.getQuantity())`). The "high output"
  that earlier looked like withheld supply was a **measurement artifact**:
  `getOutput()` returns a *stale* figure for a firm that got no labour this step (it
  only updates `output` when `newOutput > 0`), so idle firms report their last busy
  output while producing nothing.
- The real cause is **labour-starved supply meeting inelastic survival demand.** As the
  workforce collapses, the (often over-chartered) necessity firms can't be staffed, so
  actual production falls below demand. Laborer demand is floored at `minN` — a
  household under two days' food must buy at least a day's ration *regardless of price*
  — so aggregate demand is **inelastic** below supply. The market price is found by a
  bounded binary search (±`zeta` = 10 %/step). When supply drops below the `minN`
  floor, **no price clears** (demand ≥ `minN` > supply at every price), so the search
  pins to its upper bound and the price ratchets +10 %/step **without bound** → 44, 72…
- It is a **symptom and accelerant** of the workforce collapse, not an independent
  cause: fewer workers → less food < survival demand → runaway price → the price spike
  starves the buyers who can't pay → faster collapse. (So the dramatic 44–72 is real
  starvation pricing, not "plenty"; the colony genuinely cannot produce enough food
  once its workforce is gone.)

## Why the obvious levers don't work

- **Births make it worse, not better** — they add consuming mouths (and, via marriage,
  drain the pool of spouses) years before any child matures at 15. Higher birth rates
  collapse *sooner* (see `docs/births.md`).
- **Mean skill is not a usable surplus lever — it triggers a DEFLATIONARY SPIRAL.**
  Food output scales with `productivityOf(skill)` (skill 5 → 0.25; skill 12 → 1.73, as
  the curve goes cubic above 10), so raising skill *should* create surplus. Instead, a
  high-skill colony overproduces massively against **ration-capped demand** (people eat
  a fixed ~0.5/day no matter how much is made), so the necessity (and enjoyment) price
  **crashes toward zero**; laborer larders balloon (food is nearly free) while laborer
  wealth goes **deeply negative** (the labor-share wage budget collapses with crashed
  firm revenue, so wages crater and households borrow to the limit). The colony then
  declines roughly twice as fast as the skill-5 baseline. (An earlier note here claimed
  skill 10+ "collapses within months" — that was a *config-specific* artifact of the
  food-rich + large-reserve probe, where the laborer founding endowment, which scales
  with the head's skill-sum, overwhelmed the ruler's fixed 50-gold treasury at
  founding; in the clean default config skill 12 declines via the deflation spiral but
  does not collapse in months. Both point at calibration gaps, below.)
  - **A *modest* bump (male mean 7 → 8) is safe but still unhelpful — MEASURED.** It does
    *not* trigger the spiral (the glut-aware close rule of item 3 and the sub-cubic skill
    level keep prices/wages healthy: founding necessity price dips 0.37 → 0.18 from the
    extra output, but recovers to run *higher* than baseline by year 3, and avg laborer
    savings stay positive). Yet the colony still collapses **~1.4 y sooner** (1455-02 vs
    1456-06) — near the ~1 y noise band these levers live in, so call it "no help." Two
    coupled costs eat the output gain: the surplus food can't be *eaten* (ration-capped
    demand), so it only depresses founding firm revenue; and the ~14 % larger skill-sum
    **endowment** per promoted laborer drains the ruler's *fixed* 50-gold treasury faster,
    underfunding pool relief. So even below the deflation threshold, raising skill shifts
    the bottleneck rather than relieving it — output capacity is not the binding constraint.
  - **Why overproduction doesn't self-correct (firm closure doesn't fire).** The
    dynamic provisioning's *close* rule dissolves a firm only when its **capacity
    utilization** (`output / machine-capacity`) falls below 0.55 — an "idle machines"
    signal. Under deflation the firms run **flat-out at near-100% utilization** (busy
    machines) even as the market is glutted and the price has crashed, so the
    "overbuilt" signal never trips and no firm is closed (CLAUDE.md confirms the close
    rule never fires in practice). High utilization is in fact the *charter* signal, so
    the loop is wired to add firms when machines are busy, never to read a price/glut
    collapse. **The supply-control loop measures machine busyness, not profitability or
    oversupply** — a price/profit- or inventory-based close signal is needed for it to
    react to deflation. This is a genuine model gap, independent of births.
- **More food firms / bigger reserve / younger founders / external inflow** each only
  shift the timing; none reaches the ~15-year survival the births payoff needs (swept
  in `docs/births.md` Phase 3).
- **Narrowing the price band (`ConsumerGoodMarket.zeta`) does NOT help.** `zeta` caps
  how far the market price can move per step (±10 %). Shrinking it to ±1 % to tame the
  late-stage spiral was tried and is **counter-productive**: the default colony then
  collapses in **~1 year** (vs ~7) — it can no longer raise the price fast enough to
  *ration* its chronically scarce food, so food is mis-allocated and it starves almost
  immediately. The narrow band only *helps* the high-skill deflationary glut (the price
  can't crash, so wages don't collapse — that colony then holds ~400 laborers for 10 y),
  but a normal colony never enters that regime. The two regimes need **opposite**
  price-discovery speeds — scarcity wants fast price *rises*, a glut wants slow price
  *falls* — so a single symmetric band cannot serve both, and the model lives in the
  scarcity regime where ±10 % is load-bearing. (And it does not address the spiral's
  root: with inelastic `minN` demand above supply no price clears, so ±1 % merely slows
  the runaway ~10×.) If the high-productivity regime is ever wanted, an **asymmetric**
  band — capping how fast price may *fall*, not how fast it may rise — is the lever to
  explore, not a smaller symmetric one.

## The core blocker

To let births renew the population, a colony must **survive ~15 years** (the child
working-age floor). Survival is capped at **~7–8 years** by the reserve draining plus
the food-price spiral, and the obvious surplus lever (skill) destabilizes the economy.
Births are necessary but **insufficient**; the prerequisite is a food-economy /
survival fix.

## Recommended directions (each a calibration effort)

1. **Fix founding provisioning (clearest win) — DONE.** `foundStandardColony` now
   founds the necessity sector **sized to the labor force** instead of the single seed
   firm: `numNFirms = round(laborForce / cfg.foundingLaborersPerNFirm())` (default
   `foundingLaborersPerNFirm` = 30), clamped to what the colony's (province-capped)
   slots can ever seat (`Settlement.getMaxEffectiveSlots()` less the enjoyment firms
   and the four slot-claiming services — so `foundOnto` never rejects a firm). The
   default Dhenijansar colony founds **~14** necessity firms instead of 1; the founding
   food crunch softens and lifespan rises from ~7.7 y to **~10 y** (births off; the
   improvement is robust across `foundingLaborersPerNFirm` ≈ 25–50). The granular sims
   (`Hanseatic`, `SmallOpen`) call `createFirms` directly and are unaffected; a ratio
   of `0` keeps the fixed `numNFirms`. (Note: founding *already-full* over-provisions a
   high-skill colony into a faster deflationary collapse — orthogonal to the normal
   colony this helps; `GlutCloseTest` pins the ratio to 0 to study the glut-close in
   isolation.) This removes failure mode A; the residual collapse is failure mode B
   (the replacement ratchet), still open.
2. **Diagnose the late-stage food-price spiral — DONE** (see *The late-stage price
   spiral — diagnosed*, above). It is labour-starved supply falling below the inelastic
   `minN` survival demand: with no clearing price, the bounded ±10 %/step search
   ratchets the price away (→ 44–72). A symptom/accelerant of the workforce collapse,
   not an independent cause. The follow-on lever is to soften the `minN` runaway (e.g.
   ration the inelastic demand at a price cap, or let the price band widen) so the death
   throes don't price-starve the last buyers — a refinement, not the root fix.
3. **Make the supply-control loop price/profit-aware (prerequisite for any TFP gain) —
   DONE.** `Ruler.reviewSector`'s close rule now fires on an **unprofitable glut** as
   well as on idle capacity, so an over-supplied sector that has crashed its own price
   contracts instead of running flat-out forever. The glut is detected by a **crashed
   PRICE** (`sectorProfit < 0` AND market price below `GLUT_PRICE_FACTOR` = 0.3 × its
   initial reference price), *not* by unmet demand: the rest-day calendar inflates the
   smoothed unmet fraction to ~0.26 in **both** a true glut and a tight-but-needed
   sector, so unmet/pressure cannot tell them apart — but the price can (a deflationary
   glut sits far below its founding level, a needed sector hovers around it). An earlier
   pressure-based version of this gate **regressed** the normal colony — it dissolved
   productive necessity firms running at 96 % utilization merely for being loss-making,
   cutting food output and roughly halving colony lifespan (~7 y → ~3 y); the price gate
   removed that (lifespan restored to ~7 y) while still catching real deflation. The
   provisioning hysteresis was set to a **6-month** window (`MIN_FIRM_LIFETIME_DAYS` /
   `REENTRY_COOLDOWN_DAYS` = 180) — short enough to react within half a year, long
   enough to damp the seasonal charter↔dissolve hog cycle a 3-month window reintroduced.
   Verified: the high-skill glut still contracts its necessity sector (`GlutCloseTest`)
   and the normal colony is undisturbed. *Caveat:* a control-loop fix, not a cure — it
   reacts on a lag and does not by itself save a high-skill colony whose wage economy
   the deflation has already damaged; it is the prerequisite that stops output-raising
   levers (skill, TFP) from backfiring into an uncorrected deflation.
4. **Extend survival to the maturation window** — slow the reserve drain (bigger
   reserve with adequate food), widen the founding-age spread (so the cohort does not
   age out together), or soften mortality — so the first home-grown generation (15y) can
   come online. Only then can the births mechanism (`docs/births.md`) deliver renewal.
   - **Founding-age spread now includes children — DONE (partial).** The seeded peasant
     pool is no longer an adults-only working cohort: `Demography.samplePoolFoundingAgeDays`
     draws a fraction (`FOUNDING_CHILD_FRACTION` = 0.35) of the founding pool **below
     working age**, the rest from the usual working-age spread, so the pool is a genuine
     age pyramid. Pool children supply no labour, eat the smaller colony **child ration**
     (`SNACK`, capped at the relief ration; `Retinue.feed`), and are **excluded from
     promotion and marriage** (`promoteHighestSkilled`/`bestSpouseCandidate` gate on
     `Member.isAdult`) until they cross the working-age floor — at which point they
     replenish the promotable reserve. The labor-force size is unchanged (promotion still
     draws `round(promotionRatio · poolSize)` adults; `foundLaborersFromRetinue` sizes the
     cohort off what it actually gets, so a child-heavy pool can't over-promote). Measured
     on the default Dhenijansar colony (seed 7654321, births off): the **reserve no longer
     drains to zero in year 1** — children maturing keep it populated (~85 at dissolution
     vs. **0 by year 1** without children) — and the collapse horizon extends from
     **~9 years to ~11.5 years**. A real gain on the maturation window, but still short of
     the ~15y births needs; this is the *staggering* lever, to be combined with the others
     (bigger reserve, softer mortality) — not a standalone cure.
   - **Softer mortality is a near-flat lever — SWEPT, weak.** Scaling the human
     `LENIENT_MORTALITY_FACTOR` from 0.5 down through 0.35 / 0.25 / 0.15 (a 3× reduction
     in every adult hazard) moves the default colony's collapse only from ~11.5 y to
     ~12.5 y — **and not even monotonically** (0.35 came out *worse* than 0.5, i.e. the
     response is in the noise). **Old-age mortality is not the binding constraint:** the
     decisive deaths are the founding **starvation** crunch (immune to the life table)
     and the workforce stalling once the pool holds **no promotable adults** (all
     children) — softer mortality keeps more laborers and children alive, but they still
     can't be fed past the food ceiling or promoted (children). A gentler table helps
     only at the margin and should be stacked, not relied on.
   - **Demand-driven promotion makes it WORSE — PROTOTYPED, rejected.** Hypothesis: the
     replacement-only ratchet (promote 1:1 only on death) is the cause, so promote *extra*
     pool adults to staff the provisioning-grown necessity sector toward demand. Built as a
     monthly harness step-action (target = `nFirms · 30` workers, batched, adult-capped) and
     measured on the default colony: collapse came **~1 year sooner** (1455-06 vs 1456-06),
     with a **steeper year-1 crash** (420→170 vs 402→216). The extra workers are **net food
     consumers during the crunch** — a skill-5 worker's marginal food product doesn't cover
     its household's consumption while larders deplete — so promoting more drains the pool's
     adults into starving households faster. This **confirms the constraint is food output,
     not workforce headcount or promotion timing**: you cannot staff your way out of a food
     deficit. (Prototype reverted; finding kept here.) The corollary: holding the workforce
     to demand only helps *once the food economy can feed it* — so the food fixes (1–2, and a
     real TFP gain behind the glut-aware close rule, 3) are the prerequisite, not the labor
     supply.
   - **Household fission — BUILT, correct but DORMANT.** The deepest structural gap: new
     laborer *households* (the count the survival floor measures) are created only by
     pool-promotion, death-replacement, immigration and inheritance — **never from a
     grown child.** Births call `addMember`, so they grow a household's *size*, never the
     *count*; a colony-born child, even after maturing, stays a member until it inherits
     or starves. So births structurally **cannot** renew the household population — the
     real reason they are "necessary but insufficient." Fission closes that valve: when a
     household's colony-born child reaches working age it leaves to found its **own**
     laborer household (`AbstractHousehold.releaseGrownChild` / `Laborer.emancipateChild`
     / `SimulationHarness.formNewHouseholds`, deferred to end of step like ennoblement;
     food-conserving — the child carries a dowry out of its parent's larder). It grows the
     count at **zero net resource cost** (the child already existed and ate as a member).
     **Measured: it never fires on the default colony** (and is a no-op there — collapse
     stays 1456-06-28). The renewal pipeline **marriage → birth → maturation → fission**
     is throttled at the *source*: births need a *married* couple **and** a 14-day food
     buffer, both scarce in a food-stressed colony, so almost no children are born; the
     few that are get culled first (non-head members starve before the head) or mature
     only after their parent household has died. Stress-testing each gate in turn
     (children spared the cull, dowry → 1, working-age floor → 4) lifts it only to **~3
     fissions in 10 years** — still negligible, and survival does not improve. So fission
     is the correct renewal mechanism but **inert until the food economy supports a
     positive birth rate** — the same root every other lever hits. Kept in place (dormant,
     like the glut-aware close rule was before colonies could contract) so the valve is
     ready once food is fixed.

Fixes 1–4 together are the path from "collapses at ~7 years" to "survives long enough
for births to sustain it." None is a single knob; this is the food-economy calibration
program that the long-accepted colony collapse has been deferring.
