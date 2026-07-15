# Implementation plan: the SettlementTier growth ladder (Camp → City) + foraging-as-improvement

**Status:** PLAN — reconciled to the **unified-axis** decision (2026-07-15); still no code. See
**"Reconciled decisions (2026-07-15)"** below — it **supersedes** the original "additive, don't
touch Rank" framing (the `## Decisions` table further down and the byte-identical/additive notes in
Phases A–G). The *design* lives in [`docs/settlement-tiers.md`](settlement-tiers.md) §"Sub-City
tiers"; this is the phased implementation plan for it. Companion to [`docs/caravan.md`](caravan.md)
(the settle⇄unsettle cycle / Rank ladder this now **unifies with**) and
[`docs/explorer-caravan.md`](explorer-caravan.md) (the foraging expeditions that become the
**Camp-rung economy**).

## Reconciled decisions (2026-07-15 — supersede the `## Decisions` table below)

Thirteen calls that lock the **unified-axis** reading of `settlement-tiers.md` §S1 over this plan's
original "additive, don't touch Rank" stance. **One settle⇄unsettle axis; the tier is the single
source of truth; the head's `Rank` is derived from it.**

| # | Decision |
|---|---|
| **Axis** | **One unified settle⇄unsettle ladder**, `SettlementTier` the single source of truth: `caravan (mobile) ⇄ CAMP → COTTAGE → HAMLET → SMALLHOLDING → TOWN → METROPOLIS`. `Rank` keeps only the political super-structure **`LEAGUE`→`HEGEMONY`**; its low rungs (`CARAVAN`/`VILLAGE`/`CITY`) are **derived from the tier**, not independently advanced. |
| **Rungs (renamed)** | Rungs renamed to remove the `Rank` name collision: the old `VILLAGE` rung → **`SMALLHOLDING`**, the old `CITY` rung → **`METROPOLIS`**. Full ladder: `CAMP < COTTAGE < HAMLET < SMALLHOLDING < TOWN < METROPOLIS` (`SUBURBS` still the dormant future province-merge op). |
| **Head-rank map** | The tier drives the head household's `Rank`: mobile / `CAMP` / `COTTAGE` / `HAMLET` → **Captain** (`Rank.CARAVAN`); `SMALLHOLDING` / `TOWN` → **Ruler** (`Rank.VILLAGE`); `METROPOLIS` → **Mayor** (`Rank.CITY`). Crossing `TOWN → METROPOLIS` **promotes** Ruler→Mayor (realizes `Rank.CITY`); collapse-descent **demotes** symmetrically down to a departing mobile caravan. |
| **Realize Captain** | `Rank.CARAVAN` is finally **realized** — a concrete `Captain` household leads the band, with the **peasant pool as its asset** (as `docs/rank-ladder.md` Phase 5 always intended). It is the **same entity** as the existing marching/settler caravan band: one household across mobile ↔ settled. |
| **Found low** | Geographic/caravan colonies **found at `CAMP`** (a Captain-led band) and climb. **Analytical/dev scenarios opt out** — `SmallOpenEconomy`, the sweeps and homogeneous probes found directly at `SMALLHOLDING`+ (the ruler economy) so they stay steady-state-from-t0 economic probes. |
| **Camp economy** | The Captain rungs (`CAMP`/`COTTAGE`/`HAMLET`) run **only the foraging-band larder economy** (forage-as-improvement — no banks/markets/firms). The **full ruler economy boots at `SMALLHOLDING`** when the head promotes to Ruler — the `SimulationHarness` ruler defaults (three-tier banks, export sector, dynamic firm provisioning) move from *attach-at-founding* to *attach-on-promotion-to-Ruler*. |
| **Class shape** | **Collapse the `Village`/`City` subclasses** into a concrete `Settlement` + `tier` field: `hasDistricts()` = `tier.atLeast(TOWN)`, `isPermanent()` = `tier.atLeast(TOWN)`, the old `instanceof City` ⇒ `tier == METROPOLIS`. `DistrictHost`/`UrbanCenter` become tier-derived capabilities. |
| **Site ceiling** | The site's urban-plot geography **caps the climb**: a 1-urban-plot province tops out at `SMALLHOLDING` (no room for districts); only a multi-plot `city_terrain` site can reach `TOWN`/`METROPOLIS`. Development sets where it sits under that per-site ceiling. |
| **Growth** | **population × days** against each rung's **`iUpgradeTime` read as days**; `METROPOLIS` additionally gates on **≥ 1000 people**. "Population" = total residents (laborer households + pool + nobles + ruler + children), not just the workforce. |
| **Forage** | **Build then work**: camp → build a Civ4 improvement over `buildCost` days → work its yield daily (food→larder, commerce→money, production→build/development work); the improvement persists on the plot; `iCulture` dormant. This **is** the Camp-rung economy above — the explorer forage model is promoted to the bottom-rung economy, not a sideline. |
| **Settle = urbanize plot** | *(forward note, 2026-07-15)* a future **`SettlerCaravan`** founds by **changing the camp plot's feature to urban** — the physical map act of settling. Ties into the urban-terrain→feature idea (drop the synthetic `TERRAIN_URBAN`, make "urban" a feature overlay). |

