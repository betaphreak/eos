# Design note: settlement tiers — Village and City

**Status:** the Village/City structural split is **shipped** (2026-07-14); permanence-gates-collapse
and the broader capability-interface extraction are future work. A **data-driven `SettlementTier`
growth ladder** (cottage→town, from C2C data) is **proposed** below (design only, no code) — the
sub-City tiers the explorer-caravan gating (`docs/explorer-caravan.md`) motivated.
**Related:** [`district-buildout.md`](district-buildout.md) (the districts a City hosts),
[`urban-plots.md`](urban-plots.md) (the urban plots that decide the tier),
[`city-and-league.md`](city-and-league.md) / [`rank-ladder.md`](rank-ladder.md) (the *household-rank*
VILLAGE/CITY rungs — a **different axis**, see below).

## What this is

A settlement is founded as one of two concrete tiers, both extending the (now abstract) `Settlement`:

- **`Village`** — a settlement with **only a city center**, no districts. Founded into a
  single-urban-plot site: an ordinary land province (which carries exactly one urban plot) or a
  bare-coordinate analytical colony. Today's collapse-prone colony. Not a `DistrictHost`.
- **`City`** — a settlement with **districts beyond its city center**, so it is a `DistrictHost`.
  Founded into a multi-urban-plot site: an Anbennar `city_terrain` province, whose several urban core
  plots (dev-scaled — `urban-plots.md`) are the city's districts. Its buildings can spread across
  those plots, and it is `isPermanent()` (does not collapse when depopulated).

## The tier is geographic (decided, 2026-07-14)

A settlement's tier is fixed at founding by its **site's urban-plot count**, not by a gameplay
promotion: `GameSession.newSettlement` builds a **`City` iff `province != null && province.city()`**
(the Anbennar `city_terrain` flag → several urban plots via `CityPlacement.coreSize`), else a
**`Village`** (ordinary province → one urban plot; or no province at all). Every inhabitable land
province already carries **at least one** urban plot (`urban-plots.md`), so every founded settlement
has a city center.

