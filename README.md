# eos

An agent-based macroeconomic simulation written in plain Java. `eos` models a
closed economy of laborers, firms, and a banking system, where markets clear by
supply and demand once per simulated day. Running a simulation produces CSV
time-series (prices, volumes, firm finances, bank rates, laborer state) for
analysis.

Each simulation step is **one in-game day**. The default run starts on
**11 December 1444** and spans **25 years** (9,131 daily steps).

## The model

The economy advances in a fixed phase order every day (`Economy.newDay()`):

1. **Agents act** — each agent reads yesterday's prices/income and posts buy/sell
   offers to markets (no transaction happens yet).
2. **Banks act** — each bank recomputes total loans/deposits, sets the loan and
   deposit interest rates, and pays/charges interest.
3. **Markets clear** — this is where transactions actually settle, by finding a
   price where supply ≈ demand.
4. **Printers** write a row of CSV (one row per in-game month, on the first of
   the month); the in-game date advances by one day.

### Agents

- **Laborers** earn wages, choose consumption vs. saving (a target-savings model
  sensitive to the real interest rate), buy Necessity and Enjoyment goods, eat
  one unit of Necessity per day, and die if they cannot eat. Each laborer is a
  household whose head ages and dies of old age on a real mortality schedule
  (Coale-Demeny West Level 3); a successor household of the same dynasty inherits
  the estate, so money and the labor force stay in circulation.
- **Firms** come in three kinds: `CFirm` produces capital (machines), while
  `EFirm` (Enjoyment) and `NFirm` (Necessity) combine labor and capital through a
  **Cobb-Douglas production function** `A·L^β·K^(1-β)`, adjusting output, wages,
  and capital investment in response to profit and interest rates.
- **Banks** are the payment and credit system. Every agent holds a checking and a
  savings account at a bank; a negative savings balance is a loan. Payments are
  agent-routed (a transfer debits the payer's bank and credits the payee's), so
  the economy can run with **more than one bank**.

### Markets

`ConsumerGoodMarket` (Enjoyment, Necessity) binary-searches for a clearing price
bounded to ±10% of yesterday's price; `LaborMarket` allocates workers in
proportion to each firm's wage budget; `CapitalMarket` matches firms buying
machines against capital producers.

### Settlement size and slots

A settlement occupies a disc of radius `size` with a fixed number of build
**slots** that hold its firms (and, later, housing). A precalculated table maps
each size to its slot counts: of the total slots (≈ `π·size²`), a
linearly-growing share goes to **roads** (congestion) and a circumference-sized
share to **walls**, leaving the **effective** slots that firms occupy. A colony
is founded at size 3 (15 effective slots) and **grows just enough to fit its
firms** — the 22-firm default colonies reach size 4, the smaller ones stay at 3.
The table is loaded once and held on the `GameSession`, shared by every colony,
since it is pure geometry (independent of seed and location). Placing a firm on
a slot moves no money and draws no randomness, so it leaves runs reproducible.

## Build & run

Requirements: **JDK 21** and **Maven**. (Lombok is pulled in as a build-time
dependency; no manual setup needed.)

```bash
mvn clean compile          # compile
mvn exec:exec              # run HomogeneousEconomy in a forked JVM with assertions (-ea)
mvn test                   # run the JUnit 5 test suite
mvn package                # build the jar
```

`exec:exec` runs with assertions enabled, because the code uses `assert` as real
invariant checks. Select a different entry point with the `sim.main` property:

```bash
mvn exec:exec -Dsim.main=eos.simulation.HeterogeneousEconomy   # heterogeneous agents
mvn exec:exec -Dsim.main=eos.simulation.TwoBankEconomy   # two-bank example
```

The entry points are:

| Class                  | Description                                                                                 |
|------------------------|---------------------------------------------------------------------------------------------|
| `HomogeneousEconomy`   | Homogeneous agents, a single bank (the default run).                                         |
| `HeterogeneousEconomy` | Heterogeneous agents (randomized initial state), a single bank.                              |
| `TwoBankEconomy`       | Two banks, with agents split across them (a multi-bank example).                             |
| `ScaleSweep`           | Scales the firm/laborer counts down together to find the smallest economy that stays stable. |
| `SmallOpenEconomy`     | An economy opened to external money inflow + immigration, growing past its starting size.    |
| `AristocraticEconomy`  | The default economy plus noble owner households that draw firm and bank dividends.           |

Each consists of a `static run()` that builds and runs the economy via
`SimulationHarness`, plus a `main()` that calls it — except `ScaleSweep`, whose
`main()` runs the multi-economy sweep and prints a stability table to stdout.

## Configuration

A run is configured through `SimulationConfig` — an immutable record (with a
`DEFAULT`) holding the calendar (`startDate`, `durationYears`), population sizes,
market price bounds, and each agent type's initial state. Model parameters for
agents and banks live in their own config records (`LaborerConfig`,
`FirmConfig`, `BankConfig`), each with a `DEFAULT` and a Lombok builder, so a
variant run can be derived cheaply:

```java
SimulationConfig.DEFAULT.toBuilder().durationYears(10).build();
BankConfig.DEFAULT.toBuilder().spread(0.005).build();   // a profit-making bank
```

## Output

Each run writes CSV files to an `output/` directory (created relative to the
working directory), one row per in-game month. The first column of every file is
the in-game **date** (`yyyy-MM-dd`). Floating-point cells are formatted to two
decimals; the bank's interest-rate and inflation columns are shown as percents
(e.g. `0.45%`). Discrete events — an agent dying, a price skyrocketing — are
logged to stderr, prefixed with the in-game date, e.g.:

```
1452-03-14: WARN Enjoyment skyrocketed to 51.30 (>10x init)
```

## Reproducibility

Each run's `GameSession` seeds its `Rng` from a single seed and shares it with
the economy it creates, so the same seed yields byte-identical output. (Naming
and mortality draw from separate salted RNGs, so they never perturb the economic
stream.)

## Tests

`mvn test` runs a JUnit 5 smoke test per simulation: each runs the full
25-year economy (assertions on) and checks it stays healthy — most laborers
survive, prices stay finite and positive, and banks remain sound. Economy state
is per-instance, but the logging handler is a process-global static, so Surefire
forks a fresh JVM per test class to isolate it.

## Credits

`eos` was originally written by **Zhihong Xu** (~2011) and is being modernized
(Maven build, Java 21, configuration records, tests, multi-bank support). The
agent behavior and market-clearing model are his; see `CLAUDE.md` for detailed
architecture notes.

## License

Licensed under the **Apache License, Version 2.0** — see [`LICENSE`](LICENSE)
and [`NOTICE`](NOTICE).