**Consequence — the phase order below is re-sequenced and is *not* byte-identical.** The A–G
bodies still describe the right mechanisms, but their "byte-identical Phase A" and "additive to
Rank" assumptions no longer hold: realizing the Captain rung, moving the ruler-economy boot to
`SMALLHOLDING`, and the rename land together as a **calibration effort** (the project accepts
non-byte-identical). Analytical scenarios opting out is what keeps the economic smoke tests
meaningful; only the geographic/caravan colonies re-baseline to the climb.

## Context

CivStudio settlements are one of **two fixed tiers** — `Village` or `City` — decided **once at
founding** by a single line (`GameSession.buildSettlement:547`, `isCity = province.city()`) and
never changing. Collapse is a **single binary hinge**: a settled colony drops straight to a
wandering caravan at one uncalibrated floor (`Settlement.DISSOLUTION_WORKFORCE_FLOOR = 10`,
`SettlementLifecycle.update()`), whose own doc already anticipates replacing it with "per-rung
floors." `Settlement.isPermanent()` is an inert flag the docs flag as the intended collapse hook.

The goal is the full C2C settlement progression as a **growth ladder** a settlement climbs and,
on decline, **descends** — unifying three things into one settle⇄unsettle axis:
`caravan ⇄ Camp → Cottage → Hamlet → Village → Town → City` (+ a `SUBURBS` placeholder). A **Camp**
is literally where a caravan sleeps at night; **founding** is a band climbing from Camp; **collapse**
is descending the ladder back to a departing caravan. It also realizes **foraging concretely**: a
camped caravan **builds a Civ4 improvement** (from the now-imported `CIV4ImprovementInfos` data)
over its `buildCost` in days, then **works its yield** each day it stays, choosing the highest-food
improvement when hungry.

The improvement data is already imported (commit 59a14e5: `upgradeType` / `upgradeTime` (days) /
`culture` (dormant) / `techYieldChanges` on `geo.Improvement`). This plan builds the ladder on top.
**Intended outcome:** settlements grow and shrink along a legible ladder; collapse becomes a slope
not a cliff; the caravan camp, founding, and foraging are one mechanic; the food economy gains a
real "camp-and-work-the-land" foraging model.

## Decisions (SUPERSEDED — see "Reconciled decisions (2026-07-15)" above)

> These were the 2026-07-14 decisions. The rows **Found** (found-at-fitting-tier) and **Rank**
> (additive), and the "keep `City` as the one behavioural subclass" note, are **overridden** by the
> reconciled set above (found-at-Camp; unified axis with the head-rank derived; subclasses collapsed
> into the tier field). Kept for provenance.