**Not the rank ladder.** `Rank.VILLAGE`/`Rank.CITY` (`rank-ladder.md`, `city-and-league.md`) name a
*household's* command scope (Ruler → Mayor) and model `VILLAGE→CITY` as a *promotion*. That is a
separate axis from these `Settlement` subtypes, which are about the **site's urban capacity**. The two
can be aligned later (a City's ruler is a Mayor); for now the subtype is purely geographic and the rank
work stays independent.

## The class + interface shape

```
                Settlement (abstract) ── implements ─► UrbanCenter
                   │  step loop, markets, agents, banks, lifecycle, tech, taxation
        ┌──────────┴───────────┐
     Village                  City ── implements ─► DistrictHost (extends UrbanCenter)
   (center only)         (center + districts, permanent)
```

- **`UrbanCenter`** — the urban surface both tiers expose: `getCityCenter()` (plot 0, or `null`),
  `getDistrictPlots()`, `getStartingDistrictCount()`, `hasDistricts()`. Factored out so the district
  feed, the auto-build trigger, and future non-settlement holders (a caravan camp, a league seat) can
  depend on the urban surface without the whole colony machinery.
- **`DistrictHost extends UrbanCenter`** — a pure capability **marker**: "has districts beyond the
  center." Implemented by `City` only; `Settlement.hasDistricts()` is `this instanceof DistrictHost`.
- **`Settlement` is abstract** — all shared behaviour (the step loop, markets, agents, banks,
  lifecycle) stays here; constructors are `protected`. The two subclasses only narrow/widen the urban
  surface and flip permanence:
  - `Village.getStartingDistrictCount()` = `min(1, super)` — its single center (0 if province-less).
  - `City.isPermanent()` = `true` (base `false`).

**Behaviour-neutral for now.** The split changes construction and adds metadata (`hasDistricts`,
`isPermanent`, the tier-narrowed district count) but **no economics**: `isPermanent()` is not yet read
by the collapse machinery, so a `City` (e.g. the demo colony Dhenijansar, a `city_terrain` province)
still collapses exactly as before. Full engine suite (281) + server suite (35) stay green.

## Founding-center placement ✅ DONE (2026-07-14)

**Water-dominant, and the same logic for both tiers.** The city-center / village-center plot is sited
by a water-first score:

- **`CityPlacement.foundValue`** (the generation-time urban-core siting for ordinary/Village provinces)
  now leads with a **dominant fresh-water term** — the candidate's `ProvinceMask.coast` sea-adjacency
  edges plus nearby `riverCode` cells (`WATER_WEIGHT`/`RIVER_WEIGHT` = 25) — then bonuses as the strong
  secondary (`BONUS_WEIGHT` = 20), then yields, then a hill nudge. `RIVER_WEIGHT` was previously dead
  (declared, never scored); it is now live. The mask is threaded into `coreCells`.
- **`ProvincePlotPool.bestUrbanCenter`** (the claim-time anchor) picks the urban plot by the **same
  water-first criterion** (the plot's `coast` edges + `river` + `riverAdj` neighbours), tie-broken by
  centroid proximity — so a **City** (every plot urban) anchors its centre by the same water-first
  logic as a **Village** (its single urban plot), not the old centroid-nearest. A province with no
  water differs only by the unchanged tie-break, so no-water sites are byte-identical.

Deterministic (no RNG). It moves the persisted per-province urban core, so the plot-generation version
was bumped (`ProvincePlotStore.GEN_VERSION` 5 → 6) — the cache dir is versioned by it, so caches
regenerate lazily and no manual clear is needed. Engine suite **284** green (incl. new
`CityPlacementTest`); the demo City (Dhenijansar) collapse tests unchanged.

## Sub-City tiers — the cottage→town growth ladder (PROPOSED, 2026-07-14)

**Status:** design only, no code. Decided approach: a **data-driven `SettlementTier`**, *not* a
class per type. Motivated by the explorer-caravan gating (`docs/explorer-caravan.md`), which
currently keys off `instanceof City` — too coarse, since "Village" is only one rung of a ladder.

### What the C2C data actually is

The settlement types "up to but not including City" are the **cottage-growth line** in
Caveman2Cosmos' `CIV4ImprovementInfos.xml` — worked-tile improvements that **upgrade over time**:

```
COTTAGE ──(10)──► HAMLET ──(20)──► VILLAGE ──(30)──► TOWN ──(40)──► SUBURBS   (→ CITY)
```

(the parenthesised numbers are each rung's `iUpgradeTime` — turns of being worked before it
advances; a parallel `RESOURCE_SETTLEMENT → RESOURCE_TOWN → RESOURCE_COMPLEX` line exists too). So
`VILLAGE` is **rung 3 of a growth progression**, not a distinct entity — the current `Village`
class name actually means "any sub-city settlement," which collides with this rung.

### Why a data-driven tier, not a class per rung

1. **This is a _growth_ progression** — a cottage that develops *becomes* a hamlet, then a village,
   then a town. A Java object **cannot change its class at runtime**, so a class per rung makes the
   central feature — growth — impossible without tearing down and rebuilding the whole colony
   (ruler, banks, markets, pool), losing its identity. Decisive against class-per-type.
2. **The rungs differ in _data_, not behaviour** — yields, population/size caps, buildable set,
   upgrade time. Exactly what the XML provides. They are all "a settlement center"; only the
   numbers differ.
3. **Class explosion** — cottage/hamlet/village/town/suburbs + the resource line + mods, each a
   near-identical subclass, for no behavioural gain.

**Keep a class only for a genuine _behavioural_ fork.** Today that is exactly one thing —
**districts + permanence** (`City`/`DistrictHost`). Everything cottage→suburbs is one growable
settlement carrying a tier field.

### The model

- **`SettlementTier`** — an ordered enum/record `COTTAGE < HAMLET < VILLAGE < TOWN < SUBURBS <
  CITY`, mirrored from the XML chain (upgrade target, upgrade time, and the per-tier data: yield /
  size cap / buildable set). An `atLeast(tier)` comparison replaces `instanceof` checks.
- **A mutable `tier` field on `Settlement`**, advanced by a **growth rule**: the settlement
  accumulates development (population × worked time, the CivStudio analogue of "the tile is
  worked") and advances a rung when it crosses the next `iUpgradeTime` threshold — Cottage→Town is
  10+20+30 = 60 development, Suburbs +40 = 100. (Growth, not a new object.)
- **The two axes reconciled.** There are two distinct things: **site capacity** (the site's
  urban-plot count — fixed at founding; a `city_terrain` multi-plot province can host districts, an
  ordinary one-plot province cannot) and **development** (the cottage→city ladder — grows over
  time). Unify them as **one ladder plus a per-site cap**: a settlement grows up the ladder toward
  a `maxTier` set by its site — a single-urban-plot Village site caps below `CITY` (no district
  plots to become a city), a multi-urban-plot site can reach `CITY`. **Districts + permanence
  unlock at the `CITY` rung**, so `hasDistricts()`/`isPermanent()` derive from `tier == CITY`
  (reachable only on a city-capable site) rather than from a founding-time subclass.
- **`City` stays the one behavioural subclass** (the `DistrictHost` capability), now the *top rung*
  of the ladder rather than a parallel type; or it dissolves into `tier == CITY` if the district
  surface is expressed through the tier. Either way, no class per sub-city rung.

### How the explorer gate changes

The gate becomes `settlement.tier().atLeast(TIER_THRESHOLD)` — e.g. **a Town or larger** musters
winter foraging expeditions (a settlement big enough to spare foragers), replacing the current
`instanceof City`. More expressive, and the exact threshold is a tuning knob.

### Open questions / calibration

- **Founding tier.** Do all settlements start at `COTTAGE` and grow (the fuller vision, but colonies
  then start tiny), or do they found at a tier fitting their site/pop and grow from there? A safe
  incremental step: keep the current founding (city sites found near `CITY`, ordinary sites found as
  a mid-rung Village) and add the *growth* mechanic on top.
- **The development metric** — what accumulates (population, worked plots, economic output) and the
  threshold scale relative to the XML's 10/20/30/40 turn costs.
- **Per-site cap** — where a single-urban-plot site caps (Town? Suburbs?), and whether a site can be
  *upgraded* to city-capacity (rank-ladder alignment, below).
- **The resource line** (`RESOURCE_SETTLEMENT → …`) — a parallel specialisation, out of scope for
  the first cut.

## Future work

- **Permanence gates collapse.** Wire `isPermanent()` into the lifecycle so a `City` survives
  depopulation while a `Village` collapses — the real behavioral payoff (aligns with
  `city-and-league.md`'s permanence). A deliberate, separately-tested change (re-baselines the collapse
  smoke tests for city sites).
- **Broader capability interfaces.** `Taxable`, `ResearchHost`, `MarketHost`, `Growable` could be
  extracted from `Settlement` so a caravan/league implements subsets — deferred until a second
  implementor needs them (avoid speculative extraction from the large class).
- **Align with the rank ladder.** A City's Ruler becoming a Mayor (`city-and-league.md`).

*When permanence lands, add a one-line pointer in `CLAUDE.md` and update this note.*
