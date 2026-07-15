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

### Phase B — population×days growth accumulator + tier advance
- **`Settlement`**: a new `double development` accumulator, ticked once per `newDay()` by
  `+= totalResidents` (people-days). **`totalResidents`** = a new helper summing living laborer
  household members + peasant-pool size (`Retinue.size()`) + nobles + ruler + children (not just
  the `livingLaborerCount` the collapse metric uses). No existing accumulator exists — greenfield
  (today only the static `Province.development()` feeds `getStartingDistrictCount()`).
- Advance `tier` when `development` crosses the next rung's `upgradeTime` (days) threshold; the
  **CITY rung additionally requires population ≥ 1000** (`SettlementTier.CITY_POP_GATE`). Advancing
  is a field change (log it), never a new object.
- **Verify:** `SettlementGrowthTest` — drive a colony and assert it climbs rungs on schedule; the
  City gate holds a sub-1000 Town back.

### Phase C — per-tier building & district caps
- **Building cap**: gate `Settlement.autoBuildBuilding` (`:1450`, the single `Building`-placement
  chokepoint) by `tier` — Camp 0, Cottage 1, Hamlet a small N, Village+ unrestricted center.
- **District cap**: tier-condition `getStartingDistrictCount()` (`:734`, today
  `min(province.development(), maxPlots)`): **Town** ⇒ capped by town size (population-scaled),
  **City** ⇒ the province's total urban-terrain plots (`getMaxPlots()`/urban plots), sub-Village ⇒ 0.
- **Verify:** extend `DistrictTypeTest`; a Town's district count grows with its population, a City's
  equals the site's urban-plot capacity.

### Phase D — found at a fitting tier, then grow
- **`GameSession.buildSettlement`** (`:547`): stop founding directly at `City`/`Village` by
  `province.city()` alone. Instead set the initial `tier` from the **founding population** (a
  standard pool-promoted cohort → ~`VILLAGE`; a lone re-founding caravan band, below `MIN_SETTLERS`,
  → `CAMP`/`COTTAGE`), and a **`maxTier`** from the site (single-urban-plot province caps at
  `VILLAGE`; a `city_terrain` province can reach `CITY`). Growth (Phase B) climbs toward `maxTier`.
- **RISK (moderate, not brutal):** because a full founding cohort still lands at ~Village (not a
  1-plot Camp), the standard scenarios are **not** started tiny — the rebalance is gentler. It still
  re-baselines any smoke test asserting the founding tier / early size, and couples growth into the
  economy. Sequence **after** A–C; treat as a calibration change (project accepts non-byte-identical).

### Phase E — collapse descends the ladder
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

### Phase G — foraging-as-improvement (the concrete forage model)
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