| # | Decision |
|---|---|
| Model | Data-driven `SettlementTier` enum + a **mutable field**, not a class per rung (a Java object can't reclass; growth is the point). Keep `City` as the one behavioural subclass for now. |
| Rungs | `CAMP < COTTAGE < HAMLET < VILLAGE < TOWN < CITY`, **`SUBURBS` placeholder** included (dormant/future merge). |
| Semantics | Camp = caravan's night camp, no buildings. Cottage = 1 building. Hamlet = limited buildings. Village = founding plot, **0 districts**. Town = **districts capped by town size** (its own population). City = **districts capped by the province's total urban-terrain plots**, and **requires ≥ 1000 people**. |
| Growth | **population × days**, against each rung's **`iUpgradeTime` read as days** (10/20/30/40); the **City rung additionally gates on ≥ 1000 people**. **"Population" = total residents** (laborer households + the peasant pool + nobles + ruler + children), not just the workforce. |
| Found | **Found at a tier fitting the founding people** — a full pool-promoted cohort lands at ~Village, a lone caravan band at Camp — then grows/shrinks. (Not literally Camp-always: spares the standard scenarios a drastic rebalance.) |
| Collapse | **Descends the ladder** (rung by rung), departing as a caravan at the bottom. |
| Rank | **Additive now** — build `SettlementTier` alongside the caravan `Rank` ladder; don't touch Rank (align/retire overlap later). |
| Forage | **Build then work**: camp → build a Civ4 improvement over `buildCost` days → work its yield daily. Worked food → the band's **larder**; the **improvement persists on the plot** (Civ4-like — a settling camp keeps it, a later band/colony can work it). food/production/commerce all get a home (food→larder, commerce→money, production→build/development work); `iCulture` dormant. |

## Implementation — phased (A–C are the additive core; D–G rewire the lifecycle and are calibration-heavy)

### Phase A — `SettlementTier` enum + field, **flatten the `Village`/`City` hierarchy** (foundation, behaviour-preserving)
Revised 2026-07-15 (was "add a field, keep the subclasses"). Reading the code showed the two
subclasses are **almost pure ceremony** — the whole `Village`/`City`/`UrbanCenter`/`DistrictHost`
apparatus expresses just three booleans (district count, permanence, has-districts), all of which
become one-line `tier` predicates. And the unified-axis decision (the caravan band **is** the same
`Settlement` at `CAMP` tier, not a separate type) **removes the speculative-second-implementor
rationale** that justified the `UrbanCenter`/`DistrictHost` interfaces. So Phase A both adds the
enum **and deletes the hierarchy** — net *negative* lines, and still behaviour-preserving because
the founding map reproduces today's outcomes exactly.

- **New** `settlement/SettlementTier.java`: ordered enum `CAMP < COTTAGE < HAMLET < SMALLHOLDING <
  TOWN < METROPOLIS` (rungs **renamed** off the `Rank` collision; `SUBURBS` deliberately absent — a
  future province-merge op, not a linear rung) with `atLeast(tier)`. Per-rung **growth data**
  (`iUpgradeTime` as days) and the `METROPOLIS` ≥1000 gate are deferred to **Phase B** (where they're
  sourced/calibrated) — Phase A is just the ordering + `atLeast`.
- **`Settlement` becomes concrete**: drop `abstract` and `implements UrbanCenter`, make the two
  constructors `public`, add a mutable `SettlementTier tier` field + `getTier()`/`setTier()`. Derive
  the founding tier **in the province constructor**: `province != null && province.city()` ⇒
  `METROPOLIS`, else `SMALLHOLDING` (the bare/analytical constructor ⇒ `SMALLHOLDING`). Rewrite the
  three methods as tier predicates — `hasDistricts()` = `isPermanent()` = `tier.atLeast(TOWN)`;
  `getStartingDistrictCount()` folds in the old `Village` `min(1, …)` via `hasDistricts()`. This
  reproduces today's values exactly (old City ⇒ METROPOLIS ⇒ districts+permanent; old Village ⇒
  SMALLHOLDING ⇒ single centre, impermanent), so it is **behaviour-preserving** (no RNG/economics
  touched). Drop the `@Override`s on `getCityCenter`/`hasDistricts` (no interface to override now).
- **Delete** `Village.java`, `City.java`, `DistrictHost.java` (empty marker), `UrbanCenter.java`
  (single implementor). **`GameSession.newSettlement`**: collapse the `isCity ? new City(…) : new
  Village(…)` fork (duplicated 18-arg call) into one `new Settlement(…)`. **`SimulationHarness`**:
  `colony instanceof City` (the explorer gate, `:1246`) ⇒ `colony.hasDistricts()` (= `atLeast(TOWN)`,
  the reconciled gate); drop the `City` import.
- **Tests:** the four `new Village(…)` call sites (`BankInheritanceTest`, `CavernDaylightTest`,
  `SettlementSolarTest`, `SettlementLifecycleTest`) ⇒ `new Settlement(…)` (drop the one `Village`
  import). New `SettlementTierTest` (ordering, `atLeast`). Full suite green; district counts /
  permanence / explorer-gating unchanged.
- **Not in Phase A** (deferred, they're the non-byte-identical calibration work): the `Camp`
  economy, found-at-Camp, the `Captain` rung, growth. Phase A leaves founding at `METROPOLIS`/
  `SMALLHOLDING` exactly as today — only the *shape* changes.

### Phase B — Civ4 food-box growth + starvation descent ✅ SHIPPED
Revised 2026-07-15 (was a "people-days accumulator"). User direction: **growth must mimic how
Civ4 cities accumulate food to grow**, **a tier of size N needs ≥ N² households**, and **a food
deficit starves the settlement down a rung** (unifying growth and collapse into one food axis).

The growth mechanics are a **faithful port of C2C's** `CvCity::changeFood` / `CvPlayer::getGrowthThreshold`
(investigated from the vendored DLL, `docs`/memory `c2c-city-growth-mechanics`), mapped onto the tier ladder.

- **`SettlementTier`**: `size()` (1-based, `CAMP` 1 … `METROPOLIS` 6), `minHouseholds()` = `size²`
  (the household floor — `COTTAGE` 4, `HAMLET` 9, `SMALLHOLDING` 16, `TOWN` 25, `METROPOLIS` 36),
  `foodToChange()` = **C2C's threshold curve** `BASE + (size−1)·MULT` = `130 + (size−1)·25` (`CAMP` 130
  … `METROPOLIS` 255; C2C's stock `BASE_CITY_GROWTH_THRESHOLD`/`CITY_GROWTH_MULTIPLIER` — its game-speed/
  era multiplier is our deferred scale), `next()`/`previous()`, and `METROPOLIS_POP_GATE = 1000`.
