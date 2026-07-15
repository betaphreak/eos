# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Keep this file lean.** It is the orientation map only. The detailed as-built reference lives in [`docs/architecture.md`](docs/architecture.md); per-feature design notes live in `docs/*.md`. When a change adds or reworks a subsystem, document it there — this file gets at most a one-line pointer.

## What this is

`eos` (public name **CivStudio**) is an agent-based civilization simulation in plain Java 25: settlements of laborer/noble/ruler households, firms, multi-currency banks and daily-clearing markets, founded into a real imported world map (Anbennar EU4) with per-plot terrain, solar daylight, a liturgical rest-day calendar, RimWorld-style skills, real mortality, weddings and births, a peasant labor pool, wandering caravans and a tech tree. Runs are headless — CSV time series plus an event log under `output/<seed>/` — and seed-reproducible (economic, naming, mortality, skill and terrain draws ride separate salted RNG streams).

The root package is `com.civstudio` (the repository directory is named `eos`, but the Java package is not), standard Maven layout under `src/main/java/com/civstudio/`.

## Build & run

Maven **reactor** with two modules — `civstudio-engine` (the plain-Java sim core) and `civstudio-server` (a **Spring Boot 4** app depending on the engine). Java 25; both modules on JUnit 6 (6.0.3 — the engine pins it via `junit.version`, matching what Spring Boot 4.1 brings the server). Toolchain on this machine: Temurin JDK 25 at `C:\Users\Eu\tools\jdk-25.0.3.9-hotspot` (user `JAVA_HOME`), Maven 3.9.9 at `C:\Users\Eu\tools\apache-maven-3.9.9` (both on the user `PATH`; a `./mvnw` wrapper is also committed). Run all `mvn` commands from the repo root. Module split and the Spring Boot migration are documented in [`docs/spring-boot-migration.md`](docs/spring-boot-migration.md).

```powershell
mvn clean compile                                   # compile both modules
mvn -pl civstudio-engine exec:exec                  # run the default scenario (HomogeneousEconomy), forked JVM with -ea
mvn -pl civstudio-engine exec:exec -Dsim.main=com.civstudio.simulation.TwinSettlementEconomy   # another scenario
mvn test                                            # engine + server suites (each scenario runs full as a smoke test)
mvn package                                         # build the Spring Boot server fat jar (civstudio-server)
mvn -pl civstudio-server spring-boot:run            # run the spectator server → http://localhost:8080
```

`exec:exec` forks a JVM with **assertions enabled** — the code uses `assert` as real invariant checks. A scenario is a `static run()` that builds a colony via `SimulationHarness` and returns it, plus a `main()` that calls it; the scenario inventory is the README table, with mechanics detailed in `docs/architecture.md` §Scenarios. Output CSVs are written under `output/<seed>/` relative to the working directory (the project root when launched via Maven).

**Web visualization** — `web/` is a dependency-free static site (`index.html` + `app.js` + baked `assets/`) presenting a **WorldMap** of the whole imported world with real recolored terrain, all province polygons, and per-plot terrain zoom (textures/hillshade/rivers/features); a **Caravan** mode toggles the live server session over it. The map/geo `window.BUNDLE` is fetched from the server (`GET /api/bundle`, assembled by `server.web.WorldBundle`) rather than a committed `data.js` — so the page needs the server reachable (else a *Maintenance Mode* splash). Read-only consumer of the committed `map/` resources; never touches the engine. Whole-world per-plot data comes from `geo/export/WorldPlotGenerator` (gitignored caches); rebuild the page with `node web/build.mjs [seed]`. Details in [`web/README.md`](web/README.md); the river ribbon (real 2D water art, tapered by width recovered from `rivers.bmp`) is in [`docs/river-rendering.md`](docs/river-rendering.md). The frontend is organized around a continuous-zoom **band spine** — nine bands (`js/bands.mjs`), three interaction regimes, a z-level-keyed draw registry (`js/layers.mjs`), the underworld as a `z:[-1]` layer set, and a city-micro footprint skeleton at the deep end — in [`docs/zoom-bands.md`](docs/zoom-bands.md) (built & live; that doc also holds the **planned** engine z-levels: `province.z`, z=0 impassable caps, plots per `(province, z)`).

**Local source caches (dev speed-up).** The on-demand Anbennar/C2C fetches (`data.AnbennarFiles`/`web/anbennar.mjs`, `data.Civ4Files`/`web/civ4.mjs`) and the Civ6 art each resolve through a per-source cache dir. On this machine those are **directory junctions** to local clones / the Steam SDK, so bakes read from disk with no network:
- `.anbennar-cache/<ref>` → `C:\Code\anbennar-eu4-dev` (ref = `civstudio-engine/src/main/resources/anbennar-source.lock`; GitLab `new-master`)
- `.civ4-cache/<ref>` → `C:\Code\Caveman2Cosmos` (ref = `.../civ4-source.lock`)
- `.civ6-cache` → the Steam **Civ VI SDK Assets** (`…/steamapps/common/Sid Meier's Civilization VI SDK Assets`)

