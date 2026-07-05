# CivStudio

**CivStudio** is a proof-of-concept **game engine** for a civilization-scale
strategy game — built as a living, agent-based simulation rather than a set of
scripted mechanics. Nothing in it is faked with a formula where it can instead
*emerge*: prices come from markets that clear by supply and demand, wages from a
labor market, dynasties from people who marry, give birth, age and die on a real
mortality schedule, and geography from imported real map data. The engine is
currently **headless** — a run produces CSV time-series and an event log for
analysis — and fully **deterministic**: the same seed produces the same world.

Written in plain Java 21 (root package `com.civstudio`; the repository keeps its
original codename `eos`), with only two runtime dependencies (Jackson, Lombok).

## What the engine simulates

Each simulation step is **one in-game day**. The default run starts on
11 December 1444 and follows a colony founded into a real province of the
[Anbennar](https://anbennar.fandom.com/) fantasy world.

**The simulation kernel.** A `GameSession` owns the seed and creates named
`Settlement`s from it. Several settlements coexist in one session and run
**concurrently, one thread each, in lockstep** — sharing a province's land,
name pools, and the world map, while keeping fully independent, reproducible
random streams. Every day runs a fixed phase order: agents *act* (posting
buy/sell offers against yesterday's state), banks set rates, the dead are
replaced, markets *clear* (transactions settle at a discovered price), printers
report. Deferred settlement — intentions first, execution after — is the
engine's core mental model.

**The economy.** Firms (food, enjoyment, capital) produce via Cobb-Douglas
production functions and hire on a labor market; banks provide payment and
credit in a **three-currency hierarchy** (commoners bank in copper, nobles in
silver, the ruler in gold, with money-changers skimming a real exchange fee);
the ruler taxes bank profit and noble income into a treasury, and **charters or
dissolves firms monthly** so each sector's capacity tracks demand. A worker's
output is scaled by its **skills** (RimWorld-style, trained by the work it
actually does and fading with disuse), by the **length of the day** at the
colony's latitude, and by its **commute** to the plot it works.

**The society.** Households — laborers, nobles, the ruler — are named dynasties
drawn from per-race name tables. A colony is founded from a **peasant pool**:
the ruler promotes the ablest into laborer households and feeds the reserve on
poor relief. The aristocracy is not created but **raised** — the ablest
laborers are ennobled to own the firms and banks. Households wed spouses out of
the pool on the weekly day of rest, bear **children** who train their skills at a
civic school until working age, eat rations graded by class (gourmet → relief), and die
of old age or hunger; a colony-run **granary** buffers the food supply. Nearly
sixty **races** from the Anbennar setting (humans, elves, dwarves, harimari,
goblins…) vary mortality, names, calendars and tech.

**The world.** The full Anbennar EU4 map is imported from its source rasters:
**5,264 provinces** with adjacency, areas/regions/super-regions/continents, and
climate. A settlement's land is a per-province **plot field** decoded pixel-by-
pixel from the real terrain and vegetation bitmaps into the curated Civ4
terrain/feature/improvement/bonus catalog — so a farm on wheat land outyields
one on tundra, hills slow the march, and peaks are never settled. Two-level
**land routing** (province graph + per-province plot corridors) carries the
**caravans**: wandering bands that march by daylight, ford rivers, forage the
resources they can identify at their tech level, and re-found fallen colonies
elsewhere on the map.

**Time and belief.** A vendored solar calculator gives every location its true
sunrise and sunset — labor output rides the seasons. A pre-Reformation
**liturgical calendar** (per race) idles most firms on Sundays and feast days,
and a **tech tree** (researched by a ruler-funded science sector from the
aristocracy's intellectual labor) lifts sector productivity and gates content.

**The data pipeline.** All content is data-driven, imported once by committed
exporters and shipped as JSON resources: the Civ4/C2C terrain, feature,
improvement and bonus catalogs, the 56-rung **housing ladder**, the **326-good
manufactured-goods catalog with its full recipe graph**, the tech tree, feast
calendars, and name tables for every race. Design notes for each system —
implemented and planned — live in [`docs/`](docs/).

## Honest model status

The engine favors emergent failure over scripted success, and the current
labor model is **replacement-only**: a standard closed colony runs for years,
then collapses once its founding peasant reserve drains — by design, confirmed
by a parameter sweep (`CalibrationSweep`) and accepted while the food economy
is calibrated (births are implemented but children mature slower than the
colony declines). The smoke-test suite asserts colonies reach that collapse
*cleanly* — no invariant trips on the way down. The bare open scenario
(`SmallOpenEconomy`) is stable and growing.

## Build & run

Requirements: **JDK 21** and **Maven**.

```bash
mvn clean compile          # compile
mvn exec:exec              # run the default scenario (HomogeneousEconomy), assertions on
mvn test                   # run the JUnit 5 suite (each scenario runs full as a smoke test)
mvn package                # build the jar
```

`exec:exec` forks a JVM with `-ea` because the code uses `assert` as real
invariant checks. Select a scenario with the `sim.main` property:

```bash
mvn exec:exec -Dsim.main=com.civstudio.simulation.TwinSettlementEconomy
```

| Scenario | What it demonstrates |
|---|---|
| `HomogeneousEconomy` | The default: a standard ruler-bearing colony founded into the province of Dhenijansar, run to collapse. |
| `OpenColonyEconomy` | The same colony **opened** to an external inflow that refills its peasant pool — holds a full workforce for years. |
| `SmallOpenEconomy` | A bare colony (no ruler/pool) opened to money inflow + immigration; grows and stays stable. |
| `TwinSettlementEconomy` | Two settlements founded into **one province**, run concurrently in lockstep, competing for its 74 plots. |
| `HarimariEconomy` | A **mixed-race** colony founded by the harimari — race-varying names, calendar, mortality and tech. |
| `ElvenEconomy` | A mono-racial elven colony — founding with one of the imported Anbennar races. |

(`CalibrationSweep` and `SurvivalExperiment` are developer tools — headless
parameter grids and survival probes, not scenarios.)

Each scenario is a `static run()` returning its `SimulationHarness`, plus a
`main()`. Run-level parameters live in `SimulationConfig` (an immutable record
with a `DEFAULT`); per-agent parameters in their own config records
(`FirmConfig`, `BankConfig`, `NobleConfig`, `RetinueConfig`, …), all with
Lombok builders for cheap variants:

```java
SimulationConfig.DEFAULT.toBuilder().durationYears(10).build();
BankConfig.DEFAULT.toBuilder().exchangeFeeRate(0.02).build();   // a money-changer
```

## Output & reproducibility

A run writes its CSVs under `output/<seed>/` — one row per in-game month, the
in-game date (`yyyy-MM-dd`) as the first column, consolidated tables told apart
by a column (`Prices.csv`, `Firms.csv`, `Banks.csv`, `Services.csv`, …). A
multi-settlement session merges each table across settlements with a leading
`Settlement` column. Discrete events — foundings, ennoblements, notable deaths,
price anomalies, an annual one-line digest per colony — go to a seed-scoped
event log, prefixed with the emitting colony and its in-game date:

```
Withacen 1452-03-14: WARN Enjoyment skyrocketed to 51.30 (>10x init)
```

Runs are seed-reproducible — economic, naming, mortality, skill and terrain
draws ride separate salted RNG streams, so adding one feature doesn't scramble
the rest. (Byte-identical output *across* code versions is not a goal.)

A run's output can be visualized: [`web/`](web/) holds self-contained, read-only
HTML views built from `output/<seed>/`. `web/dashboard.html` is a map-led replay
of a parallel directed-march caravan run — see [`web/README.md`](web/README.md).

## Roadmap

Toward a playable game, headless-first:

- **Household housing** — the 56-rung dwelling ladder, driving demand for
  construction materials (`docs/household-housing.md`).
- **Manufactured goods** — the demand-driven production chain over the imported
  326-good recipe graph; its data layer is done, the runtime is next
  (`docs/manufactured-bonuses.md`).
- **Caravan trade** — settlement-sponsored trade caravans coupling economies
  over the road network (`docs/caravan-trade.md`).
- **Population renewal** — calibrating food and survival so births can sustain
  a colony (`docs/births.md`, `docs/food-balance.md`).
- **A player seat** — the presentation layer and player role, deliberately
  deferred until the world underneath is worth playing in.

## Credits

The economic core descends from an agent-based macroeconomic model written by
**Zhihong Xu** (~2011); CivStudio grows it into a game engine — the geography,
society, calendar, caravan and data-import layers are new. Map and content data
derive from the **Anbennar** EU4 mod and the **Caveman2Cosmos** Civilization IV
mod.

## License

Licensed under the **Apache License, Version 2.0** — see [`LICENSE`](LICENSE)
and [`NOTICE`](NOTICE).