- **`Settlement`**: a Civ4 **food box** (`double foodBox`) banked each `newDay()` (post-`updateLifecycle`)
  by `+= dailyFoodSurplus()`. **`dailyFoodSurplus()`** = necessity **produced** (Σ living `ConsumerGoodFirm`
  with product `"Necessity"` `.getOutput()`) − **eaten** (`totalResidents() × RationSize.FINE.perDay()`),
  then a **food-wastage** curve (`applyFoodWastage`, a simplified port of C2C `CvCity::foodWastage`): surplus
  above `1.0 × consumption` suffers diminishing returns (saturates near `start + 1/0.05`). Helpers:
  `householdCount()` (families, not the pool), `totalResidents()`, `getFoodBox()` (+ pkg-private `setFoodBox`
  test seam), the `maxTier` site ceiling.
- **Grow** while `foodBox ≥ tier.foodToChange()` **and** the next rung `≤ maxTier` **and**
  `householdCount() ≥ next.minHouseholds()` **and** (for `METROPOLIS`) `totalResidents() ≥ 1000` —
  spending the cost, carrying the remainder, but keeping ≥ `FOOD_KEPT_FRACTION` (0.25) of it (the
  granary; provisional until Phase C wires a Granary building). **Shrink** (C2C-faithful): while the
  box is negative, **descend a rung and climb the box back by the lower rung's cost** — falling into
  the previous size's box (keeping the overshoot), *not* reset; at `CAMP` it floors and the existing
  workforce dissolution takes over.
