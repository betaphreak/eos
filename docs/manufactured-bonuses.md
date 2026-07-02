# Design note: manufactured bonuses (the production chain)

**Status:** design complete; **step 1 (the data layer) implemented 2026-07-02** —
`ManufacturedBonusExporter`/`RecipeExporter` emit the committed
`/manufactured-bonuses.json`, `/recipes.json` and `/tier1-providers.json` resources
(records `good/Recipe.java`, `good/TierOneSource.java`; covered by
`RecipeCatalogTest`). The runtime (steps 2–3) remains proposed.
**Depends on:** the goods model (`com.civstudio.good.Good`); the firm hierarchy
(`com.civstudio.agent.firm.*` — esp. the labor-only `BuilderFirm`/`StrategicFirm`); the
market layer (`com.civstudio.market.*`, `ConsumerGoodMarket`); the bonus/resource layer
(`com.civstudio.geo.Bonus`, `BonusClass.MANUFACTURED`); the plot/terrain model
(`docs/plots.md`); the tech tree (`docs/tech-tree.md`).
**Driven by / first consumers:** `docs/household-housing.md` — a household buys its
dwelling's construction material (wood, bricks, …) from that material's market. Housing is
the **demand** side; this note is the **supply** side. The **caravan** is the second
consumer: a wandering/trading band forages, carries, and (via a `TradeCaravan`) buys and
sells these goods at the per-good markets — see *Caravans carry, forage, and trade these
goods* below (`docs/caravan-march.md`, `docs/caravan-trade.md`).

## Motivation

C2C resources split in two:

- **Raw bonuses** — placed on the map (wheat, iron, marble, timber). Already imported
  (`bonuses.json`, 106) and read for plot yield.
- **Manufactured bonuses** (`BONUSCLASS_MANUFACTURED`) — **produced**, not placed: wood
  from a lumber camp, bricks from clay, leather from hide, cloth from a weaver. In C2C a
  *building* grants the manufactured bonus, consuming raw inputs. Not modeled today.

Housing is the first feature that needs these to be real, tradeable goods (its construction
materials are almost all manufactured). Rather than fake them as an abstract cost, we model
them properly: a **demand-driven production chain** that turns labor and raw inputs into
manufactured goods sold in per-good markets.

## Decisions

