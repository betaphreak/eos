# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`eos` is an agent-based macroeconomic simulation written in plain Java. It models a closed economy of laborers, firms (capital, enjoyment, necessity), a bank (one by default, though the design now allows several), and markets that clear by supply/demand each time step. Running a simulation produces CSV time-series files for analysis (prices, volumes, firm finances, bank rates, laborer state).

The root package is `eos`, under the standard Maven layout at `src/main/java/eos/` (e.g. `src/main/java/eos/agent/firm/CFirm.java` is `eos.agent.firm.CFirm`).

## Build & run

Maven project, Java 21 (`pom.xml`). There is no test suite yet (`src/test/java` does not exist).

```powershell
mvn clean compile          # compile to target/classes
mvn exec:exec              # run Simulation1 (forks a JVM with -ea)
mvn exec:exec -Dsim.main=eos.simulation.Simulation2   # run another entry point
mvn package                # build the jar in target/
```

`exec:exec` runs in a **forked JVM with assertions enabled** (`-ea`) because the code uses `assert` as real invariant checks; the main class is the `sim.main` property (default `eos.simulation.Simulation1`). The entry points (each has a `main`) are `Simulation1` (homogeneous agents), `Simulation2` (heterogeneous, randomized init), and `Simulation3` (two banks, agents split across them — a worked example of the multi-bank setup). A run is configured through `SimulationConfig.DEFAULT` read at the top of `main` (agent counts, initial balances, number of steps, print interval), plus the per-`main` seed and init logic.

Output CSVs are written to an `output/` directory created relative to the **current working directory** at runtime (i.e. the project root when launched via Maven; see `io/printer/CSVPrintWriter.java`). Runs are reproducible: each simulation's `main` calls `StdRandom.setSeed(...)`, so the same seed yields identical output.

Toolchain on this machine: Temurin JDK 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (set as user `JAVA_HOME`), Maven 3.9.9 at `C:\Users\Eu\tools\apache-maven-3.9.9` (both added to the user `PATH`).

## Architecture

### The step loop is the spine

`Economy` is a **static singleton** — all economy state is global. Banks are instances registered with the economy via `Economy.addBank(...)` (the model uses one today, but supports more). `Economy.run(steps)` calls `Economy.step()` repeatedly, and `step()` defines the fixed phase order every tick (`economy/Economy.java`):

1. `agent.act()` for every alive agent — agents read last step's prices/income and **post buy/sell offers** to markets (they do not transact yet).
2. Dead agents (flagged via `die()` during `act()`) are removed.
3. `bank.act()` for every registered bank — recomputes total loan/deposit, sets loan & deposit interest rates, pays/charges interest.
4. `market.clear()` for every market — **this is where transactions actually settle**.
5. `printer.print()` for every printer.
6. `updateInflation()` (CPI = mean of consumer-good market prices over `INFLATION_TIME_WIN`).

The key mental model: **`act()` posts intentions against last step's state; `clear()` executes them.** Order of construction in a simulation matters because of this deferred settlement (the labor market is cleared once manually before `run()` so firms have workers in step 0).

### Markets clear by finding a price

Each `Market` (subclass of `eos/market/Market.java`) collects offers during the act phase and settles in `clear()`:
- `ConsumerGoodMarket` binary-searches for a price where demand ≈ supply (bounded to ±`zeta` of last price), then fills all buyers/sellers pro-rata and moves money via `Bank`. Buyers express willingness via a `Demand` strategy object (`market/Demand.java`), not a fixed quantity.
- `LaborMarket` shuffles employers/employees and allocates workers proportionally to each firm's wage budget, paying wages through `Bank.pay(...)`.
- `CapitalMarket` matches firms buying machines against `CFirm` sellers.

### Bank is the payment + credit system

`Bank` (`bank/Bank.java`) is an **instance** class (constructed with a `BankConfig`); each agent holds its accounts at a specific `Bank` (`agent.getBank()`, injected through every constructor). A bank holds one `Account` per agent keyed by the agent's ID (which doubles as account number). Every account has a `CHECKING` and `SAVINGS` balance; a **negative savings balance is a loan**. Money only moves between checking accounts. Payments are **agent-routed**: a transfer is `payerBank.withdraw(payerID, amt)` + `payeeBank.credit(payeeID, amt, purpose)`, which is correct even when the two parties bank at different `Bank` instances (there is no combined `pay` — split it). The payment-purpose codes `Bank.PRIIC`/`SECIC`/`OTHER` stay `static` (shared across banks). Critically, agents read their last-step income from public `Account` fields — `priIC` (primary income: wage/sales), `secIC` (secondary: dividends), `interest` — and **reset these to 0 at the end of their own `act()`**. Loan interest rate responds to the loan/deposit gap; deposit rate redistributes collected interest to creditors.

### Agents

