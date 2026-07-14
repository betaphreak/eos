# Design note: settlement tiers — Village and City

**Status:** structural split **shipped** (2026-07-14); permanence-gates-collapse and the broader
capability-interface extraction are future work.
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

## Founding-center placement (in progress)

The city-center / village-center plot is sited by `CityPlacement.foundValue` (`urban-plots.md`). The
site-selection priority is being reworked to **water-dominant** (owner, 2026-07-14): (1) maximize
adjacent **river/water** plots, then (2) proximity to as many **bonuses** as possible — a water-dominant
weighted score (bonuses stay a strong secondary term). Deterministic (no RNG), but it moves the
persisted per-province urban core, so it needs a plot-cache generation bump + regen on deploy.

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
