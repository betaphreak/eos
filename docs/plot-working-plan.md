# Plan: households work plots for food (Civ4-style plot-working economy)

**Status:** **P1 + P2 SHIPPED** (2026-07-16); P3–P5 still PLAN. The fix for the **large / mature-colony collapse**: give every household a
**home plot** it farms for its own subsistence food, so baseline survival is decoupled from the market.
The firms stay as a market/surplus layer *on top*. Companion to
[`docs/settlement-tier-ladder-plan.md`](settlement-tier-ladder-plan.md) (the tier ladder + the
found-at-Camp forage this generalizes), [`docs/food-balance.md`](food-balance.md) and the collapse
analysis (memory `colony-collapse-accepted`), and [`docs/plots.md`](plots.md) (the plot model).

> **Terminology:** this uses **plot** throughout (the project's word for a Civ4 *tile*).

## Why (the collapse root, and why plots fix it)

The mature-colony collapse is a **market-clearing failure**, not a physical food shortage: after the
reserve drains, the necessity price spirals and laborers **can't afford** food even while output is high
(memory `colony-collapse-accepted`, finding B) — or, in the small booted colony, the price crashes to 0
and the sole farm stops producing (the subsistence-floor stopgap, #1). Any fix that keeps survival
*inside* the market inherits that fragility. Having each household **work a plot for its own food** puts
subsistence *outside* the market: the market becomes about **surplus and trade**, not about whether
people eat. When the market fails, the market-dependent overflow (the landless pool) shrinks, but the
**landed households survive on their own plot food** — the colony contracts toward its plot-count core
instead of fully collapsing. This is how pre-industrial economies actually worked (subsistence farming +
a thin market layer), and it **supersedes the subsistence floor (#1)** rather than patching the market.

It is also a **generalization of what already ships**: the found-at-Camp economy is *already* laborers
working a plot for food (`Settlement.campForageYield` reads `Plot.yields()[FOOD]`). This plan makes that
the settled colony's permanent subsistence base — the camp's plot-working simply **continues** after the
boot instead of being replaced by a pure-market economy.

## Decisions (user, 2026-07-16)

| # | Decision |
|---|---|
| **Pop cap** | **Soft Malthusian cap (revised 2026-07-16, P2)** — plots are **shared**: multiple households may farm the same plot and its food **splits equally** among them (strict equal-split, `plotFood/N`). The colony spreads households one-per-plot until it has claimed all its province's workable land (density 1, each self-sufficient), then piles further households on (density rises → per-household food falls → the colony **self-limits by food**). So the province's plot count is the *carrying capacity at density 1* — a crowding **knee**, not a hard wall. No landless class; the pool is the renewal reserve only. (Supersedes the originally-planned hard one-per-plot cap + landless pool, which produced a growing idle underclass and made the 900-pool probes incoherent — sharing avoids both.) |
| **Labor** | **Peasant smallholder** — every household **both** farms its home plot (subsistence food into its own larder) **and** sells labor to firms (wages, market goods). One agent, two income streams; not a farmer-vs-specialist split. |
| **Firms** | The necessity firm (`NFirm`) **re-roles as a commercial / surplus farm** — marketed food for the *landless* (pool) and for trade, staffed by wage labor. Household subsistence comes from their own plots; firms are the "on top" market layer. |
| **Yields** | **Food first** — P1 works plots for FOOD only (the survival fix). Production and commerce plot yields are a later phase (P5). |

## Current state (what exists to build on)

- **`Plot.yields()`** → `[food, production, commerce]` (terrain + feature-if-wild + improvement + a
  NECESSITY bonus). `Plot.yieldFactor(Sector)` is the food TFP a farm firm already reads.
- **`PlotField`** — the colony's plot list (`getDistrictPlots`), the shared `ProvincePlotPool`,
  `claimPlot`/`appendPlot`/`claimBarePlot` (the camp forage plot, Phase G), `getMaxPlots` (the province
  cap). `PlotOccupant` is implemented by `Agent`, so **a household can occupy a plot today**.
- **Household eating** — `Laborer.act()` eats from its `necessity` larder (a `Necessity` good), starving
  members off when short (with granary child-relief). So **plot food drops in by adding to that larder**
  before the eat step; no new eat path needed.
- **The pool** — `Retinue` is the settlement's landless reserve/labor (founding, replacement,
  immigration, wedding, explorer draft). Naturally becomes the **landless** overflow.
- **`NFirm`** — the sole plot-occupying firm (subsistence agriculture, `IMPROVEMENT_FARM`), the thing the
  subsistence floor (#1) currently props up.
- **Camp forage (Phase G)** — `campForageYield = foragers × rate × Plot.yields()[FOOD]`; the settled
  analogue is exactly P1.

---

## P1 — Household home plots + subsistence food (the survival fix) — **SHIPPED 2026-07-16**

**What shipped.** Flag-gated (`SimulationConfig.homePlots`, default **false** → the whole existing suite
stays byte-identical), opted in by `CampFoundingEconomy`. A settled `Laborer` gains a `homePlot`
(`com.civstudio.settlement.Plot`); at the top of `Laborer.act()` it drops
`Settlement.homePlotFoodYield(homePlot)` — `Plot.yields()[0] × HOUSEHOLD_PLOT_RATE` (rate `1.0`,
uncalibrated) — straight into its `necessity` larder, **outside the market**, before it eats. Home
plots are claimed by `Settlement.claimHomePlot` → `PlotField.claimHomePlot` (seats the household — a
`PlotOccupant` — on a free workable plot, skipping peaks; **landless/`null` when the site is full**),
wired at every laborer-founding seam through two call-sites: `SimulationHarness.promoteToLaborer`
(founding cohort **and** promotion-replacement) and `SocialMobility.buildFissionHousehold` (fission
**and** returned-explorer households). A dead landed household **frees its plot** in `Settlement.newDay`
(before its successor is spawned, so the replacement reclaims it — turnover). `dailyFoodSurplus` counts
each landed household's home-plot food alongside the farms' output, so the food box stays honest when
self-feeding cuts market demand. Tests: `settlement.HomePlotTest` (field mechanics + landless overflow
+ reclaim), `simulation.HomePlotEconomyTest` (a booted home-plots colony's landed households self-feed
≥ a full adult ration and survive the boot transient). Full reactor green (328 engine + 55 server).

**Deferred from the original P1 sketch:** step 4 (retire the subsistence floor #1) is **not** done — the
floor stays as inert, redundant protection (it only ever binds for a colony's *sole* food farm, and is
untouched by flag-off colonies). It is retired when home plots become the default in **P2** (the cap
rebalance), per *"supersede, don't stack"* — kept for now so this slice stays additive and byte-identical
for every existing scenario.

**Goal.** Each landed household works one home plot each day → the plot's food yield goes **directly into
its larder** (non-market), before it turns to the market. Delivers the survival decoupling on its own.

**Steps.**
1. `AbstractHousehold` (or `Laborer`) gains a **home-plot** reference + a daily *work-my-plot* step in
   `act()`: `necessity.increase(homePlot.yields()[FOOD] × HOUSEHOLD_PLOT_RATE)` before the eat/buy logic.
   A household with no home plot (landless — the pool, or overflow) forages/relies on the market as today.
2. Assign home plots at founding and on the openings P2 manages (a basic "first N households get the
   first N workable plots" for P1; P2 makes it a real cap + turnover). Reuse `Settlement.claimPlot` /
   the province pool; the household is the `PlotOccupant`.
3. Food flow is **non-market**: plot food never touches `ConsumerGoodMarket`. The household still buys
   *market* goods (enjoyment, and surplus food when its plot falls short) as today.
4. **Retire the subsistence floor (#1)** once survival is plot-based (drop / gate `ConsumerGoodFirm`'s
   sole-food-producer floor — it was the stopgap this replaces).

**Seams.** `AbstractHousehold`/`Laborer.act`, `Plot.yields`, `Settlement.claimPlot`/`PlotField`,
`ConsumerGoodFirm` (unwind #1).

**Risk / tests.** Medium. Rebalances food (households self-feed → market food demand drops). A `PlotFood
Test`: a household on a food-yielding plot restocks its larder from the plot and survives a market with no
supply. **Not byte-identical.** The `HOUSEHOLD_PLOT_RATE` calibration sets subsistence self-sufficiency.

## P2 — Shared plots + Malthusian self-limit — **SHIPPED 2026-07-16**

**Revised model (user, 2026-07-16).** The originally-planned *hard* Civ4 cap (one household per plot,
overflow → landless pool) was replaced with a **soft, Malthusian** cap: plots are **shared** and a plot's
food **splits equally** among the households on it (strict `plotFood/N`). This is a refinement of P1's
mechanism rather than a founding/pool rewrite — no founding cohort cap, no landless routing, no
rebaselining the analytical probes. Instead the colony **self-limits by food**: it stays at density 1
(each household self-sufficient) until it has claimed all its province's workable land, then piles further
households on, thinning each one's share until the food cushion runs out (births slow / lean spells trim).
The province's plot count is the *carrying capacity at density 1* — the crowding knee, not a wall.

**What shipped.** `PlotField` gained a `homePlotLoads` map (plot → household count). `claimHomePlot` now
**shares**: it reuses a fully-freed home plot first, else claims fresh workable land while the province has
it (density 1), else crowds the least-loaded plot (density rises). `Settlement.homePlotFoodYield(plot)`
divides the plot's food by its `homePlotLoad` — the Malthusian split (summing the per-household shares over
a plot's households recovers the whole plot's food, so `dailyFoodSurplus` still counts each plot once).
Home-farm plots are kept **distinct from firm-occupied plots** (`firstVacantPlot` skips the load-map keys,
so a firm never claims farmland) and are **retained as claimed land at load 0** so turnover reuses them.
A dead household `releaseHomePlot`s its share in `Settlement.newDay` (before its successor, so the
replacement reuses the land). **No change** was needed to founding / fission / replacement gating — they
all keep calling `claimHomePlot`, which now always succeeds by sharing. Still flag-gated on
`SimulationConfig.homePlots` (default false → the whole existing suite byte-identical). Tests:
`HomePlotTest` (sharing under crowding, density-1 spread, the equal-split formula, freed-plot reuse),
`HomePlotEconomyTest.plotsAreSharedUnderCrowdingWithNoHardCap` (a large-pool founding seats more landed
households than distinct plots — no hard cap). `CampFoundingEconomy` survives the full 25-year run
unchanged (it stays under density 1 at its small size). Full reactor green (330 engine + 55 server).

**Known tension (deferred to P3):** shared home farming claims workable plots up to the province cap, so a
heavily-crowded home-plots colony can leave little land for the **dynamic firm provisioning** to charter
onto. It does not bite the shipping scenario (`CampFoundingEconomy` stays small), but the home-vs-firm land
split wants a real policy when the commercial-farm re-role (P3) lands. **Also still deferred:** retiring the
subsistence floor (#1), unchanged from P1.

## P3 — Re-role the farm firm as a commercial/surplus producer

**Goal.** With landed households self-feeding, `NFirm` becomes a **market** food producer for the
*landless* pool + trade + the wage-labor it hires — not the survival lifeline. The market food layer sits
"on top," sized to the residual (non-subsistence) demand.

**Steps.**
1. Necessity market demand now comes from the **landless** (pool relief) + households topping up when a
   plot falls short + trade — a fraction of today's. The dynamic firm provisioning sizes the farm sector to
   that residual (fewer farms; possibly zero when subsistence covers everyone).
2. Remove the subsistence floor (#1) fully (P1 already unwinds it) — the market may now let the commercial
   farm scale down or shut, because survival no longer depends on it.
3. `Settlement.dailyFoodSurplus()` (the food box) reads **plot food + firm output** across all tiers
   (unifying the camp branch, P4) so tier growth reflects the true food balance.

**Seams.** `NFirm`/`ConsumerGoodFirm`, `Ruler.reviewSector` (dynamic provisioning), `Settlement.dailyFood
Surplus`, `ConsumerGoodMarket`.

**Risk / tests.** Medium. The market economy shrinks to a surplus/trade role. `CommercialFarmTest`: with
landed households self-feeding, the farm sector sizes to the landless demand and the colony survives a
market shock (landed core unaffected).

## P4 — Unify the camp forage with settled plot-working

**Goal.** Collapse the Phase-G camp forage and P1 into **one** plot-working mechanism, so the camp's
plot-working simply **continues** after the boot (no separate camp code path).

**Steps.**
1. A camp's pooled foragers are landless households-in-waiting working plots collectively; a settled
   household works its own home plot. Express both through the same `Plot.yields()[FOOD]` step, so
   `campForageYield`/`campPlotFood` become the P1 mechanism at the CAMP tier.
2. The `dailyFoodSurplus()` camp branch and the settled branch converge (both = Σ plot food − eaten,
   plus the commercial-farm surplus at settled tiers).

**Seams.** `Settlement.campForageYield`/`campPlotFood`/`dailyFoodSurplus`, the Phase-G build-then-work
`advanceCampForageBuild` (the HUNTING_CAMP improvement raises the worked plot's food, unchanged).

**Risk / tests.** Low-medium (a consolidation). The found-at-Camp tests (`CampFoundingEconomy`,
`CampBootViabilityTest`) still pass; the boot transient the subsistence floor patched is now moot
(households self-feed from day 1).

## P5 — Production & commerce plot yields (fuller Civ4 model)

**Goal.** Extend plot-working to the other two Civ4 yields: a worked plot also yields **production**
(→ the colony's build/development, the tier ladder's growth work) and **commerce** (→ money / science),
with improvements (mine/cottage/lumbermill) and tech shaping them (`Improvement.techYieldChanges`).

**Steps.** `Plot.yields()` already carries all three; wire production into the build queue / tier
development and commerce into the treasury / research; let households (or the ruler) choose plot
improvements by yield and tech. This is the richest, most future-facing phase and is deliberately last.

**Seams.** `Plot.yields()` (indices 1,2), `BuildProject`/tier development, the treasury/tech tree,
`Improvement`/`TerrainRegistry`.

---

## Recommended sequence & dependencies

```
P1 (plot food) ──► P2 (plot cap) ──► P3 (commercial farm) ──► P4 (unify camp) ──► P5 (prod/commerce)
```

- **P1 first** — delivers the survival fix (landed households self-feed) and retires the subsistence-floor
  stopgap, even before the cap. Smallest step with the biggest payoff.
- **P2** — the Civ4 city-size cap; the big rebalance (colonies → dozens). Land this deliberately, with the
  smoke-test rebaseline and the analytical-probe rethink.
- **P3** — re-role the market/firm layer to surplus/trade.
- **P4** — fold the camp forage into one plot-working mechanism.
- **P5** — the other two yields, last.

**Smallest valuable slice:** P1 alone (households self-feed) likely already breaks the collapse for the
found-at-Camp scale and validates the model, before committing to the P2 rebalance.

## Risks (cross-cutting)

- **The P2 rebalance is large and not byte-identical** — colonies drop from hundreds to Civ4-scale dozens;
  founding-size, collapse-timing, wage/price, and skill-training smoke tests all rebaseline. The 900-pool
  analytical probes are the hardest to reconcile (they seed a colony's worth of people that no province of
  ~74 plots can land) — expect to rethink or opt them out.
- **Calibration** — `HOUSEHOLD_PLOT_RATE` (subsistence self-sufficiency), the landed/landless food split,
  and how much market food the commercial farm still supplies. Aim: a landed household on average ground
  is self-sufficient in food, so the market is genuinely surplus/trade.
- **Money conservation** — plot food is created outside the market (no counterparty), like the camp
  forage; keep it out of the bank/market accounting exactly as `campForageYield` does today.
- **Supersedes, don't stack** — P1/P3 should *remove* the subsistence floor (#1), not run both; the floor
  was the market-side stopgap this plan makes unnecessary.