`Agent` (`agent/Agent.java`) is the abstract base: unique ID, alive flag, `act()`, and `getGood(name)`. Two families:

- **Laborers** (`agent/laborer/Laborer.java`) earn wages, decide consumption vs. savings (target-savings model sensitive to the real interest rate), buy Necessity and Enjoyment, eat one unit of Necessity per step, and `die()` if they can't eat.
- **Firms** (`agent/firm/`): `CFirm` produces Capital (machines); `ConsumerGoodFirm` (abstract) is subclassed by `EFirm` (Enjoyment) and `NFirm` (Necessity), which combine labor + capital via a **Cobb-Douglas production function** `A·L^β·K^(1-β)`. Consumer firms adjust output by marginal profit, adjust wage budget by cash-flow gap, and decide whether to buy/replace capital based on return-on-capital vs. interest rate and capacity utilization.

Note: `ConsumerGoodFirm.act()` contains explicitly-labeled **"hacks"** (capital-purchase nudges gated on `timeStep > 2000`) tuning the model's stability — treat that block as model calibration, not incidental logic.

### Goods, printers, utils

- `good/` — `Good` is a mutable quantity holder; `Capital`, `Labor`, `Necessity`, `Enjoyment` are the concrete goods.
- `io/printer/` — each `Printer` writes one CSV via `CSVPrintWriter`. Register printers with `Economy.addPrinter(...)` (which writes the header row immediately) and finalize with `Economy.cleanUpPrinters()` after the run.
- `io/SimLog.java` — event logging via `java.util.logging`. `SimLog.init()` (called first thing in each `main`) installs a formatter that prefixes every record with the current `Economy.getTimeStep()`, writing to **stderr** with per-record flush. Classes that emit events are annotated with Lombok `@Log` and call `log.info(...)` for events (e.g. a laborer dying) or `log.warning(...)` for anomalies (e.g. a price crossing `PRICE_SKYROCKET_FACTOR`×its initial level in `ConsumerGoodMarket`). This is for discrete events — bulk time-series still goes through printers to CSV. Note `Economy.run`'s per-1000-step progress counter still prints to stdout, so progress and events are on separate streams.
- `util/` — `StdRandom` (seeded RNG; **set `StdRandom.setSeed(...)` for reproducible runs**), `Averager` (rolling window average used for inflation and long-term rates), `In`/`Out` (Sedgewick-style I/O).

## Conventions

- Reproducibility hinges on the single seed set at the top of `main`; the same seed yields identical runs.
- When adding a new market or good, register the market with `Economy.addMarket(...)` **before** constructing agents that reference it — agent constructors look markets up by good name (`Economy.getMarket(name)`).
- Behavioral/model parameters of an agent or bank live in an immutable `*Config` record (`LaborerConfig`, `FirmConfig`, `BankConfig`) with a `DEFAULT` constant holding the canonical values; the owner stores it as a `final` field and the constructor takes it as a required param. This makes parameters per-instance (enabling heterogeneous populations / multiple banks) and tunable per run from the simulation's `main`. Structural/non-tunable constants — capital lifetime (`CFirm.CAPITAL_LIFE`), market price-band width (`ConsumerGoodMarket.zeta`), the inflation window on the `Economy` singleton (`INFLATION_TIME_WIN`), payment-purpose index constants (`Bank.PRIIC`/`SECIC`/`OTHER`) — stay as `static final` on the relevant class. The `Economy` singleton keeps its constants inline since there's only one instance. Inline calibration magic numbers (e.g. the `ConsumerGoodFirm.act()` "hacks") are deliberately left un-extracted.
- Run-level parameters (step/population counts, market price bounds, per-agent-type initial state) live in `SimulationConfig` — an immutable record of nested sub-records (`PriceRange`, `FirmInit`, `CFirmInit`, `LaborerInit`) with a `DEFAULT`. Each simulation's `main` binds `SimulationConfig cfg = SimulationConfig.DEFAULT` and reads values via accessors (`cfg.eFirm().checking()`); only the per-run differences stay in `main` — the **seed** and the **init logic** (`Simulation1` fixed vs. `Simulation2` randomizing around the config values). The simulations are entry points with no constructor, so the config is a `main`-local bound to `DEFAULT` rather than a constructor param.
- The config records carry Lombok `@Builder(toBuilder = true)`. The hand-written `DEFAULT` constants remain the source of truth (no `@Builder.Default`); the builder exists so a run can derive a variant cheaply — `SimulationConfig.DEFAULT.toBuilder().numStep(5000).build()` — and so multi-field value records can be constructed by name instead of positionally. Builders are additive; nothing in the default run path calls them. Lombok is already a `provided` dependency (`@Getter` is used on agents); `@Builder` on records needs Lombok ≥ 1.18.20 (project is on 1.18.34).
