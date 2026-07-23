# Plan: settlement build queue (hammers, housing, ruler buildings)

**Status:** PLAN (2026-07-23). This is **P5 of the plot-working economy**
([`docs/plot-working-plan.md`](plot-working-plan.md)) grown into its own feature: wire the two dormant
`Plot.yields()` channels — **production** (hammers) and **commerce** — into a daily **occupation choice**
for households, a **housing** need, and a Civ4-style **build queue** that raises real (imported C2C)
buildings at the city center. Companion to [`docs/c2c-building-import.md`](c2c-building-import.md) (the
catalog this consumes), [`docs/plots.md`](plots.md) (the plot model, *Buildings vs. improvements*), and
[`docs/calendar.md`](calendar.md) (the rest-day couplings the parity rule preserves).

## The model in one paragraph

Each day a household chooses **one occupation** (binary, whole-household): walk to the center and sell
labor on the labor market (wage in coin, as today), or **stay on its home plot and work it** — producing
the plot's **hammers** and **commerce** locally, on top of the subsistence **food** that already flows
unconditionally (P1–P4). Hammers go to the household's **own build project** first (a house, then up to
two other buildings); a household with nothing to build **donates its hammers to the ruler's main build
queue**, which constructs Civ4-style buildings on the **center plot** (unlimited there; up to two
ruler-owned regular buildings on any other plot). Housing is the first need: a **homeless** household
(owning no housing-kind building) stays home to build one — *if its larder can carry it* — and a finished
house is the **prerequisite for weddings and household fission**. The labor market gains a real **reservation wage**:
firms must outbid the value of a home-worked day.

## Decisions (user, 2026-07-23)

