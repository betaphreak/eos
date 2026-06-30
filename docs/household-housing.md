# Design note: household housing

**Status:** proposed — design complete, not yet implemented
**Depends on:** the household stack (`com.civstudio.agent.Household`, `AbstractHousehold`
and its implementors `Laborer`, `Noble`, `Ruler`); the housing catalog
(`com.civstudio.settlement.HousingBuilding` + `/housing.json`, exported by
`com.civstudio.geo.export.HousingExporter` from
`data/SpecialBuildings_CIV4BuildingInfos.xml`); the plot model
(`com.civstudio.settlement.Plot`, the `Plot.addBuilding`/`buildings()` seam); the tech
tree (`docs/tech-tree.md`); the consumer-good markets (`ConsumerGoodMarket`); and — for
the dwelling material a household buys — the **manufactured-goods production chain**
(`docs/manufactured-bonuses.md`), of which housing is the first consumer.
**Related:** `docs/plots.md` *Buildings vs. improvements*; `docs/social-class.md`.

## Motivation

Every household today "lives" the same abstract way: it earns, eats a ration, and spends
its surplus on a generic `Enjoyment` good. There is no notion of *where* a household lives
or how its dwelling improves. The C2C **housing ladder** (lean-tos → hovels → cottages →
… → arcologies, 56 rungs in `/housing.json`) is the content for exactly that: a
per-household dwelling that starts crude and improves as the colony develops, **creates
demand for a specific construction material** (bought from the manufactured-goods chain),
and returns a small **benefit** to its occupants.

## Decisions

| # | Topic | Decision |
| --- | --- | --- |
| 1 | Scope / placement | **Per-household rung**; each household's `Building` record is **mirrored on plot 0** (the center) for now — interim, via the existing center-building seam |
| 2 | Default at founding | **`BUILDING_HOUSING_HOMELESS`** |
| 3 | Material acquisition | The household **buys 1 unit/step of its housing's `<Bonus>` from that material's market** — a real manufactured good (`docs/manufactured-bonuses.md`); the payment flows to the good's producer |
| 4 | Housing benefit | **`commerceChanges[gold]` → minted free money**; food (`yieldChanges`), production, `iHealth`, `iHappiness` all **dormant** (the food→necessity benefit was dropped — a better benefit comes later) |
| 5 | `prereqPopulation` | **Minimum plot count** the settlement must hold (not population) |
| 6 | Tech gate | `prereqTech` must be in the colony's researched-tech set and `obsoleteTech` not; `obsoleteTech` retires the rung |
| 7 | Environmental prereqs | `bFreshWater` / `prereqOrFeatures` / `prereqOrTerrains` checked against the settlement's **claimed plots** |
| 8 | Rung selection | **Follow the upgrade chain** — climb `replacements` / `obsoletesToBuilding` links from `HOMELESS` |
| 9 | Rung ranking | **By gold** (`commerceChanges[0]`), ties by ladder order |
| 10 | Per-household differentiation | **Affordability gates** which rung a household occupies |
| 11 | Affordability test | **Solvent after food** — can pay the material *and* still afford its ration this step |
| 12 | Losing eligibility | **Active downgrade** to the best rung it can sustain (to `HOMELESS` if needed) |
| 13 | Fresh water (`bFreshWater`) | A **claimed plot on/beside a river or lake** |
| 14 | Plot-0 storage | **One `Building` per household** (a multiset — a live census on plot 0) |
| 15 | Laborer spend | The material buy **replaces** the `Enjoyment` buy; the freed budget remainder is **saved** |
| 16 | Noble / ruler spend | **Additive** — they keep their `Enjoyment` spend *and* buy the material on top |
| 17 | Material obtainable | A rung's material must be **producible** in the colony — its recipe chain reaching a raw resource the colony's land holds (the **hard geography gate**, `docs/manufactured-bonuses.md` M19/M23) |
| 18 | Building prereqs | `PrereqInCityBuildings` / `prereqOrBuildings` name non-housing buildings not modeled yet, so a rung needing one is **unbuildable until that content lands** (deferred by absence) |
| 19 | Cadence | Eligibility/upgrade re-evaluated **monthly**; the material buy + benefit happen **every step** |

## The household housing attribute

Each `Household` carries a current housing rung — the eos-native id of a `HousingBuilding`
— defaulting to `BUILDING_HOUSING_HOMELESS` at founding. It lives on `AbstractHousehold`
(shared by `Laborer`/`Noble`/`Ruler`), exposed via `Household.housing()`.

The rung is the household's **canonical** housing state (read for the material buy, benefit
and affordability). Its `Building` record is **also mirrored on plot 0** (decisions 1, 14):
the center plot accumulates one `Building` per household (a multiset — 40 hovel-dwellers
are 40 `HOVELS` entries), via the existing `Plot.addBuilding`/`buildings()` seam, so the
village's dwellings have a physical home without each consuming a plot, and plot 0's
building list is a live housing census. On upgrade/downgrade the old `Building` is removed
and the new rung added; on death it is removed. This is **interim** — a later cut may give
housing its own plots; the household-field-plus-plot-0-mirror keeps the door open.

