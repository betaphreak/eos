# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`eos` is an agent-based macroeconomic simulation written in plain Java. It models a closed economy of laborers, firms (capital, enjoyment, necessity), a single bank, and markets that clear by supply/demand each time step. Running a simulation produces CSV time-series files for analysis (prices, volumes, firm finances, bank rates, laborer state).

The root package is `eos`, under the standard Maven layout at `src/main/java/eos/` (e.g. `src/main/java/eos/agent/firm/CFirm.java` is `eos.agent.firm.CFirm`).

## Build & run

Maven project, Java 21 (`pom.xml`). There is no test suite yet (`src/test/java` does not exist).

```powershell
mvn clean compile          # compile to target/classes
mvn exec:exec              # run Simulation1 (forks a JVM with -ea)
mvn exec:exec -Dsim.main=eos.simulation.Simulation2   # run the other entry point
mvn package                # build the jar in target/
```

`exec:exec` runs in a **forked JVM with assertions enabled** (`-ea`) because the code uses `assert` as real invariant checks; the main class is the `sim.main` property (default `eos.simulation.Simulation1`). `Simulation1` (homogeneous agents) and `Simulation2` are the two entry points (each has a `main`). Tuning a run means editing the `private static final` constants at the top of the simulation class — agent counts, initial balances, number of steps, print interval (`STEP_SIZE`).

Output CSVs are written to an `output/` directory created relative to the **current working directory** at runtime (i.e. the project root when launched via Maven; see `io/printer/CSVPrintWriter.java`). Runs are reproducible: each simulation's `main` calls `StdRandom.setSeed(...)`, so the same seed yields identical output.

Toolchain on this machine: Temurin JDK 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (set as user `JAVA_HOME`), Maven 3.9.9 at `C:\Users\Eu\tools\apache-maven-3.9.9` (both added to the user `PATH`).

## Architecture

### The step loop is the spine

`Economy` and `Bank` are entirely **static singletons** — all economy/bank state is global. `Economy.run(steps)` calls `Economy.step()` repeatedly, and `step()` defines the fixed phase order every tick (`economy/Economy.java`):

1. `agent.act()` for every alive agent — agents read last step's prices/income and **post buy/sell offers** to markets (they do not transact yet).
2. Dead agents (flagged via `die()` during `act()`) are removed.
3. `Bank.act()` — recomputes total loan/deposit, sets loan & deposit interest rates, pays/charges interest.
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

`Bank` (`bank/Bank.java`) holds one `Account` per agent keyed by the agent's ID (which doubles as account number). Every account has a `CHECKING` and `SAVINGS` balance; a **negative savings balance is a loan**. Money only moves between checking accounts. Critically, agents read their last-step income from public `Account` fields — `priIC` (primary income: wage/sales), `secIC` (secondary: dividends), `interest` — and **reset these to 0 at the end of their own `act()`**. Loan interest rate responds to the loan/deposit gap; deposit rate redistributes collected interest to creditors.

### Agents

`Agent` (`agent/Agent.java`) is the abstract base: unique ID, alive flag, `act()`, and `getGood(name)`. Two families:

- **Laborers** (`agent/laborer/Laborer.java`) earn wages, decide consumption vs. savings (target-savings model sensitive to the real interest rate), buy Necessity and Enjoyment, eat one unit of Necessity per step, and `die()` if they can't eat.
- **Firms** (`agent/firm/`): `CFirm` produces Capital (machines); `ConsumerGoodFirm` (abstract) is subclassed by `EFirm` (Enjoyment) and `NFirm` (Necessity), which combine labor + capital via a **Cobb-Douglas production function** `A·L^β·K^(1-β)`. Consumer firms adjust output by marginal profit, adjust wage budget by cash-flow gap, and decide whether to buy/replace capital based on return-on-capital vs. interest rate and capacity utilization.

Note: `ConsumerGoodFirm.act()` contains explicitly-labeled **"hacks"** (capital-purchase nudges gated on `timeStep > 2000`) tuning the model's stability — treat that block as model calibration, not incidental logic.

### Goods, printers, utils

- `good/` — `Good` is a mutable quantity holder; `Capital`, `Labor`, `Necessity`, `Enjoyment` are the concrete goods.
- `io/printer/` — each `Printer` writes one CSV via `CSVPrintWriter`. Register printers with `Economy.addPrinter(...)` (which writes the header row immediately) and finalize with `Economy.cleanUpPrinters()` after the run.
- `util/` — `StdRandom` (seeded RNG; **set `StdRandom.setSeed(...)` for reproducible runs**), `Averager` (rolling window average used for inflation and long-term rates), `In`/`Out` (Sedgewick-style I/O).

## Conventions

- Reproducibility hinges on the single seed set at the top of `main`; the same seed yields identical runs.
- When adding a new market or good, register the market with `Economy.addMarket(...)` **before** constructing agents that reference it — agent constructors look markets up by good name (`Economy.getMarket(name)`).
- Behavioral/model parameters of an agent live in an immutable `*Config` record (`LaborerConfig`, `FirmConfig`) with a `DEFAULT` constant holding the canonical values; the agent stores it as a `final` field and the constructor takes it as a required param. This makes parameters per-instance (enabling heterogeneous populations) and tunable per run from the simulation's `main`. Structural/non-tunable constants — capital lifetime (`CFirm.CAPITAL_LIFE`), market price-band width (`ConsumerGoodMarket.zeta`), averaging windows on singletons, account-field index constants (`Bank.PRIIC`/`SECIC`/`OTHER`) — stay as `static final` on the relevant class. Singletons (`Bank`, `Economy`) keep their constants inline since there's only one instance. Inline calibration magic numbers (e.g. the `ConsumerGoodFirm.act()` "hacks") are deliberately left un-extracted.