| # | Topic | Decision |
| --- | --- | --- |
| M1 | Material acquisition | A household **buys 1 unit/step** of its material from that good's market |
| M2 | Market structure | **Per-good markets** (one per manufactured good), not a single generic one |
| M3 | Payment | **To the good's producer** (circulates) |
| M4 | Data source | **Import the C2C source files** (an exporter, like the others) |
| M5 | Scope | **All** C2C manufactured bonuses (the full 326-good catalog), tech-gated so only the era-appropriate slice is ever live |
| M6 | Producer (eventual) | A **building** — the C2C processing buildings (Brickworks, Tannery, …). Interim, pre-building-system, it is implemented as a firm-agent (M26) |
| M7 | Input chains | Producers **consume raw inputs** — a real multi-tier recipe network (clay → bricks, hide → leather) |
| M8 | Placement (interim) | **Landless** (center-grouped, no plot) until the building system lands |
| M9 | Ownership | **The colony itself** — revenue accrues to the colony/treasury, no dividend |
| M10 | Staffing | **Laborers**, on the regular labor market |
| M11 | How producers arise | **On tech unlock** (the good's `TechReveal`) — deterministic, no charter; combined with demand (M14) |
| M12 | Instance cap | **One producer per good per colony** |
| M13 | Capital | **None** — labor + raw inputs only (labor-only, à la `BuilderFirm`) |
| M14 | Creation gating | **Only goods with downstream demand** — a housing material, or an input to an active producer (so the chain grows from demand, not all 326 at once) |
| M15 | Output rule | **Pull to demand** — produces only what the market demands, up to its labor/input limit |
| M16 | Chain bottom | Bottom-tier producers **source raw inputs from a claimed plot** (forest → wood); upper tiers buy manufactured inputs — **the chain terminates in land** |
| M17 | Consumers (for now) | **Housing materials only** as end-demand; otherwise consumed only as inputs to other producers |
| M18 | Producer tech gate | The good's own **`TechReveal`** (from the catalog) |
| M19 | Geography gate | **Hard** — a bottom-tier good is producible only if the colony has claimed a plot bearing its raw input; else that good (and any housing rung needing it) is impossible there |
| M20 | Economics | **Zero-profit conduit** — sells at cost (wages + inputs), like `BuilderFirm`/`StrategicFirm` |
| M21 | Pricing (eventual) | **Supply/demand discovery** per good; in the interim **deferred** in favour of M30 |
| M22 | Production function | **Labor-driven, inputs gate** — output = `A·L^β`, **hard-capped by input availability** |
| M23 | Housing × material | **Hard coupling** — a household may hold a rung only if its material is obtainable; it **downgrades** if the material becomes unavailable |
| M24 | Labor priority | **Below food** — necessity/food firms get first call on labor; producers hire only the surplus workforce |
| M25 | Input quantity | **Consume 1 unit of each input good per unit of output** (recipe *structure* from the data; the 1:1 coefficient is an eos calibration knob) |
| M26 | Producer identity (interim) | A **colony-owned firm-agent** (a `Firm` subclass with `act()`), trading in the labor + per-good markets like other firms |
| M27 | Tier sequencing | **Pipeline delay** — a tier consumes the previous tier's output from the **prior step** (a one-step lag per tier); no intra-step ordering |
| M28 | Auto-build cadence | **Monthly**, folded into the ruler's `reviewSectors()`: recompute the demand graph, build each newly demanded + tech-unlocked + geography-available producer |
| M29 | Tier-1 → plot mapping | **Extracted from data** — the `RecipeExporter` reads the gatherer buildings' terrain/feature prereqs to derive each tier-1 good's raw plot source |
| M30 | Interim pricing | **Price at cost** (M20 wins over M21); demand above the single producer's capacity shows as a **shortage** — feeding housing's material-unobtainable gate (M23) — not a price spike. True price discovery (M21) returns once multiple producers can enter |

## The supply model

**The producer.** Eventually a manufactured good is made by a **building** (M6) — the C2C
processing building (a Tannery, a Brickworks). Until the building system exists, a producer
is implemented as a **landless, colony-owned firm-agent** (M8, M26): a `Firm` subclass
that, in `act()`, posts a labor bid and an output sell-offer, hires laborers (at lower
priority than food, M24), draws its inputs, and sells into its per-good market on `clear()`.
It is **labor-only** (`A·L^β`, no capital — M13, M22) with output **hard-capped by input
availability**, **one per good** (M12), run as a **zero-profit conduit** (M20) whose account
the treasury holds. It **prices at cost** (M30); demand above its capacity manifests as a
**shortage**, not a price spike (true supply/demand discovery, M21, awaits free entry).

**The chain assembles from demand, terminating in land** (M14–M17, M19):

1. A household **aspires** to a rung — eligible on every leg *except* its material not yet
   being obtainable. That **aspirational** demand registers (not a realized purchase — a
   household can't buy the material until the producer exists; aspirational demand is what
   breaks the M14 ↔ M23 chicken-and-egg).
2. Demand + the good's `TechReveal` (M18) + the raw resource being on the colony's land
   (M19) brings a producer into being (monthly, M28). If the geography gate fails (no clay
   on any claimed plot), no producer arises and the rung stays unreachable.
3. The recipe's inputs that are *themselves* manufactured register their own demand →
   their producers arise → recurse.
4. At the **bottom**, the input is a **raw map resource** sourced directly from a claimed
   plot (M16) — a **renewable tap** (the land yields it each step, not depleted), unlike a
   traded input good which is a consumed quantity (M25). So "consume 1 per input" (M25)
   bites on **market-bought** inputs; the tier-1 raw draw is a renewable plot flow.
5. Each producer **pulls to demand** (M15) — nothing is made that no one buys.

So the chain grows downward from housing demand to the land, exactly as deep as needed,
and bootstrapping is automatic — no cold-start seed list. Because the chain terminates in
the colony's actual land, **geography gates which housing rungs a colony can reach** (M19 →
M23): a clay-less colony can never raise brick housing.