| # | Decision |
|---|---|
| **Occupation choice** | **Binary per household per day** — the whole household either goes to market or works its plot. No member-level split. |
| **Decision rule** | **Reservation wage + unhired fallback.** Housed households compare yesterday's realized wage against the plot's commerce value in `act()` (hammers uncounted in the comparison — they are the donation byproduct); below the threshold → stay home. A household that chose the market but was left **unhired** by the wage-budget allocation works its plot that day anyway (no wasted days). **Homeless** households stay home to build **only when they have enough food** — survival wages beat house-building when the larder is short. |
| **Commerce destination** | **Copper coin straight into the household's purse**, outside the market, split by plot load like food (`commerce × rate ÷ load`). **Untaxed** for now — the informal economy; the tax advantage of staying home is accepted (the ruler's counter-incentive is wanting hammers donated). |
| **Scaling parity** | **Full parity with firm labor**: rest days gate plot-working exactly like firms (no holiday loophole — the calendar couplings are calibration-sensitive), daylight scales output, a skill scales and trains on plot days (CONSTRUCTION for hammers; commerce skill decided at implementation). |
| **Queue source** | **Household surplus hammers only.** No center-plot or firm-plot trickle: a fully-employed colony builds nothing — the ruler has a real stake in the wage/home-working balance. |
| **Housing** | A household with no housing building is **homeless**. Housing is **exempt from all build limits and stacks freely** on one plot; it is the only *non-regular* kind for now. A finished house is a **prerequisite for weddings/fission**. Housing is the **imported C2C `BUILDING_HOUSING_*` line** (bark/bone/stone/mud huts → … → arcologies; `<bAutoBuild>1` in the source, real art, tech-gated with `<ObsoleteTech>` + a `ReplacementBuildings` upgrade chain) — in C2C these are auto-granted and so carry **no `iCost`**; since eos households hammer-build them, **housing costs are hand-authored in studio**. The Strapi building collection type is incomplete today and can't represent these rows — completing it is in scope (B2). |
| **Build limits** | **Two regular buildings per owner per plot.** Households build **only on the plot they occupy**, never at the center. The **center plot is ruler-only and unlimited** (the full Civ4 city). The ruler may also place up to two regular buildings on any other effective plot. |
| **Queue brain** | **(a) heuristic driven by imported C2C AI build weights**, plus a **new player verb** (a build-order command on the `CommandLog`) as the override seam. |
| **Player interrupt** | (user, 2026-07-23) **Empty queue + a human player seated → the session pauses and a modal offers the next building(s)** — the Civ4 what-to-build-next interrupt. The AI-weight heuristic runs only for unattended colonies. The pause is client-side clock state, not sim state — the chosen `queue_build` command is tick-stamped on the log, so replay stays deterministic. Scoped to solo-seated sessions; shared/ranked (lockstep) behavior is an open question (one player's empty queue must not freeze the world — likely a notification + heuristic fallback there). |
| **Cold start** | **Optimism prior** — a household with no realized-wage history goes to market (the reservation wage binds only once it has been paid at least once). Founding behavior matches today's; the unhired fallback catches the over-optimists; no new RNG stream. |
| **Stickiness** | The occupation choice has a **hysteresis band** (the dynamic-firm-provisioner pattern): a household switches occupation only when the wage/plot comparison crosses a threshold margin in either direction — no daily flip-flop, no oscillating market-clearing spiral. One knob. |
| **Obsolescence** | When a household's housing goes obsolete (C2C `ObsoleteTech`, e.g. bark huts at Surveying): **still sheltered, but the wedding/fission gate re-applies** until it builds current housing — soft modernization pressure, demographically throttled, no eviction wave. |
| **Noble regular buildings** | **Housing only for nobles this feature** — their 2-regular-building allowance stays dormant until nobles own land (the estate system's land/property spoke is where noble building belongs). |
| **Acceptance bar** | Before `buildEconomy` flips default-on, the opt-in calibration scenario must prove: the founding **housing wave completes** within a bounded period, **weddings/fission resume** after it, **firms retain enough labor** to function (no home-working market collapse), and colony survival matches the current home-plots colony — all asserted in its smoke test. |
| **Hammer price** | Commissions are **billed at cost** through the BuilderFirm's existing sponsor contract (near-zero-profit conduit, wages flowing ruler-ward) — no margin, no price discovery: a sole builder's "market price" is a monopoly markup in costume, and a margin's distributional effect would hinge on the firm's ownership (an estate-system question). The **scaffold cap is the real price**: the palace competes with plot-opening in one queue, so elite construction costs the colony growth — labor-constrained, period-correct. **Documented as intended**: the ruler's own commission is a coin **wash** (sponsor wage bill → flows back to the ruler as peasant patron), so housing-first binds on cash-flow timing + scaffold opportunity cost, not net wealth; **noble** commissions are *not* a wash — a quiet aristocracy→crown wealth-recycling channel leaning against rentier accumulation. Price discovery arrives if/when a second builder ever exists. |
| **Coin origin** | Home commerce is **minted at the copper bank** (the bank credits the household's account) — the monetary parallel of plot food being created outside the market. Money supply grows with home production; the inflation printer instruments it. |
| **Uniqueness** | (revised, user 2026-07-23) **At most one of each building id per PLOT** — the Civ4 one-of-each rule applied at plot granularity, so a household can own a second granary on its plot while the ruler's stands at the center. Duplicates across plots are legal; duplicates on one plot are not. **Wonders**: the wonder files (Great/National/Group) turn out never to be scanned by the importer, so there are no wonder rows to exclude — moot until someone imports them. |
| **Participants** | **Laborer households** face the occupation choice and self-build with their own plot hammers. **Nobles and the ruler also need housing, but commission the `BuilderFirm`** (revised, user 2026-07-23): the firm's hired labor converts to hammers — a **separate hammer source** — applied to the commissioner's housing project and paid in coin. The **peasant pool is exempt** from the wedding/fission gate so replacement-renewal keeps working. |
| **Unification** | **One `BuildProject` abstraction** — the existing sponsor-billed project model (BuilderFirm's land-clearance queue) generalizes: a project accepts work-units from any source — plot hammers (household self-build), donated hammers (the ruler's center queue), or BuilderFirm build-units (commissioned elite housing, billed as today). Plot-opening stays a project type alongside buildings; one progress/completion model, three funding paths. |
| **Firm buildings** | **Not in this feature.** Building owners are households and the ruler only; firm-owned buildings (a forge boosting the smithy's TFP) arrive with the building-effects feature. The owner field stays open to them. |
| **Fiscal priority** | **Housing first** (user, over the science-first recommendation): the ruler pays its BuilderFirm housing commission **before** funding science when the treasury runs thin — the vain court. **Known risk**: slows the tech tree in the calibration-sensitive early game; the B3 instrumentation must watch tech pacing and the housing-cost calibration keeps the palace affordable enough not to stall research for years. |
| **Surface** | All web visibility folds into **B6**: buildings on plot hover + the colony/caravan composition rails, the queue panel, SimLog cards. Engine phases stay headless-testable via CSVs. |
| **Minor defaults (by precedent)** | Buildings are **durable** — they survive colony death on the plot, like the camp's HUNTING_CAMP improvement (a later colony inherits them; uniqueness then counts inherited stock). **Fission** requires the *parent* household housed; the child household starts homeless (the next construction wave). A housed household never builds a second house. Donated hammers with **no queued project evaporate** daily (Civ4 use-it-or-lose-it; rarely binds — the heuristic auto-fills unattended queues and a seated session pauses on empty). |
| **Catalog** | Building **cost and all other properties are imported** from C2C at runtime (today only the web sees them), including the AI build weights. Effects stay **dormant data** except the HOUSE gate. |
| **Out of scope** | Tier growth stays **food-only** (hammers never feed the ladder in this feature). The camp's `advanceCampForageBuild` is untouched. Building *effects* (granary, happiness, …) are a later feature. |

## Current state (what exists to build on)

- **`Plot.yields()`** already returns `[food, production, commerce]`; indices 1–2 are consumed by nothing.
  `Plot.buildings()` / `addBuilding()` / `hasBuilding()` ship as an empty data model waiting for a trigger
  (`settlement.Building`, an id-only record keyed to the tech tree's `Unlock` targets).
- **Home plots (P1–P4)** — every landed household farms a shared home plot daily (`FoodEconomy`,
  Malthusian ÷load split), flag-gated on `SimulationConfig.homePlots`, opted in by `CampFoundingEconomy`.
- **`Laborer`** tracks its realized `wage` from the previous clearing — the reservation-wage comparand
  exists already.
- **Labor market** — wage-budget allocation; skill-, daylight- and commute-scaled output; `DayType` gates
  firm operation. The parity rules mirror all of this onto plot days.
- **Building catalog** — 1,865 C2C buildings in `buildings.json` (id, name, prereq tech(s), advisor
  category, art, `cost`), exported by `BuildingInfoExporter`, delivered via the studio world-bundle chain
  (`WorldSource`); the engine consumes only the tech tree's `Unlock(BUILDING_*)` tokens today. **No AI
  weight is exported yet** (`<iAIWeight>` in the C2C XML) and the engine has no runtime catalog.
- **`UnitCatalog`** — the pattern to copy for the runtime `BuildingCatalog` (parse a bundle JSON via
  `WorldSource`, natural-key ordering per the world-bundle sort rule).
- **Precedent for work-accrual**: `FoodEconomy.advanceCampForageBuild` (forager-work accumulates against
  an `Improvement.buildCost`, then raises it).
- **Command spine** — tick-stamped `CommandLog`, admin-gated `submit_command` MCP tool, snapshot streaming:
  the player-verb seam is fully built, only the new command type is needed.

## Flag

A new **`SimulationConfig.buildEconomy`** (default **false**), requiring `homePlots`. Rationale: the
reservation wage changes labor *supply* for every home-plots colony, so riding `homePlots` alone would
perturb `CampFoundingEconomy`'s shipped balance; a separate flag keeps the whole existing suite
byte-identical and lets one scenario opt in for calibration. Folding the flags together is a later
default-flip decision.

---

## B1 — The occupation choice (hammers + commerce day)

**Status: BUILT 2026-07-23.** As-built: `settlement.BuildEconomy` (sibling of `FoodEconomy`, present
only when the new `SimulationConfig.buildEconomy` flag is on — enabled by the harness constructor),
the occupation state + `updateOccupation` on `Laborer` (wage memory, optimism prior, hysteresis via
`BuildEconomy.HYSTERESIS_BAND` 0.25 UNCALIBRATED), `LaborMarket.daylightFactor` extracted as the
shared day-length scaler + `wasHiredLastClear` (hired-ID bookkeeping) feeding the unhired fallback
(`Settlement.newDay`, right after market clearing), commerce minted via a bare
`Bank.credit(..., SECIC)`, CONSTRUCTION + COMMERCE trained on plot days, `HammerPrinter`
(monthly plot/market/fallback day counts + hammer/commerce flows), and the `HammerEconomy` scenario
(a one-flag contrast to `CampFoundingEconomy`; registry key `hammers`, honored by the server host's
CAMP-shape flag read).

**Calibration finding (2026-07-23), for B3+ to act on:** on real ground the **commerce channel of raw
farmland is ~0** (Civ4 commerce needs rivers/coast/cottage-type improvements, none of which exist on
shared home plots) — so the reservation wage, whose comparand is deliberately commerce-only, **never
pulls a household home**; every plot day in the calibration run comes from the **unhired fallback**
(the unemployed working their land — economically coherent for the era, and the hammers still flow).
The choice machinery is built and armed but stays dormant until commerce channels open (P5
improvements). The B3 housing project changes this independently: a homeless-and-fed household stays
home for the *house*, not the commerce, so B3 is where chosen plot days first appear.

**Goal.** The daily binary choice and the two new yield flows, ending in a per-household **hammer
balance** and copper commerce income. No buildings yet — hammers accumulate (donation flows to a stub
`Settlement` hammer sink so the numbers are observable in CSVs).

**Steps.**
1. `Laborer` gains the day-choice in `act()`, before posting labor: homeless-and-fed → home;
   else reservation wage (yesterday's `wage` vs the plot's expected commerce value) → home or market,
   with the **optimism prior** — no wage history yet → market (so founding day matches today's
   behavior and wages get discovered) — and a **hysteresis band** (the provisioner pattern): the
   household switches occupation only when the comparison crosses a threshold margin in either
   direction, so the choice can't oscillate day-to-day into a market-clearing spiral. A market-day
   household posts its labor offer as today.
2. **Unhired fallback**: at clearing, a household that offered labor but received no allocation counts as
   a plot day (the plot yields accrue then, consistent with deferred settlement).
3. A plot day yields `production × dayFactor ÷ load` hammers and `commerce × dayFactor ÷ load` copper,
   where `dayFactor` folds the parity scalers: `DayType` gate (0 on rest days — a rest day is nobody's
   loophole), daylight hours, and skill (CONSTRUCTION scales + trains on the hammer component). Food is
   unchanged and unconditional.
4. Commerce coin is **minted at the copper bank** into the household's account (no market, no tax, no
   counterparty — the monetary parallel of plot food; the inflation printer watches the money-supply
   growth). Hammers go to the household hammer balance; with no project (B3+) the whole balance donates
   to the colony sink daily.
5. **Scope**: only `Laborer` households choose — nobles, the ruler and the pool are untouched (housed
   by status / exempt, per the Participants decision).
6. A `HammerPrinter` (or columns on an existing monthly printer): hammers produced / donated / spent,
   plot-day vs market-day household counts — the calibration instruments.

**Seams.** `Laborer.act` + the clearing hook, `Plot.yields()[1..2]`, `FoodEconomy`-adjacent placement
(a sibling `BuildEconomy` helper on `Settlement`, mirroring the `FoodEconomy`/`PlotField` extraction
pattern), `Skill.CONSTRUCTION`, `DayType`, the solar clock.

**Risk / tests.** Medium — the reservation wage is the calibration cliff: too high and firms starve, too
low and it never binds. Tests: a household on a rich plot with a lousy wage stays home; a fat wage pulls
it to market; unhired households still produce; rest days produce nothing; flag-off byte-identical.

## B2 — Complete the building content type + runtime catalog (the import)

**Status: BUILT 2026-07-23** (fixture regen + full-suite verification pending at time of writing; see
the as-built notes below).

**Goal.** The studio building collection type carries the **full property set** (it is incomplete today —
it can't even represent the housing line), the **C2C `BUILDING_HOUSING_*` rows are imported** with
hand-authored costs, and the engine gains a runtime `BuildingCatalog` reading it all through `WorldSource`.

**As-built discoveries (2026-07-23), superseding details below:**
- **A housing import chain already existed** for the old auto-build-at-center design:
  `geo.export.HousingExporter` → `/housing.json` (all 56 `BUILDING_HOUSING_*` rungs with the full
  prereq legs — population, fresh water, material bonuses, feature/terrain any-ofs — ladder structure
  and dormant effects) → the studio `housing` collection type → seed/bundle/verify, but **no runtime
  consumer** and no name/cost fields (the "incomplete, can't see houses" state). Reconciliation: **two
  catalogs with a clean split** — `building` stays the flat catalog (gains `obsoleteTech`, `autoBuild`,
  `kind: housing`, `flavors`, `replacedBy`), while the rich `housing` type is the housing catalog B3's
  targeting reads (gains `name`, `cost`, `authoredCost`).
- **`iAIWeight` doesn't exist in C2C's building XML** — the AI weight channel is **`<Flavors>`**
  (~1,100 entries: GROWTH/GOLD/MILITARY/CULTURE/RELIGION/PRODUCTION/SCIENCE/ESPIONAGE), exported as a
  per-building `flavors` map. The C2C DLL applies them as a **dot product with leader personality
  weights, added flat** to a base value, then time-discounts by `value × 100 ÷ (turns + 3)`
  (CvCityAI research — see B4).
- **`bAutoBuild` is not a housing discriminator** (it also marks C2C's civic/pest/resource bookkeeping
  rows); the id prefix `BUILDING_HOUSING_` is. The bookkeeping autobuilds import costless → unbuildable,
  as planned. **The wonder files are never scanned** by the importer, so the `kind: wonder` concern is
  moot — no wonder rows exist to exclude.
- **Housing costs**: hand-priced defaults live in `HousingExporter.HAND_COSTS` (32 in-horizon rungs
  priced, `HOMELESS` + past-horizon rungs costless → unbuildable), exported as `cost`; studio's
  `authoredCost` (never seeded — it is not in the seeder's scalar spec, which *is* the preservation
  contract) overrides per rung. Effective cost = `authoredCost ?? cost`, on both
  `HousingBuilding.effectiveCost()` and `BuildingInfo.effectiveCost()`.
- Engine consumers: `settlement.BuildingCatalog` (flat, for the B4 brain) and
  `settlement.HousingCatalog` (rich, for B3 targeting), both `UnitCatalog`-pattern
  (`WorldSourceCache`, lenient load — unseeded store ⇒ empty catalog ⇒ safely inert).

**Steps.**
1. `BuildingInfoExporter`: export `<iAIWeight>`, `<bAutoBuild>` (→ `kind: housing` — the housing line is
   exactly the autobuild set we keep; audit for other autobuild non-housing rows and decide keep/drop),
   `<ObsoleteTech>`, and audit for any other property the queue brain needs — effects stay opaque data.
   **Check the current keep-filter**: housing rows must survive it (they have a `<PrereqTech>` but often
   no `<Advisor>`; today no-advisor rows are kept uncategorized — verify none of the line is dropped).
   Re-run the export.
2. Studio: **complete the building content type** — cost, AI weight, prereq tech(s), obsolete tech,
   advisor category, `kind` (`housing` | `regular` | `wonder` — wonder rows stay in the catalog for the
   web tech tree but are skipped by every build brain), and an **`authoredCost`** field the seed never
   touches. Re-seed the C2C rows.
3. **Hand-author housing costs** in studio (`authoredCost` on each `BUILDING_HOUSING_*` row — C2C has no
   `iCost` for them since they were auto-granted). The seed contract: imported fields are overwritten on
   re-seed, `authoredCost` (and any future hand-set field) is **preserved** — cf. the economy-matrix
   absent-≠-empty contract. Effective cost = `authoredCost ?? iCost`.
4. Bundle + `FixtureWorldSource` fixture regenerated (fixture includes the housing line with costs, so
   `mvn test` sees the full catalog offline). **Ordering**: the catalog read must pass a natural-key
   sort (world-bundle rule).
5. Engine `BuildingCatalog` (pattern: `UnitCatalog`): parse `/buildings.json` via `WorldSource` — id,
   effective cost, prereq/obsolete tech, AI weight, category, kind. Tech availability = the existing
   `Unlock(BUILDING_*)` tokens (the exporter must now also emit unlock tokens for the housing line if
   the current pass excluded autobuilds); a costless row is unbuildable (safe default).
6. Deploy note: content-type change + seed on prod before the server ships (absent ≠ empty contract;
   server falls back to an empty catalog = no houses, no queue — safe but inert, so B3+ scenarios
   require the seeded catalog).

**Seams.** `settlement.export.BuildingInfoExporter`, studio content type + seed, `data.WorldSource`,
new `settlement.BuildingCatalog`, `TechTree` unlock tokens.

**Risk / tests.** Low mechanically, but it is the cross-repo slice (exporter → studio → bundle → fixture
→ engine). Tests: catalog parses the fixture (incl. HOUSE); unknown fields ignored; deterministic order;
eos rows survive a re-seed; a tech's unlock set matches the catalog's prereq view.

## B3 — Housing

**Status: BUILT 2026-07-23 (B3a household self-build + gate; B3b elite commissions).** B3b as-built: a building-legged `BuildProject` (owner=sponsor), `PlotField.queueCommission` behind plot-opening (scaffold-cap competition literal), completion raises the owned house at the center without appending a plot, `HousingCatalog.bestAvailable` elite targeting, the daily commission driver in `BuildEconomy` (deduped against the live queue), and `housedForGate` overrides on Noble/Ruler via `AbstractHousehold.hasCurrentHouse()`. As-built for B3a: `Building` record → owner-carrying class (`ownerId`
nullable = orphaned/inherited; the tech auto-build path places unowned render-state buildings);
`Laborer` housing project (`applyHammersToProject` — hammers pay the cheapest available rung,
overflow donates; completion raises the owned house on the home plot), **adoption** of orphaned
houses (dead owner → `Settlement.newDay` orphans before releasing the plot → the successor seated on
the same plot adopts — succession inherits shelter, and so does a later colony on durable ground);
the **homeless-and-FED rule** in `updateOccupation` (larder ≥ 2 days' rations → stay home and build;
hungry → market); the **gate** as `AbstractHousehold.housedForGate()` (default true; `Laborer`
overrides — landed on a build-economy colony needs a *current* house; landless exempt) checked in
`seekSpouseIfSingle` + `SocialMobility.tryFission`; obsolescence re-gates without evicting
(`HousingCatalog.isCurrent`, lenient on unknown rungs).

**Finding (2026-07-23):** the warm-start baseline **never applies `Unlock` token effects** for
pre-known techs (`getGrantedTechTokens()` is empty at founding — also why early unit embodiment is
null until live research). Housing targeting therefore reads `prereqTech ∈ knownTechs` straight off
the catalog (`HousingCatalog.cheapestAvailable(knownTechs)`) instead of tokens. The token warm-start
gap is a known follow-up, out of this feature's scope.

**Goal.** The homeless state, the housing build project (from the B2 catalog's `BUILDING_HOUSING_*`
line), and the wedding/fission gate.

**Steps.**
1. `Laborer` gains a **build project** slot: homeless → an implicit housing project on its occupied
   plot, targeting the **cheapest unlocked, non-obsolete housing-kind building** the tech tree allows
   (bark huts at the start; the era ladder comes free with tech). Plot-day hammers pay into it (overflow
   beyond completion carries, Civ4-style); completion calls `Plot.addBuilding(...)` tagged with the
   owning household. **Obsolescence** (C2C `ObsoleteTech`): an obsolete-housed household stays
   sheltered — no eviction — but the wedding/fission gate **re-applies** until it builds current
   housing (its build-project slot reopens), a soft modernization pressure spread over time. The C2C
   `ReplacementBuildings` chain itself (which rows supersede which) is imported data the targeting
   reads later; for now "current housing" = any unlocked, non-obsolete housing-kind row.
2. **Ownership**: `Plot`'s building list gains an owner dimension (ruler vs household id) — the limits in
   B4/B5 and "a household's own house" both need it. A dead household's buildings: houses pass to the
   successor household (inheritance seam — the replacement spawns housed), other buildings stay with the
   plot for the successor.
3. **The gate**: weddings and fission require the household to be housed (`MarriageMarket` / fission
   check in `SocialMobility`) — *parent* housed for fission; the child household starts homeless (the
   next construction wave). **Pool weddings are exempt** (else replacement-renewal chokes). This is the
   feature's demographic tooth — a housing shortage now throttles growth, which also self-paces the
   founding construction wave.
4. **Elite housing via the `BuilderFirm`**: nobles and the ruler need housing too, but don't work plots
   — they **commission the BuilderFirm** through its existing machinery: a housing commission is just a
   **new `BuildProject` type** in the firm's queue (today's projects are land clearances), sponsor-billed
   to the commissioning noble/ruler **at cost** exactly like a plot request (the Hammer-price decision:
   no margin, no discovery — the **scaffold cap is the price**, palace vs plot-opening competing in one
   queue), build-units applied per the scaffold cap, wages flowing to the ruler as today. The ruler's
   own commission is a documented coin **wash** (its wage bill returns as patronage income); a noble's
   is a real noble→crown transfer. **The unification decision**: `BuildProject` generalizes to
   accept work-units from any source (plot hammers / donated hammers / build-units) — one progress and
   completion model across household self-builds, the center queue and commissions.
   **Fiscal priority — housing first**: the ruler pays its own housing commission *before* funding
   science when the treasury is thin (the vain court); the instrumentation must watch early tech pacing,
   which this deliberately risks.
   Defaults (open to revision): elite housing stacks on the **center plot** (housing is limit-exempt;
   the court lives in town), the ruler commissions first (the palace precedent), an ennobled household
   starts homeless and commissions its manor — the BuilderFirm's capacity is the aristocracy's
   demographic throttle, as plot hammers are the laborers'.
5. Homeless-and-hungry goes to market (B1 rule); the housing wave self-throttles in a poor colony.

**Seams.** `Building` (owner), `Plot.addBuilding`, `Laborer`, `BuildingCatalog`, `BuilderFirm` (the
elite hammer stream), `SocialMobility` (fission gate + ennoblement), the marriage market, succession in
`Settlement.newDay`.

**Risk / tests.** Medium — the fission gate touches the demographic engine; verify a housed colony still
weds/fissions at the prior rate and an unhoused one stalls (a new assertion, not a regression). Tests:
house completes at cost; overflow carries; successor inherits the house; wedding blocked while homeless.

## B4 — The ruler's main build queue

**Status: BUILT 2026-07-23.** As-built, all in `BuildEconomy`: `spendDonation` pays the active item
from every donation (overflow carries into the next pick; a donation with nothing buildable
evaporates), the **brain** `pickNextBuilding` (regular + buildable + non-autoBuild + prereq-known +
non-obsolete + not already at the center; score `(1 + flavorSum) × 100 ÷ (cost + 3)` — the C2C DLL
shape with a flat-1 personality; deterministic, no RNG; the B6 player-fed strategy replaces this
seam for seated colonies), completion raises the **ruler-owned** building at the center with a
VILLAGE-rank SimLog event, `BUILD_COST_SCALE = 1.0` UNCALIBRATED, queued/completed counters
instrumented and asserted in `HammerEconomyTest`.

**Goal.** Donated hammers construct real buildings at the center.

**Steps.**
1. The center queue rides the **unified `BuildProject`** model: an ordered list of building projects
   (catalog ids); one active item; daily donated hammers pay in as its work-units; completion →
   `center.addBuilding(...)` (ruler-owned); overflow carries to the next item. Same
   progress/completion code path as BuilderFirm projects and household self-builds.
2. **The brain (a)** — for **unattended** colonies: when the queue is empty, pick the highest-scoring
   affordable building the tech tree has unlocked and **the center plot doesn't already have**
   (uniqueness is per-plot, inherited stock on that plot included). Scoring follows the C2C DLL
   (memory: `check-c2c-dll-source`; CvCityAI research 2026-07-23): a base value + the **flavor dot
   product added flat** (leader personality weights × building flavors — eos can start with a flat
   1-weight personality, i.e. `flavorSum()`), then the **time discount** `value × 100 ÷ (buildDays +
   3)` — the single line that stops early wonder-stacking — and optionally a small deterministic
   ±25% variety factor (salted RNG stream if used). Costless/autoBuild rows are excluded from
   scoring, exactly as C2C's `buildingMayHaveAnyValue` does. A **player-seated** colony instead
   raises the B6 interrupt (pause + modal); in the headless engine the seam is just a `QueueBrain`
   strategy — heuristic vs command-fed. Center plot: unlimited count. Ruler buildings on non-center
   plots (≤2 each) are *allowed* by the data model but the heuristic targets only the center for now
   — outer placement arrives with the player verb.
3. **Cost scale**: a `BUILD_COST_SCALE` divisor mapping C2C `iCost` to hammer-days — calibrated so an
   early building costs a small colony roughly a season, not a decade. Uncalibrated constant to start;
   the B1 printer instruments it.
4. SimLog events (queued / completed) — they surface as notification cards for free.

**Seams.** `BuildEconomy`/`Settlement`, `BuildingCatalog`, `TechTree`, `PlotField.center`, SimLog.

**Risk / tests.** Low-medium. Tests: hammers accumulate and complete the cheapest weighted building;
tech-gating respected; the 2-per-plot ruler limit enforced off-center; no hammers → queue idles.

## B5 — Household non-housing buildings

**Status: BUILT 2026-07-23.** As-built: `BuildEconomy.pickHouseholdBuilding` (the ruler brain's
scoring on the household's own plot, per-plot uniqueness) + `Laborer.buildOwnBuilding` (a housed
household's surplus hammers pay its own next regular; completion raises it owned; at the limit or
with nothing buildable, hammers donate to the ruler's queue). **Limit-scope ruling (user,
2026-07-23): the 2-per-owner-per-plot limit counts only deliberate, costed regular constructions** —
housing AND the emergent families (autobuild vernacular; the 185 costless state/property buildings:
crime, disease, ordinances, beliefs, achievements) are exempt and stack freely, since nobody chose
to build them; the same `buildable()` predicate that gates the brains scopes the limit.

**Goal.** A housed household spends its hammers on up to **two** regular buildings on its own plot before
donating.

**Steps.**
1. Project selection mirrors the queue brain: highest-AI-weight affordable unlocked building the
   household doesn't have, on its occupied plot, respecting **2 per owner per plot** (its houses don't
   count). Never at the center.
2. Priority chain finalized: house → own buildings (≤2) → donate. A household at its limit donates
   everything — the mature colony's hammer flow tilts toward the ruler over time, which is the intended
   arc (early: housing wave; late: monumental center).
3. Effects stay dormant; the buildings are visible state (web plot hover / composition panels can list
   them later).

**Seams.** `Laborer` project slot (B2), `BuildingCatalog`, the `Plot` owner-aware limit check.

**Risk / tests.** Low. Tests: the limit binds per owner per plot; houses exempt; donation resumes at the
limit.

## B6 — The player verb (`queue_build`) + the Civ4 interrupt

**Goal.** The build queue as the first real player verb on the command spine — including the
what-to-build-next interrupt that makes it feel like a game.

**Steps.**
1. A `QueueBuild` command (append / reorder / cancel queue items; colony-scoped) on the `CommandLog` —
   tick-stamped, replayed deterministically like every command; the heuristic (a) fills the queue only
   for unattended colonies.
2. **The interrupt**: when a player-seated colony's queue runs empty, `HostedSession` **auto-pauses**
   (a new clock-state reason — awaiting-input, distinct from a manual pause) and the snapshot carries an
   `awaitingBuildChoice` flag plus the buildable candidates (unlocked, affordable, not-owned, non-wonder,
   AI-weight-sorted as the default ordering). The web client shows a **modal** to pick the next
   building(s) — multi-select queues several; submitting sends `QueueBuild` and the session resumes.
   The pause is clock state only, never sim state, so replay is untouched. **Scoped to solo-seated
   sessions**; a shared/lockstep session must not freeze on one player's empty queue (open question —
   likely a notification card + heuristic fallback there).
3. Exposed through the existing admin-gated `submit_command` MCP tool; snapshot gains the queue (current
   item, progress, pending list) for the web/rail panel and `get_snapshot`.
4. Web read surface (the Surface decision): buildings listed on plot hover and in the colony composition
   rail; the queue panel in the right rail (read-only for spectators; the verb is player/admin-gated as
   everything on the spine); SimLog queue/completion events already surface as notification cards.

**Seams.** `CommandLog`/`HostedSession` (clock states — cf. the session-management ClockState split),
`SessionWriteMcpTools`, snapshot DTO, web rail + modal, lobby/session seat identity (who counts as "a
human player seated").

**Risk / tests.** Low-medium — the spine exists; the new part is the awaiting-input clock state and its
interaction with seats. Tests: command replays to the same completions; empty queue pauses a seated
session and not an unattended one; resume on submit; a spectator snapshot shows the queue; authz on the
verb.

---

## Calibration scenario & acceptance

A new opt-in scenario (a `CampFoundingEconomy` sibling with `buildEconomy` + `homePlots` on) is the
feature's proving ground, added in B1 and growing assertions phase by phase. Its smoke test asserts the
**acceptance bar**: the founding housing wave completes within a bounded period; weddings/fission resume
after it; firms retain enough labor to function (no home-working market collapse — watch the hysteresis
band); colony survival matches the current home-plots colony. The instrumentation (the B1 hammer
printer + existing inflation/tech printers) additionally watches: minted-commerce money-supply growth,
early **tech pacing** under housing-first fiscal priority (a deliberate risk, not an assertion), and
scaffold-cap congestion between the palace commission and plot-opening. Only when this scenario holds
does the default-flip conversation start.

## Sequence & dependencies

```
B1 (occupation choice) ──► B2 (housing) ──► B4 (main queue) ──► B5 (household buildings) ──► B6 (verb)
                                └──────────► B3 (catalog import) ──┘
```

- **B1 first** — it is the economic engine and the calibration risk; everything downstream just spends
  what B1 produces. B2 gives hammers their first purpose and the demographic gate.
- **B3 is independent** after B1 and can run in parallel with B2; B4 needs both.
- **B6 last** — pure surface over a working queue.

## Autobuilds = vernacular development (design lens, user 2026-07-23)

C2C's `bAutoBuild` set is what a settlement does to itself, without the crown's queue — "what the
peasants would build themselves": the **housing** line (B3 made this literal — households hammer-build
their own shelter), the **`RESOURCES_*`** markers (cottage industry — charcoal burners appear when the
materials and know-how exist, nobody commissions them), the **`PESTS_*`** infestations (organic
development's dark side — arrive with population and food stores; a future sanitation/granary
pressure), the **`CIVIC_*`/`WORLDVIEW_*`** practice markers (social states — estate-system/law
territory), and the era **knowledge bases**. The B4 brain excluding all autobuilds from the ruler's
queue is therefore the correct *semantics*, not just a data guard: the queue is deliberate crown
construction; autobuilds are emergent. Future features should raise them from colony state (economy
output, population, laws), never from hammers.

## Open questions (deliberately deferred)

- The commerce-component skill (FARMING vs a trade-ish skill) — pick at B1 implementation.
- Housing's C2C side-effects (`iHealth` maluses, feature/bonus prereqs like bark huts wanting forest) —
  imported as dormant data with the rest; whether the feature prereqs ever gate eos construction.
- Elite housing **tier and siting** — do nobles target a richer housing row than laborers (a manor vs a
  hut — pick the *most* expensive affordable rather than the cheapest?), and does the court really stack
  at the center or on plots the nobles come to own (the estate system's land question).
- The **shared/ranked session interrupt** — a lockstep royale must not freeze on one player's empty
  queue; likely a notification card + heuristic fallback there (decided when Timeline scoring lands).
- Whether commissioned construction ever generalizes into a **market** laborers can buy from — requires
  a second builder; price discovery is deferred until then (see the Hammer-price decision).
- Whether the ruler ever auto-builds off-center (the ≤2 outer slots) or that stays player-verb-only.
- When `buildEconomy` (and `homePlots`) flip default-on — after calibration proves a standard colony
  survives the housing wave.
- Building *effects* (the imported catalog's dormant data) — a separate feature with its own plan.