- **Growth-up dormant in production; shrink is live.** Colonies found at `maxTier`, so nothing grows
  up until **Phase D** founds them at `CAMP` — but a starving colony now descends the ladder (the
  growth/collapse unification, previously the separate Phase E). No smoke-test fallout (colonies run
  near food parity). Costs are **uncalibrated** (C2C's speed/era scale is the lever).
- **Verify:** `SettlementGrowthTest` — a well-fed `CAMP` colony climbs to its household/site ceiling; the
  `METROPOLIS` gate holds a sub-1000 well-fed `TOWN`; a deeply starving colony descends to `CAMP` (box
  floors at 0); `applyFoodWastage` banks small surpluses fully but saturates huge ones. `SettlementTierTest`
  covers `size`/`minHouseholds`/`foodToChange`(C2C curve)/`next`/`previous`.

### Phase C — per-tier building & district caps ✅ SHIPPED
- **Building cap**: `SettlementTier.maxBuildings()` (`CAMP` 0, `COTTAGE` 1, `HAMLET` 3,
  `SMALLHOLDING`+ unrestricted) gates `Settlement.autoBuildBuilding` — it skips once the centre
  already holds `maxBuildings()` (after the idempotent already-present check). Render-only
  (auto-build is off by default in the engine), so byte-identical headless runs.
- **District cap**: `getStartingDistrictCount()` is now tier-conditioned on the site's urban
  capacity (`min(province.development(), maxPlots)`): `CAMP` ⇒ 0; sub-`TOWN` ⇒ the single centre
  (`min(1, base)`); `TOWN` ⇒ **population-capped** `min(max(1, residents / RESIDENTS_PER_DISTRICT),
  base)` (a growing town has not filled the province — `RESIDENTS_PER_DISTRICT = 100`, provisional);
  `METROPOLIS` ⇒ the full `base`. **Behaviour-preserving at founding**: production colonies found at
  `METROPOLIS` (⇒ `base`, today's City) or `SMALLHOLDING` (⇒ `min(1, base)`, today's Village), both
  unchanged; the `TOWN`/`CAMP` branches only bite mid-run (growth/shrink).
- **Verify:** `SettlementTierTest.buildingCapRampsUpToUnrestrictedAtSmallholding`;
  `DistrictTypeTest.startingDistrictCountIsCappedByTier` (`METROPOLIS` full, `CAMP` 0, sub-`TOWN`
  centre, `TOWN` within `[1, base]`); the existing City/bare district tests unchanged. Full reactor green.
### Phase D — found low (at Camp) and grow — **the first non-byte-identical phase** (D1–D3 + D4 mechanism SHIPPED)
Rewritten 2026-07-15 to the **reconciled found-at-Camp** decision (superseding the old
"found-at-fitting-tier"). This is the big one: it's where growth-up stops being dormant, and it
**cannot be split** into "found low" without also wiring the Camp economy and the economy-boot —
a Camp running the full 3-tier ruler economy would be incoherent. Sequence it as D1→D5.

> **SHIPPED 2026-07-15 (D1–D3 + the D4 mechanism):** the full found-at-Camp→boot machinery is
> wired and validated end-to-end (`SettlementCampFoundingTest`: a camp founds at `CAMP` under a
> `Captain`, forages, climbs `CAMP→SMALLHOLDING`, and boots the whole ruler economy; a starving
> camp dies). It is gated behind a new `SimulationConfig.foundAtCamp` flag (**default false**), so
> the entire existing scenario/test suite stays **byte-identical** — that flag *is* the analytical
> opt-out (they simply leave it false). Pieces: `agent/Captain.java` (the realized `Rank.CARAVAN`
> head holding the pool as its asset — no `RankLadder` `CARAVAN` factory, avoiding the ennoblement
> trap); `Retinue.camp()` + a `Camp` provisioning mode (the pooled peasants forage the site);
> `Settlement` — a camp food branch in `dailyFoodSurplus()` (`campForageYield − residents×CAMP_RATION`),
> a growth gate that counts the pool below `SMALLHOLDING` (`growthPopulation()`), and an
> `onTierAdvance` callback fired from `grow()`; `SettlementLifecycle` — a camp is alive while its
> foraging band has members (0 laborers by design; it dies terminally when the band is spent — the
> graceful depart-as-caravan is Phase E); `SimulationHarness.foundCamp`/`bootRulerEconomy`
> (mirroring `reFoundStandardColony`, treating the camp as a settling band: `Captain`→`Ruler`, camp
> pool→settled reserve via `createRetinueFromPool`) + a `Settlement.genesisFounding` window so the
> mid-run boot lays its founding farms genesis-style.

> **SHIPPED 2026-07-15 (D5 — live scenario + calibration finding):** found-at-Camp is now **live**
> as a real scenario — `simulation/CampFoundingEconomy` founds a *small band* (`retinueSize 60`) at
> Dhenijansar with `foundAtCamp(true)`, wiring its economy-coupled printers via a new
> `SimulationHarness.setOnEconomyBooted` hook (they can't exist while it is a camp). Run it with
> `exec:exec -Dsim.main=com.civstudio.simulation.CampFoundingEconomy`; `CampFoundingSmokeTest`
> asserts the full cycle. With `CAMP_FORAGE_PER_FORAGER = 0.14` the band climbs `CAMP→SMALLHOLDING`
> in **~6 months** (a sane, gradual pace — the D5 growth-calibration goal), boots the ruler economy
> (3 banks), then — its small pool having drained during the climb — departs as a wandering band,
> completing the settle⇄unsettle cycle cleanly under `-ea`.
>
> **The booted small colony — was short-lived, now RESOLVED 2026-07-16 (subsistence-floor fix).** The
> camp climbs and boots correctly. It *used* to collapse within ~1 month: at the boot every buyer opens
> well-stocked, so market demand is ~0 → the sole genesis farm's necessity price crashes to 0 → its
> revenue, wage budget and output collapse (the labor-share rule + the marginal-profit output rule both
> zero out) → the colony is left with **no food supply**, and once the laborers eat down their founding
> stock (~30 days) they mass-starve and the colony dissolves. **Diagnosed with a day-by-day probe (now
> `CampBootViabilityTest`)** — the failure was on the *supply* side (the farm stopped producing), not
> the price/demand side. **Fix (`ConsumerGoodFirm`/`NFirm`, `ConsumerGoodMarket`):** a **subsistence
> floor** on the colony's *sole* food farm — its wage budget and output never collapse to 0 (people
> farm to eat, not only for profit) — plus a market **price floor** (never clears below 5% of the
> founding reference, so the crash cannot zero revenue and the `÷price` output rule cannot blow up). The
> floors bind **only** for the sole food farm (a multi-farm colony lets the market scale each down, so
> nothing is forced to overproduce). Result: the sole farm keeps producing, the laborers restock the
> now-cheap food, and **`CampFoundingEconomy` (band 60 @ Dhenijansar) now survives the full 25-year run**
> — feeding itself and regenerating through births (61 household fissions; ~40 households + ~60 children
> sustained). `CampFoundingSmokeTest` rebaselined to assert survival, not departure. Deliberately **not**
> flipped: the analytical/closed probes stay `foundAtCamp` false — a 900-person "camp" is not a band.
>
> **Minimum-viable-founding gate (#2), SHIPPED 2026-07-16.** Even with the subsistence floor, a band
> measured **below ~40 residents** boots a colony that still collapses within a few years (bands 16–30
> die in ~3y; 40+ survive). So `Settlement.grow()` gates the `CAMP→SMALLHOLDING` boot crossing on
> `totalResidents() ≥ MIN_VIABLE_BOOT_POPULATION` (40): a too-small band **stays a foraging camp** rather
> than booting a doomed economy — and, unable to grow its slowly-draining pool, eventually departs.
> `SettlementCampFoundingTest.aSubViableBandStaysACampAndDoesNotBoot` pins it. **Design note:** the 40
> line is the natural **settle-vs-return divider** — an explorer levy (`ExplorerProvisioner.DEFAULT_DRAFT_BATCH
> = 20`, the *least-skilled* adults mustered in winter scarcity) forages and *returns* to its origin,
> while a band ≥ 40 is viable enough to *settle*. A future dispatched **settler caravan** (a thriving
> colony sending out a ≥40 band to found a daughter colony — today only collapse-born `SettlerCaravan`s
> wander and re-found) is the natural complement, and would let a sub-viable founding fall back to
> returning home; align `SettlerCaravan.MIN_SETTLERS` (10) up to the viability line when it lands.

> **Prereqs, all shipped:** A (tier field + flattening), B (food-box growth-up + starvation shrink),
> C (per-tier caps). The `grow()` advance loop already climbs `CAMP→…→maxTier`; today no colony is
> founded low enough to use it.

- **D1 — Realize the Captain rung (`Rank.CARAVAN`), pool-as-asset.** Register a `RankFactory` for
  `Rank.CARAVAN` in `RankLadder`: a **`Captain`** household leads the band and **holds the peasant
  pool (`Retinue`) as its asset** (like `Noble.firms`), per `docs/rank-ladder.md` Phase 5. The
  Captain **is the same entity** as the marching/settler-caravan band (a camped band = a `CARAVAN`
  household). **⚠ The trap:** realizing `CARAVAN` means a single-step `RankLadder.promote()` from a
  `Laborer` (`HOUSEHOLD`) would now land on **Captain**, not **Noble** — silently breaking
  ennoblement (which relies on `CARAVAN` being unrealized to skip it, `rank-ladder.md` Phase 2). So
  **keep `RankLadder` for the laborer↔noble ennoblement axis only**; drive the settlement-head
  Captain→Ruler→Mayor changes from **tier crossings** (a pure `tier → Rank` reform), never a
  `promote()` walk. Seams: `RankLadder.register`, `RankFactory`, `Retinue`, `SettlerCaravan`.
- **D2 — Camp economy = the foraging-band larder (no ruler economy).** At `CAMP`/`COTTAGE`/`HAMLET`
  the colony runs **only** the forage-larder economy — no banks, markets, firms, granary or dynamic
  provisioning. Food is foraged into the band's larder (interim: the existing `MarchingCaravan`
  forage/gather; Phase G realizes it as build-then-work improvements). Establishes "what runs below
  Smallholding."
- **D3 — Move the ruler-economy boot to `SMALLHOLDING` (the promotion trigger).** The riskiest
  refactor. `SimulationHarness.foundStandardColony` today attaches the whole ruler economy (gold/
  silver/copper banks, strategic export sector, `Granary`, monthly `DynamicFirmProvisioner`,
  ennoblement, immigration, `installExplorerProvisioning`) **at founding**. Split it: found only a
  Captain-led camp, and **boot the ruler economy when the tier crosses to `SMALLHOLDING`** (Captain
  reforms → `Ruler`). Wire a **tier-advance callback** on `Settlement` (fired from `grow()` on an
  advance) that the harness observes. `foundStandardColony` becomes "found camp" + "on-promotion
  boot."
- **D4 — Flip founding to `CAMP`; analytical scenarios opt out.** `GameSession.newSettlement` (and
  the re-found path `SettlerCaravan.arrive`→`reFoundStandardColony`) founds geographic/caravan
  colonies at `tier = CAMP`. **Analytical/dev scenarios opt out** — `SmallOpenEconomy`, the sweeps,
  and the closed-economy probes (`HomogeneousEconomy`, `TwinSettlementEconomy`) found directly at
  `SMALLHOLDING`+ with the ruler economy (today's behaviour) via a founding-tier flag, so they stay
  steady-state economic probes. (This is what keeps the economic smoke tests meaningful.)
- **D5 — Growth-rate calibration + smoke-test rebaseline.** The food economy runs near **parity**
  (`docs/food-balance.md`), so a Camp-founded colony may never accumulate the surplus to climb —
  calibrate the `foodToChange` scale (C2C's speed/era multiplier lever) **and** the Camp forage
  yield so a healthy colony climbs `CAMP→SMALLHOLDING` at a sane pace and then boots its economy.
  Re-baseline every smoke test that asserts founding tier / early size / collapse timing.

**Risks:** D3 (economy-boot) touches `SimulationHarness` deeply; found-at-Camp couples growth into
the economy so the D5 calibration is real work; D1 must avoid the ennoblement-collision trap. The
project accepts non-byte-identical here.

**Folds in / overlaps the later phases:**
- **Old Phase F (Camp⇄caravan merge) is now D1** — the Captain *is* the band; no separate merge.
- **Phase E (collapse descends) is partly shipped** — Phase B's food-box **starvation shrink**
  already descends tiers. E's remainder: wire `isPermanent()` into `SettlementLifecycle` (a City
  resists depopulation) and the **`CAMP`→depart-as-caravan** hand-off (`dissolveIntoCaravan` fires
  only at the foot).
- **Phase G (forage-as-improvement)** realizes D2's Camp food source (interim forage → build-then-
  work Civ4 improvements).

### Phase E — collapse descends the ladder (CAMP→depart hand-off + descent floor SHIPPED; un-boot deferred)

> **SHIPPED 2026-07-15.** Both collapse regimes now end in a **departing caravan**, without the risky
> economy-teardown:
> - **Head-agnostic `SettlerCaravan.dissolve`** — the band is led by the settlement's *head*: its
>   `Ruler` if it booted a ruler economy, else its `Captain` (a camp that never booted). This is what
>   lets a camp dissolve into a Captain-led band.
> - **`CAMP`→depart-as-caravan hand-off** — camp forage is now a settable field
>   (`Settlement.setCampForagePerForager`, default `0.14`) so a poor site (or a test) can drop it
>   below `CAMP_RATION`; a camp that can't feed its band (a forager starves once its larder is spent)
>   **strikes camp and departs** as a wandering caravan with its survivors (`SettlementLifecycle`
>   sets `dissolving`), instead of dying terminally. A band starved to *nothing* still dies terminally.
> - **Booted colonies floor their starvation-descent at `SMALLHOLDING`** — the sub-`SMALLHOLDING`
>   tiers are only for un-booted foraging camps, so a booted colony never starves down into an
>   incoherent "ruler at a camp tier" state (`grow()` shrink floor keys off `getRuler()`). If it can't
>   sustain a Smallholding, its workforce drains and it dissolves into a caravan. Rebaselined
>   `SettlementGrowthTest` (a starving booted colony descends to `SMALLHOLDING`, not `CAMP`); added
>   the starving-camp-departs case to `SettlementCampFoundingTest`.
>
> **Deferred (the plan's original "demote one tier instead of dissolving" for a booted colony):** the
> **symmetric un-boot** of a booted colony back into a foraging camp (`SMALLHOLDING`→`HAMLET` tears
> down the ruler economy: `Ruler`→`Captain`, dissolve firms/silver+gold banks, pool→camp mode) is a
> large, risky teardown with low ROI given colonies collapse fast (D5) — a booted colony **dissolves**
> at `SMALLHOLDING` instead. Also deferred: **per-rung dissolution floors** + wiring `isPermanent()`
> (kept the flat `DISSOLUTION_WORKFORCE_FLOOR = 10` to avoid collapse-timing churn). The camp
> starvation path is only reachable once forage can dip below the ration (a bad site / a test); the
> flat `0.14` default means a real camp still always sustains and climbs — **Phase G** (site-scaled
> forage) makes bad sites, hence natural camp failure, real.

Original plan (superseded above for what shipped; the un-boot remainder stands):
- **`SettlementLifecycle.update()`** (`:88`): replace the single `DISSOLUTION_WORKFORCE_FLOOR` with
  **per-rung floors** — when living workforce/population falls below the current rung's floor,
  **demote one tier** (City→Town→…→Camp) instead of dissolving; only at the **Camp** rung does the
  existing `dissolveIntoCaravan()`/`SettlerCaravan.dissolve` fire and the band depart. Wire
  `isPermanent()` (the inert hook) so a City resists the first demotions.
- **Verify:** rework the collapse smoke tests — a colony now **steps down the ladder** and departs as
  a caravan at the bottom (was: dissolves at floor 10). `assertDepartedAsCaravan` still holds at the
  end; add a `SettlementDescentTest`.

### Phase F — Camp ⇄ caravan merge (additive to Rank)
- Make a caravan's nightly Camp the `CAMP` rung conceptually: a band that settles (`SettlerCaravan.
  arrive` → `reFoundStandardColony`) re-founds at **Camp/Cottage** (Phase D's found-low), then
  climbs. The re-found path (`GameSession.newSettlement(band,…)` → `reFoundStandardColony`) already
  exists; it just lands at the bottom rung now.
- **Do not touch** the `Rank` ladder (`docs/caravan.md`) — additive; align/retire overlap later.
- **Verify:** `CaravanRefoundTest` — a re-founded colony starts at the bottom rung and grows.

### Phase G — foraging-as-improvement (the concrete forage model) — SHIPPED (settled-camp cut)

> **SHIPPED 2026-07-15 (the found-at-Camp settled-camp realization).** The camp's forage is now
> **site-scaled** and driven by a real, built, persisted Civ4 improvement — so bad ground fails and
> good ground climbs, and Phase E's camp-departure is reachable *naturally* (no test hook). Pieces:
> `Settlement.setUpCampForage` claims one **bare forage plot** from the site (`PlotField.claimBarePlot`
> → `appendPlot`, which sets the founding `center` so the eventual boot inherits it); `campForageYield =
> foragers × campForagePerForager × campPlotFood()` where `campPlotFood()` is the plot's real food yield
> (`Plot.yields()[0]` — terrain + feature + improvement); `advanceCampForageBuild()` (in `grow()`) puts
> `foragers × CAMP_BUILD_PER_FORAGER` of work per day toward the improvement's `buildCost` and, once met,
> `raiseImprovement(HUNTING_CAMP, /*clearFeature=*/false)` on the plot (no-clear, so terrain/feature
> yields still stack — the contract `PlotDevelopmentTest.aForageCampLeavesThePlotWild` pins). Recalibrated
> `campForagePerForager` to `0.10` (per-forager-per-unit-plot-food; ~0.14/forager on typical ~1.4 ground,
> matching the old flat rate) + a `DEFAULT_CAMP_SITE_FOOD` fallback for a province-less camp.
> **Measured** (`CampFoundingEconomy`, Dhenijansar band 60): raises a `HUNTING_CAMP` on day 20 (site food
> 1.0→2.0), which lifts the forage over parity and carries it `CAMP→SMALLHOLDING`, boots, departs on the
> accepted upstream collapse. `SettlementCampFoundingTest.aCampBuildsAForageImprovementAndClimbsOnItsYield`
> asserts the improvement raises the plot food and the camp climbs on real (unprimed) forage. Full
> reactor green.
>
> **Deferred (the wandering-band cut):** the *marching* caravan still uses its bonus-based
> `forage`/`gather` (resource corridors) — this cut realized the improvement model for the **settled**
> found-at-Camp economy (where the flat forage lived), not the mobile band. Choosing among improvements
> by yield/tech and multi-day march-camp persistence remain for a wandering-band cut.

Original plan (the wandering-band framing; the settled-camp realization above supersedes it for what shipped):
- A camped explorer/caravan **builds** the chosen Civ4 improvement on its camp plot via the existing
  `Plot.raiseImprovement(improvement, /*clearFeature=*/false)` (`:348`, already pinned by
  `PlotDevelopmentTest.aForageCampLeavesThePlotWild`), spending the improvement's `buildCost` in days
  — reuse the `BuildProject.advance(units)` progress pattern (`:72`). **Choose** the improvement by
  yield: the highest-`yieldChange(FOOD)` valid improvement when hungry (winter/low larder), gated by
  `prereqTech` (the band's known techs) and the plot's terrain/feature validity
  (`Improvement.validTerrains`/`validFeatures`/`hillsMakesValid`/`freshWaterMakesValid`).
- Then **work it**: each day camped, add `improvement.yieldChange(FOOD)` (× a rate) to the band's
  **larder** (commerce→money, production→build/development work). The **improvement persists on the
  plot** (`raiseImprovement` is durable, plot state stays after the band leaves) — Civ4-like: a
  settling camp keeps it, a later band/colony can work it.
- **Camp persistence:** today `MarchingCaravan.releaseCamp()` (`:478`) strikes the camp every dawn —
  add multi-day camp persistence so a `buildCost`-day build can accumulate (a `Camp` occupant that
  survives across nights while the band chooses to stay). This replaces/augments the current
  bonus-based `forage`/`gather` (`MarchingCaravan:535/565`).
- **Verify:** `ForageImprovementTest` — a hungry camped band builds a food improvement over N days and
  its larder grows from working it; a well-fed band picks differently.

## Reused seams (don't reinvent)
- `improvements.json` via `TerrainRegistry` — per-tier `upgradeTime`/`upgradeType` (already imported).
- `getStartingDistrictCount()` / `getDistrictPlots()` (`Settlement.java:734/680`) — district-cap seam.
- `autoBuildBuilding` (`:1450`) — the single `Building` placement chokepoint for the building cap.
- `Plot.raiseImprovement(imp, false)` + `Improvement.buildCost`/`yieldChange` — forage-by-improvement.
- `BuildProject.advance`/`isComplete` (`BuildProject.java:72/79`) — the build-progress accumulator pattern.
- `SettlementLifecycle.update()` + `dissolveIntoCaravan()` + `SettlerCaravan.dissolve` — collapse/descent.
- `GameSession.buildSettlement:547` / `newSettlement(band,…)` + `reFoundStandardColony` — found-low + merge.

## Risks
- **Found-at-fitting-tier + collapse-descent (D, E) rebalance the lifecycle** — a full founding
  cohort still lands at ~Village (so colonies are **not** started tiny), but growth couples into the
  economy and collapse falls gradually; this re-baselines the founding-tier / collapse smoke tests.
  Not byte-identical (the project accepts this). Land A–C first; treat D–G as a calibration effort.
- **Growth calibration** — mapping `iUpgradeTime` days + the population×days rate + the ≥1000 City gate
  to a colony that actually climbs at a sane pace is tuning work (like the food-balance levers).
- **Forage/camp persistence** rework touches the march loop; keep the directed-march caravans working.

## Verification (end-to-end)
- Per-phase unit tests above; after each phase run `mvn -o test` (full reactor: engine + server).
- After D/E, drive the default Dhenijansar colony to observe it **climb** (found-low → grows) and,
  on decline, **descend** rung by rung before departing as a caravan (a throwaway probe like the
  explorer survival probe, then deleted).
- Sanity-check the web map still renders (the tier is render-additive; districts unchanged for a City
  at CITY tier).