## The data (complete)

Three C2C files in `data/` supply everything:

- **Catalog** — `Manufactured_CIV4BonusInfos.xml`: **326** `MANUFACTURED` bonus
  definitions (`Type`, `TechReveal`/`TechCityTrade`, occasional health/happiness). Covers
  every housing material and far more; spans the whole tree (`TECH_GATHERING` →
  `TECH_PERSONAL_COMPUTERS`).
- **Tier-1 providers** — `zProviders_CIV4BuildingInfos.xml`: **48** `BUILDING_RESOURCES_*`
  buildings, each mapping an extracted good ← an OR-list of tile-based gatherer buildings
  (wood, hide, bone, the metal ingots, …). The extraction tier.
- **Recipe layer** — `Regular_CIV4BuildingInfos.xml`: **2403** buildings, each processing
  building carrying its full recipe — **output** in `<ExtraFreeBonuses>`, **inputs** in
  `<Bonus>` (primary) + `<PrereqBonuses>`/`<PrereqVicinityBonuses>` (secondary), gated by
  `PrereqTech`/`PrereqOrBuildings`. E.g. `BUILDING_TANNERY` (`TECH_TANNING`): `HIDE` +
  `TANNIN` → `LEATHER`. Filtering to buildings whose output is a `MANUFACTURED` bonus
  yields the **producer set + the recipe graph** — exactly the M7 multi-tier chain.

> **Modeling nuance — access vs. flow.** C2C bonuses are **binary access flags** (a city
> *has* `HIDE` access or not); a recipe means "input available + building ⇒ output granted,"
> with `iNumFreeBonuses` nominal (1). We instead treat manufactured goods as **quantitative**
> eos goods (markets, 1 unit/step, pull-to-demand, output gated by input *quantity*). So the
> recipe **structure** imports cleanly from the data, but the **input quantities** are an
> eos choice — the 1:1 default (M25), with the `<Bonus>`/`<PrereqBonuses>` split available if
> we later want per-input coefficients.

## Runtime model

Through the existing step loop (`act()` posts offers → markets `clear()` settle):

- **Producers act like firms** (M26): bid labor, sell output, draw inputs; zero-profit, the
  treasury holds the account.
- **Chains run a one-step pipeline delay** (M27): a tier consumes the previous tier's output
  bought *last* step, so no intra-step ordering — deep chains take a few steps to fill.
- **The demand graph + auto-build run monthly** (M28) inside `reviewSectors()`: recompute
  which goods are demanded (aspirational housing + active producers' inputs, recursive,
  geography-gated) and build each newly viable producer.

**Implementation mechanics** (not design forks):

1. **Labor priority below food (M24)** — `LaborMarket` needs a two-pass allocation:
   necessity/food firms first, producers from the residual.
2. **Per-good market lifecycle** — up to 326 markets, created lazily when a good first gains
   demand and registered on the `Settlement`.
3. **Zero-profit budgeting (M20)** — how a producer sizes its wage + input budget from
   lagged demand to break even, like the existing conduit firms.
4. **Goods taxonomy** — manufactured goods are a new family of `Good` (their own class or a
   parameterized good), distinct from `Necessity`/`Enjoyment`/`Capital`/`Strategic`.

## Caravans carry, forage, and trade these goods

The wandering **caravan** (`docs/caravan.md`, `docs/caravan-march.md`) is the second consumer
of the goods model, alongside housing — and much of the seam is **already built** by the
caravan march work:

