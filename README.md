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
4. **Printers** write a row of CSV; the in-game date advances by one day.

### Agents

- **Laborers** earn wages, choose consumption vs. saving (a target-savings model
  sensitive to the real interest rate), buy Necessity and Enjoyment goods, eat
  one unit of Necessity per day, and die if they cannot eat.
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

## Build & run

Requirements: **JDK 21** and **Maven**. (Lombok is pulled in as a build-time
dependency; no manual setup needed.)

```bash
mvn clean compile          # compile
mvn exec:exec              # run Simulation1 in a forked JVM with assertions (-ea)
mvn test                   # run the JUnit 5 test suite
mvn package                # build the jar
```

`exec:exec` runs with assertions enabled, because the code uses `assert` as real
invariant checks. Select a different entry point with the `sim.main` property:

```bash
mvn exec:exec -Dsim.main=eos.simulation.Simulation2   # heterogeneous agents
mvn exec:exec -Dsim.main=eos.simulation.Simulation3   # two-bank example
```

The three entry points are:

| Class         | Description                                                        |
|---------------|--------------------------------------------------------------------|
| `Simulation1` | Homogeneous agents, a single bank.                                 |
| `Simulation2` | Heterogeneous agents (randomized initial state), a single bank.    |
| `Simulation3` | Two banks, with agents split across them (a multi-bank example).   |

Each consists of a `static run()` that builds and runs the economy via
`SimulationHarness`, plus a `main()` that calls it.

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
working directory). The first column of every file is the in-game **date**
(`yyyy-MM-dd`). Discrete events — an agent dying, a price skyrocketing — are
logged to stderr, prefixed with the in-game date, e.g.:

```
1452-03-14: WARN Enjoyment skyrocketed to 51.30 (>10x init)
```

## Reproducibility

Each simulation seeds the RNG (`StdRandom.setSeed(...)`) at the start of its
run, so the same seed yields byte-identical output.

## Tests

`mvn test` runs a JUnit 5 smoke test per simulation: each runs the full
25-year economy (assertions on) and checks it stays healthy — most laborers
survive, prices stay finite and positive, and banks remain sound. The model
keeps state in static singletons, so Surefire forks a fresh JVM per test class.

## Credits

`eos` was originally written by **Zhihong Xu** (~2011) and is being modernized
(Maven build, Java 21, configuration records, tests, multi-bank support). The
agent behavior and market-clearing model are his; see `CLAUDE.md` for detailed
architecture notes.

## License

Licensed under the **Apache License, Version 2.0** — see [`LICENSE`](LICENSE)
and [`NOTICE`](NOTICE).