The `HousingBuilding` catalog loads once from `/housing.json` into a small registry
(parallel to `TerrainRegistry`), likely `com.civstudio.settlement.HousingRegistry`.

## Eligibility — when a household may hold a rung

A rung is eligible when *all* its prereqs pass. Legs 1–4 and 6 are **colony-wide**; leg 5
is **per-household** — the lever that makes housing vary across households.

1. **Tech** (decision 6) — `prereqTech` researched and `obsoleteTech` not. `HOMELESS` has
   no `prereqTech`, so it is the always-eligible floor.
2. **Plot count** (decision 5) — `settlement.getPlotCount() >= prereqPopulation`.
3. **Environment, against claimed plots** (decisions 7, 13) — the colony must own a plot
   satisfying each map-placed leg: `bFreshWater` (a river/lake-adjacent plot),
   `prereqOrFeatures` (any-of features — a longhouse wants nearby forest), `prereqOrTerrains`
   (any-of terrains).
4. **Building prereqs** (decision 18) — a rung naming a non-housing building is unbuildable
   until that content exists ("no such building → fail").
5. **Affordability** (decisions 10, 11) — the household is **solvent after food**: it can
   buy the material *and* still afford its ration this step. The loosest gate (food-first),
   so a richer household climbs higher and a poorer one lives more modestly.
6. **Material obtainable** (decision 17; `docs/manufactured-bonuses.md` M19/M23) — the
   rung's `<Bonus>` material must be producible/buyable: a producer exists, its recipe
   chain terminating in a raw resource the colony's claimed plots hold. A clay-less colony
   can never raise brick housing.

## Upgrade and downgrade

A household **climbs the ladder links** rather than free-picking the global best:

- From its current rung it considers that rung's `replacements`; `HOMELESS.replacements` is
  the whole ladder, so a fresh household can enter at any eligible rung, and thereafter each
  rung's `replacements` are its next steps.
- Among eligible links it takes the **best by gold** (`commerceChanges[0]`, decision 9),
  ties by ladder order.
- **Obsolescence** — when `obsoleteTech` is researched the rung retires; the household moves
  to its `obsoletesToBuilding` if named, else re-enters from the best eligible replacement.
- **Active downgrade** (decision 12) — if the household loses affordability (income drops) or
  a colony-wide leg (a lost prereq, or its **material becomes unobtainable**), it moves down
  to the best rung it can sustain, to `HOMELESS` if needed. Housing tracks fortunes both
  ways — not sticky.

## Consumption and benefit

A housed household **buys 1 unit/step of its dwelling's `<Bonus>` material** from that
material's per-good market (decision 3), at the market price; the payment flows to the
good's producer (`docs/manufactured-bonuses.md`). How this meshes with today's spend
differs by type:

- **Laborers** buy the material **instead of** `Enjoyment` (decision 15): the enjoyment leg
  of the consumption budget is replaced by the material buy, and the remainder is **saved**
  (the existing target-savings model). So as housing spreads, laborer enjoyment demand
  shifts to material demand. A `HOMELESS` laborer has no material and **keeps buying
  `Enjoyment`** until it climbs to a real rung — a smooth transition.
- **Nobles and the ruler** are **additive** (decision 16): they keep their wealth-fraction
  `Enjoyment` spend *and* buy the material on top.

The dwelling returns a small **benefit** (decision 4): `commerceChanges[0]` (gold) is
**minted** as free money credited to the household's account (new wealth). The food slot of
`yieldChanges` (almost always 0 in the data), production, `iHealth` and `iHappiness` are
stored but **dormant** — the food→necessity benefit was removed pending a better design.

## Phasing

- **Phase 1 — attribute + registry (near byte-identical).** The housing rung on
  `AbstractHousehold` (default `HOMELESS`), the `HousingRegistry` from `/housing.json`, the
  plot-0 `Building` mirror. Nothing reads the rung yet; runs unaffected.
- **Phase 2 — eligibility + the upgrade chain.** The prereq checks (tech, plot count,
  claimed-plot environment, affordability, material-obtainable) and the monthly chain-climb,
  keeping the plot-0 mirror in sync. Reported via a `HousingPrinter` (`Housing.csv`, the rung
  distribution); no consumption change yet, so the economy is unmoved.
- **Phase 3 — the material buy.** A housed laborer swaps its `Enjoyment` buy for the material
  buy (saving the remainder); nobles/ruler add it on top. Behavioural — re-validated against
  the suite; depends on the manufactured-goods supply side existing.
- **Phase 4 — the benefit.** Wire the `commerceChanges[gold]` minted money. Calibration-light.

## Remaining details (tunable, non-blocking)

1. **Magnitudes.** The "solvent-after-food" threshold feel, set in calibration so housing
   neither bankrupts households nor is free.
2. **Succession.** Proposed default: a fresh **promoted** laborer (a new household) starts
   `HOMELESS`; a same-dynasty **heir** that inherits the estate keeps the rung.