- **Tech-gated identification (implemented).** A band departs with a **tech state**
  (`MigrantCaravan.setKnownTechs`; default `MigrantCaravan.DEFAULT_TECH` =
  `TECH_MEDIEVAL_LIFESTYLE` for a fresh band, or its colony's carried research for a
  dissolution band) and can only **identify** — report, forage, carry, or trade — a bonus or
  good whose **`TechReveal`** it knows. This is exactly the tech gate this catalog carries
  (M18/M5): a medieval band cannot see or handle a good locked behind a future tech (oil,
  aluminium, natural gas). The gate lives in `MigrantCaravan.identifies(Bonus)` and already
  filters the march journal's reported bonuses and what the band may forage.

- **Foraging and gathering (implemented — the per-good inventory is real).** As it marches,
  a band forages **food-class raw bonuses** (CROP/LIVESTOCK/SEAFOOD → `NECESSITY`, via
  `BonusClass.resourceType()`) off its corridor into its carried **larder**, gated on surplus
  daylight and on the band identifying the resource; with the surplus hours foraging leaves
  over it then **gathers every other identified raw bonus** (ores, gems, luxuries,
  production materials…) into a **per-good `Cargo` inventory** (`good/Cargo.java`, carried on
  the `Caravan` base next to the money hoard) — **whole units** keyed by bonus type (discrete
  goods, no fractional elephants; part-unit gathering accrues as progress on the band),
  capped by a head-count-scaled **carrying capacity** (`MarchConfig.cargoCapacityPerHead`),
  journalled as the march's `Gathered`/`Cargo`/`Carrying` columns. The larder remains the `NECESSITY`
  special case (kept separate because it is eaten daily); the cargo is the goods side a
  trade caravan will trade across (`Cargo.draw` is the selling seam), awaiting the per-good
  markets (M2) as its venue.

- **Trade (designed — `TradeCaravan`, `docs/caravan-trade.md` Phase B).** The **per-good
  markets** (M2) are the venue where a settlement-sponsored trade caravan **buys and sells**
  manufactured (and raw) goods, coupling two settlements' economies. Without this note's
  goods-as-quantities model, caravan trade would be an abstract money transfer; with it, a
  caravan moves **real goods** between markets. The band's carried **hoard** (money) and
  larder/inventory (goods) are the two sides it trades across.

So the per-good markets (M2), the `BonusClass.resourceType()` seam, and the `Bonus.techReveal`
gate are **shared infrastructure**: housing is the first *demand* consumer of manufactured
goods, and the caravan is the *mobile* forager/carrier/trader of both raw and manufactured
goods. Building this note's supply side is therefore also what makes caravan foraging and
trade span the full goods catalog rather than just food.

## Next steps

1. ~~**Exporters.**~~ ✅ **Done (2026-07-02).** `ManufacturedBonusExporter` (catalog →
   `manufactured-bonuses.json`, all 326 as `geo/Bonus` records — 313 `MANUFACTURED` + 13
   `WONDER` pseudo-goods the source file carries) and `RecipeExporter` (→ `recipes.json`,
   the 318 Regular buildings granting a catalog good, as `good/Recipe.java` — output,
   inputs, tech, prereq-buildings; and → `tier1-providers.json`, the 48 providers as
   `good/TierOneSource.java` with each gatherer's terrain/feature/vicinity plot prereqs
   inlined, M29). Both parallel the existing Civ4 exporters (`geo/export/`); spot-checked
   against the XML by `RecipeCatalogTest`. Data notes: the two catalogs are disjoint
   (`BONUS_SALT` is the one tier-1 output that is a *raw* map bonus, not manufactured);
   37 gatherer references (national wonders, `BUILDING_HERD_*`, lost-lands buildings from
   uncommitted C2C modules) are skipped, every provider retaining ≥1 real gatherer; 43
   catalog goods have no committed producer — all far-future/mythical (Martian goods, warp
   drives…), unreachable behind their `TechReveal` anyway.
2. **Runtime** — the producer firm, per-good markets, the monthly demand graph, the labor
   two-pass.
3. **Calibration** — `A·L^β` coefficients, the 1:1 input coefficient, market params.