Keep each clone on the locked ref (both match today). Delete a junction to fall back to on-demand fetch. Git Bash `find`/glob can't traverse a junction — use PowerShell or Node `fs`.

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
- **Plots & land** — a colony grows a list of terrain-bearing build plots (Civ4 terrain/feature/improvement/bonus, relief, travel ladder), hard-capped by its province; only farms sit on plots; plot food yield feeds farm TFP. §Settlement build plots; `docs/plots.md`, `docs/province-plots.md` (the **live** terrain pipeline — read it over older docs). The Civ4/C2C art + XML these are exported from is **not vendored** — it's fetched on demand from the C2C GitHub repo (dev-time only) by `com.civstudio.data.Civ4Files` / `web/civ4.mjs`; `docs/civ4-files.md`.
- **Markets** — consumer goods (binary-search price in a ±zeta band, unmet-demand pressure signal), labor (wage-budget allocation; skill-, daylight- and commute-scaled output; on-the-job training), capital, weddings. §Markets.
- **Dynamic firm provisioning** — the ruler charters/dissolves consumer firms monthly from utilization/pressure signals, with hysteresis. §Dynamic firm provisioning.
- **Ennoblement** — the aristocracy is raised from the ablest laborers (no founding nobles); hereditary succession. §Ennoblement.
- **Banking & currencies** — agent-routed payments, loans as negative savings, copper/silver/gold tiers with money-changer FX fees, equity as the inheritance/open-colony seam. §Bank.
- **Agents** — laborers (target-savings consumers), firms (Cobb-Douglas), nobles (rentier owners), ruler (taxing treasury that works the export firm), builder (peasant-staffed growth), peasant pool, marriage. §Agents.
- **Goods & rations** — `RationSize` hierarchy (gourmet → relief) ties diet to class; `Cargo` is the caravans' per-good inventory. §Goods, printers, utils.
- **Reporting** — typed printers → consolidated monthly CSVs (told apart by a column); `SimLog` event log with per-colony prefixes, level-by-frequency, annual digest. §Goods, printers, utils.
- **People** — `name/` (weighted, rarity-aware, unique recycled dynasty surnames): only **human** names are hand-authored/committed (`/human-names/`); every other race is generated on demand from Anbennar (`RaceNameGenerator`/`NameStore`, cached + gitignored under `generated/names/`) — `docs/names.md`. `skill/` (12 skills, passions, train-vs-decay), `mortality/` (Coale-Demeny life tables, salted demographic RNGs). §Goods, printers, utils.
- **Daylight & geography** — vendored solar calculator; the 5264-province `WorldMap` with areas/regions/climate; province plot fields; two-level land routing; caravans that march, forage and gather by daylight. §Daylight and the solar package; `docs/solar.md`, `docs/geography.md`, `docs/land-routing.md`, `docs/caravan*.md`.
- **Calendar** — `DayType` (workday/weekend/holiday) gates which firms operate; the couplings that make rest days survivable are calibration-sensitive. §The liturgical calendar; `docs/calendar.md`.
- **Tech tree** — ruler-funded science from the aristocracy's INTELLECTUAL labor; per-sector productivity multipliers. `docs/tech-tree.md`. **Buildings** — 1,270 C2C buildings imported gated to the kept-tech horizon (`generated/buildings.json` via `BuildingInfoExporter`), each tech's `Unlock(BUILDING_*)` in the generated `building-unlocks.json` overlay (merged by `TechTree`); button-icon bake in `web/build-buildings.mjs`. `docs/c2c-building-import.md`.
- **Races** — per-person ancestry varying names, mortality, calendar and tech overlay. `docs/race.md`.
- **Political layer** — canonical Anbennar province ownership (`Province.ownerTag`/`culture`/`religion` + `Country`/`Culture`/`Religion` records + `WorldMap.provincesByOwner`/`ByCulture`/`ByReligion`), stamped from vendored EU4 history by dev-tool exporters; drives the web **Political** map mode. `docs/political-map.md`.
- **Trade goods** — per-province EU4 resource (`Province.tradeGood` + `TradeGood`/`TradeGoodClass` records + `WorldMap.provincesOfTradeGood`), stamped from Anbennar history by `ProvinceHistoryExporter` / `TradeGoodExporter`; reference data (nothing consumes it yet), drawn as a real per-province Anbennar icon in the web viewer (`bakeTradeGoodIcons` → `tradegoods.js` → `plots.drawTradeGoodIcons`). Per-province (cf. the per-plot `Bonus`). `docs/trade-goods.md`.
- **Underworld + special terrains** — the underground Serpentspine as a second map plane (four Dwarovar `ProvinceType`s — `CAVERN`/`DWARVEN_HOLD`/`DWARVEN_HOLD_SURFACE`/`DWARVEN_ROAD` — stamped by `CavernExporter`): sun-free 14h `FixedDaylightClock`, food-scarce `TERRAIN_CAVERN`, dimmed-ghost viewer plane. The same machinery promotes seven distinctive **surface** terrains (ancient/fey/blood forests, mushroom, shadow swamp, glacier) to their own types with bespoke terrain/art/yields (`city_terrain` deferred). `docs/underworld.md`.
- **Client/server (spectator)** — `com.civstudio.server`: a **Spring Boot 4** app (MVC on virtual threads). A tick-authoritative `HostedSession` (pausable/rate-limited lockstep loop + tick-stamped `CommandLog` seam) that the `SessionHost` bean runs many-per-JVM, streaming a read-only render snapshot to the browser over SSE. Transport is `web.SessionController`/`BundleController`/`PageController` (`SseEmitter`, drop-oldest queue); `ServerMain` is the `@SpringBootApplication`, `DemoSessionSeeder` founds the six-caravan demo; Actuator exposes health (liveness/readiness) the site polls during its splash. Factorio-shaped spine, browser as thin render-client; the session's savegame is its `SessionSpec` + command log, replayed deterministically. **Deployed live** on Azure Container Apps at `dev.civstudio.com` (the caravan demo). An **admin console** (`web/admin.html` at `/`, gated by the existing `ROLE_ADMIN`/`civstudio.auth.admins` allow-list) manages the running server — drop/warm the plot cache, sessions, fetch caches; `docs/admin-console.md`. `docs/client-server.md` (incl. §Deployment — the **deployment runbook**: rebake bundle → `tools/deploy-server.ps1` → **clear the persistent plot cache** on a generation change → SWA web deploy) + `docs/spring-boot-migration.md` (the guest-identity/ACR-region constraints that shape it).
- **MCP tool surface** — the sim as typed tools for an LLM. A Spring AI 2.0 MCP server co-hosted in `civstudio-server` (`spring-ai-starter-mcp-server-webmvc`, Streamable HTTP at `/mcp`) exposes read-only tools/resources/prompts over the live `SessionHost` (`com.civstudio.server.mcp`: `list_sessions`/`get_snapshot`/`get_person`/`get_command_log`/`get_events` tools, `civstudio://session/{id}/snapshot|events` resources served from a retained `SessionEventLog` tail, `diagnose-live-colony` `@McpPrompt`). Dev-gated **calibration** tools ride the same endpoint (`civstudio.mcp.calibration.enabled`, on under the `dev` profile): `list_scenarios` / `run_scenario` / `sweep` (`ScenarioMcpTools`) found a standard colony via `simulation.CalibrationRun` and write a typed **SQL run store** (H2/Postgres via the `io/sink` `JdbcRowSink`/`CompositeRowSinkFactory` seam, keyed by `runId`); `list_outputs` / `query_timeseries` / `compare_runs` (`CalibrationQueryTools`) read it back (identifiers validated against the live schema). `sweep` **retired the `CalibrationSweep` dev tool**. Live-session **write** tools (admin-gated) and `get_event_log` for finished runs remain to build. `docs/mcp-server.md`.

