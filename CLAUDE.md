# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Keep this file lean.** It is the orientation map only. The detailed as-built reference lives in [`docs/architecture.md`](docs/architecture.md); per-feature design notes live in `docs/*.md`. When a change adds or reworks a subsystem, document it there — this file gets at most a one-line pointer.

## What this is

`eos` (public name **CivStudio**) is an agent-based civilization simulation in plain Java 21: settlements of laborer/noble/ruler households, firms, multi-currency banks and daily-clearing markets, founded into a real imported world map (Anbennar EU4) with per-plot terrain, solar daylight, a liturgical rest-day calendar, RimWorld-style skills, real mortality, weddings and births, a peasant labor pool, wandering caravans and a tech tree. Runs are headless — CSV time series plus an event log under `output/<seed>/` — and seed-reproducible (economic, naming, mortality, skill and terrain draws ride separate salted RNG streams).

The root package is `com.civstudio` (the repository directory is named `eos`, but the Java package is not), standard Maven layout under `src/main/java/com/civstudio/`.

## Build & run

Maven project, Java 21, JUnit 5. Toolchain on this machine: Temurin JDK 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (user `JAVA_HOME`), Maven 3.9.9 at `C:\Users\Eu\tools\apache-maven-3.9.9` (both on the user `PATH`).

```powershell
mvn clean compile          # compile to target/classes
mvn exec:exec              # run the default scenario (HomogeneousEconomy) in a forked JVM with -ea
mvn exec:exec -Dsim.main=com.civstudio.simulation.TwinSettlementEconomy   # another scenario
mvn test                   # JUnit 5 suite (each scenario runs full as a smoke test)
mvn package                # build the jar
```

`exec:exec` forks a JVM with **assertions enabled** — the code uses `assert` as real invariant checks. A scenario is a `static run()` that builds a colony via `SimulationHarness` and returns it, plus a `main()` that calls it; the scenario inventory is the README table, with mechanics detailed in `docs/architecture.md` §Scenarios. Output CSVs are written under `output/<seed>/` relative to the working directory (the project root when launched via Maven).

**Web visualization** — `web/` is a dependency-free static site (`index.html` + `app.js` + generated `data.js`) presenting a **WorldMap** of the whole imported world with real recolored terrain, all province polygons, and per-plot terrain zoom (textures/hillshade/rivers/features); a **Caravan** mode toggles a run's caravan replay over it. Read-only consumer of the committed `map/` resources + `output/<seed>/`; never touches the engine. Whole-world per-plot data comes from `geo/export/WorldPlotGenerator` (gitignored caches); rebuild the page with `node web/build.mjs [seed]`. Details in [`web/README.md`](web/README.md); the river ribbon (real 2D water art, tapered by width recovered from `rivers.bmp`) is in [`docs/river-rendering.md`](docs/river-rendering.md).

**Every standard (ruler-bearing) settlement gets the same defaults**, wired by `SimulationHarness`: a gold-banking **ruler**, a **strategic export sector**, the three-tier **copper/silver/gold banks**, a **peasant pool** (`Retinue`) plus `BuilderFirm` from which the labor force is founded and replaced, an aristocracy **raised from the laborers by ennoblement**, and monthly **dynamic firm provisioning**. Only the bare `SmallOpenEconomy` and the developer sweeps skip these. **Ruler colonies collapse by design** once the pool's reserve drains (the labor model is replacement-only; births are implemented but don't yet outpace the decline) — the smoke tests assert the collapse is *clean* (`SimulationAssertions.assertCollapsed`), and `SmallOpenEconomy` stays stable. See `docs/architecture.md` §Scenarios and §Agents → Peasant pool; design notes in `docs/peasant-pool.md`, `docs/births.md`, `docs/food-balance.md`.

## Architecture in one page

A `GameSession` owns the seed and creates named `Settlement` instances from it; several settlements can run **concurrently, one thread each, in lockstep** via `SessionRunner`, with per-colony RNG streams, name slices and agent-ID spaces. **Each step is one in-game day.** `Settlement.newDay()` runs a fixed phase order:

1. Recompute the day's **solar times** for the colony's location and date.
2. `agent.act()` — agents read *last* step's prices/income and **post buy/sell offers** (no transaction yet); deaths are flagged.
3. `bank.act()` — interest rates set, interest paid/charged.
4. Remove the dead, spawn **successors** (replacement policies), run step actions, admit immigrants.
5. `market.clear()` — **transactions actually settle here**, at a discovered price.
6. Printers (one row per in-game month); inflation update.

**The key mental model: `act()` posts intentions against yesterday's state; `clear()` executes them.** Construction order in a simulation matters because of this deferred settlement.

Subsystem map — one line each; the as-built detail for all of them is in [`docs/architecture.md`](docs/architecture.md), under the matching section:

- **Step loop, sessions, concurrency** — lockstep threads, per-colony reproducibility, replacement policies. §The step loop.
- **Plots & land** — a colony grows a list of terrain-bearing build plots (Civ4 terrain/feature/improvement/bonus, relief, travel ladder), hard-capped by its province; only farms sit on plots; plot food yield feeds farm TFP. §Settlement build plots; `docs/plots.md`, `docs/province-plots.md` (the **live** terrain pipeline — read it over older docs).
- **Markets** — consumer goods (binary-search price in a ±zeta band, unmet-demand pressure signal), labor (wage-budget allocation; skill-, daylight- and commute-scaled output; on-the-job training), capital, weddings. §Markets.
- **Dynamic firm provisioning** — the ruler charters/dissolves consumer firms monthly from utilization/pressure signals, with hysteresis. §Dynamic firm provisioning.
- **Ennoblement** — the aristocracy is raised from the ablest laborers (no founding nobles); hereditary succession. §Ennoblement.
- **Banking & currencies** — agent-routed payments, loans as negative savings, copper/silver/gold tiers with money-changer FX fees, equity as the inheritance/open-colony seam. §Bank.
- **Agents** — laborers (target-savings consumers), firms (Cobb-Douglas), nobles (rentier owners), ruler (taxing treasury that works the export firm), builder (peasant-staffed growth), peasant pool, marriage. §Agents.
- **Goods & rations** — `RationSize` hierarchy (gourmet → relief) ties diet to class; `Cargo` is the caravans' per-good inventory. §Goods, printers, utils.
- **Reporting** — typed printers → consolidated monthly CSVs (told apart by a column); `SimLog` event log with per-colony prefixes, level-by-frequency, annual digest. §Goods, printers, utils.
- **People** — `name/` (weighted, rarity-aware, unique recycled dynasty surnames), `skill/` (12 skills, passions, train-vs-decay), `mortality/` (Coale-Demeny life tables, salted demographic RNGs). §Goods, printers, utils.
- **Daylight & geography** — vendored solar calculator; the 5264-province `WorldMap` with areas/regions/climate; province plot fields; two-level land routing; caravans that march, forage and gather by daylight. §Daylight and the solar package; `docs/solar.md`, `docs/geography.md`, `docs/land-routing.md`, `docs/caravan*.md`.
- **Calendar** — `DayType` (workday/weekend/holiday) gates which firms operate; the couplings that make rest days survivable are calibration-sensitive. §The liturgical calendar; `docs/calendar.md`.
- **Tech tree** — ruler-funded science from the aristocracy's INTELLECTUAL labor; per-sector productivity multipliers. `docs/tech-tree.md`.
- **Races** — per-person ancestry varying names, mortality, calendar and tech overlay. `docs/race.md`.
- **Political layer** — canonical Anbennar province ownership (`Province.ownerTag`/`culture`/`religion` + `Country`/`Culture`/`Religion` records + `WorldMap.provincesByOwner`/`ByCulture`/`ByReligion`), stamped from vendored EU4 history by dev-tool exporters; drives the web **Political** map mode. `docs/political-map.md`.
- **Underworld** *(in progress)* — a second map plane for the underground Serpentspine, defined by the `cavern` terrain → new `ProvinceType.CAVERN` (stamped by `CavernExporter`): sun-free 14h "sweatshop" labor, cavern/mushroom terrain, dimmed-surface-ghost viewer plane. Phases 1–3 done (the `CAVERN` type + the sun-free `FixedDaylightClock` + the food-scarce `TERRAIN_CAVERN` cave floor); web viewer plane + cave art planned. `docs/underworld.md`.

## Tests

`mvn test` runs each scenario full-length as a smoke test; ruler-colony tests assert a **clean collapse**, `SmallOpenEconomyTest` asserts growth and stability, and targeted tests cover individual mechanisms (the inventory is in `docs/architecture.md` §Scenarios). Surefire keeps `reuseForks=false` — the `SimLog` handler is a process-global static, so each test class gets a fresh JVM (which also keeps `-ea` on).

## Conventions

- Reproducibility hinges on the single seed set at the top of `main`; the same seed yields identical runs. Never let a new feature consume from the economic RNG — new random draws get their own salted stream.
- Behavioral parameters live in an immutable per-owner `*Config` record (`FirmConfig`, `BankConfig`, `NobleConfig`, `RetinueConfig`, …) with a hand-written `DEFAULT` as the source of truth and a Lombok `@Builder(toBuilder = true)` for cheap variants. Structural/non-tunable constants stay `static final` on the owning class. Run-level parameters live in `SimulationConfig` (also a record with `DEFAULT`; each `main` binds it locally — only the seed and init logic stay in `main`). Full field inventory: `docs/architecture.md` §Configuration reference.
- There is no `numLaborers`: a pool colony's labor force is `round(promotionRatio · retinueSize)`; only the bare `SmallOpenEconomy` passes an explicit count.
- When adding a market or good, register the market with `Settlement.addMarket(...)` **before** constructing agents that reference it — agent constructors look markets up by good name.
- Display names: `Agent.getName()` defaults to the class simple name (subclasses call `setName`); markets/banks carry their own (`"<Good> Market"`, `"Bank <n>"` per colony). `toString()` overrides are concise and side-effect-free (safe on dead agents).
- The colony has a lifecycle: `start()` marks it founded; it dies the step its last laborer is gone; `run(steps)` stops early on death, and no-arg `run()` runs until death. Mortality is always on — there is no toggle.
- The in-game date is the canonical display unit (SimLog prefix, progress counter, every CSV's first `Date` column); the integer `timeStep` is internal control flow only.