## Tests

`mvn test` runs each scenario full-length as a smoke test; ruler-colony tests assert a **clean collapse**, `SmallOpenEconomyTest` asserts growth and stability, and targeted tests cover individual mechanisms (the inventory is in `docs/architecture.md` §Scenarios). All sim state is per-instance — including the event log, now routed per session rather than through a process-global handler (the client/server Phase 0 work) — so Surefire runs the suite in one reused JVM (`reuseForks=true`, assertions on).

## Conventions

- Reproducibility hinges on the single seed set at the top of `main`; the same seed yields identical runs. Never let a new feature consume from the economic RNG — new random draws get their own salted stream.
- Behavioral parameters live in an immutable per-owner `*Config` record (`FirmConfig`, `BankConfig`, `NobleConfig`, `RetinueConfig`, …) with a hand-written `DEFAULT` as the source of truth and a Lombok `@Builder(toBuilder = true)` for cheap variants. Structural/non-tunable constants stay `static final` on the owning class. Run-level parameters live in `SimulationConfig` (also a record with `DEFAULT`; each `main` binds it locally — only the seed and init logic stay in `main`). Full field inventory: `docs/architecture.md` §Configuration reference.
- There is no `numLaborers`: a pool colony's labor force is `round(promotionRatio · retinueSize)`; only the bare `SmallOpenEconomy` passes an explicit count.
- When adding a market or good, register the market with `Settlement.addMarket(...)` **before** constructing agents that reference it — agent constructors look markets up by good name.
- Display names: `Agent.getName()` defaults to the class simple name (subclasses call `setName`); markets/banks carry their own (`"<Good> Market"`, `"Bank <n>"` per colony). `toString()` overrides are concise and side-effect-free (safe on dead agents).
- The colony has a lifecycle: `start()` marks it founded; it dies the step its last laborer is gone; `run(steps)` stops early on death, and no-arg `run()` runs until death. Mortality is always on — there is no toggle.
- The in-game date is the canonical display unit (SimLog prefix, progress counter, every CSV's first `Date` column); the integer `timeStep` is internal control flow only.
