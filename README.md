# eos

An agent-based macroeconomic simulation written in plain Java. `eos` models a
**colony** — closed by default, but optionally *opened* to a stream of external
money that bankrolls immigration — of laborers, firms (capital, enjoyment,
necessity), a banking system, a noble class that owns the firms and banks, and a
ruler who governs and spends. Markets clear by supply and demand once per
simulated day. A run produces CSV time-series (prices, volumes, firm finances,
bank rates, laborer and noble state) for analysis.

Each simulation step is **one in-game day**. The default run starts on
**11 December 1444** and spans **25 years** (9,131 daily steps), placed at
**London** (51.51°N) by default — location matters, because labor output is
scaled by the length of the day.

## The model

A colony is a `Settlement` (created from a `GameSession`, which holds the seed).
Several independent colonies can coexist in one session — they share a name pool
and demography but run independent economies. Each colony advances in a fixed
phase order every day (`Settlement.newDay()`):

1. **Recompute the day's solar times** for the colony's location and date
   (dawn / sunrise / sunset / dusk and the daylight duration).
2. **Agents act** — each agent reads yesterday's prices/income and posts
   buy/sell offers to markets (no transaction happens yet); a death is flagged.
3. **Banks act** — each bank recomputes total loans/deposits, sets the loan and
   deposit interest rates, and pays/charges interest.
4. **Remove the dead, spawn successors, run step actions, admit immigrants** — a
   dead household is replaced by a same-dynasty heir who inherits the estate;
   an open colony injects external money and admits immigrants here.
5. **Markets clear** — this is where transactions actually settle, by finding a
   price where supply ≈ demand.
6. **Printers** write a row of CSV (one row per in-game month, on the first of
   the month); inflation is updated and the in-game date advances by one day.

The key mental model: **`act()` posts intentions against yesterday's state;
`clear()` executes them.** Construction order in a simulation matters because of
this deferred settlement.

### Agents

- **Laborers** earn wages, choose consumption vs. saving (a target-savings model
  sensitive to the real interest rate), buy Necessity and Enjoyment goods, eat
  one unit of Necessity per day, and die if they cannot eat. Each laborer is a
  household whose head ages and dies of old age on a real mortality schedule
  (Coale-Demeny West Level 3); a successor household of the same dynasty inherits
  the estate, so money and the labor force stay in circulation. Every household
  also carries a **skill** in `[0, 20]` that fixes its labor productivity for
  life through the curve `max(0.01, (skill/10)²)` — skill is drawn around the
  colony's mean and re-rolled each generation (it is not inherited).
- **Firms** come in three kinds: `CFirm` produces capital (machines), while
  `EFirm` (Enjoyment) and `NFirm` (Necessity) combine labor and capital through a
  **Cobb-Douglas production function** `A·L^β·K^(1-β)`, adjusting output, wages,
  and capital investment in response to profit and interest rates.
- **Nobles** are the **owners** — rentier households that live off profit, not
  wages. Each step a noble pulls a dividend from every firm and bank it owns and
  spends a fraction of its wealth back into the consumer markets, so dividends
  circulate. Nobles age, die, and pass their holdings to a same-dynasty heir.
- **The ruler** is the colony's sovereign: the owner and sole client of its
  **gold** bank. It draws no dividends — it is a treasury that indulges, spending
  a small fraction of its fortune on enjoyment each day (and, because enjoyment
  is priced in copper, paying the gold bank's currency-exchange fee every time).
- **The builder** (`BuilderFirm`) is the only thing that can enlarge a *live*
  colony. It is a labor-only firm that converts hired labor into *build-units*
  and applies them to the colony's build queue — clearing land for new firms and
  raising the roads and walls of each new ring — billing each task's sponsor at
  cost (the firm pays for its own plot; the ruler funds the public works).

### Markets

`ConsumerGoodMarket` (Enjoyment, Necessity) binary-searches for a clearing price
bounded to ±10% of yesterday's price; `LaborMarket` allocates workers in
proportion to each firm's wage budget; `CapitalMarket` matches firms buying
machines against capital producers. A worker's labor is **skill-scaled** (an
abler worker supplies more labor for the same wage) and then **daylight-scaled**:
a laborer delivers 100% of its output at an 8-hour reference day, more on long
summer days and less in deep winter — so high-latitude colonies can starve in
winter. The standard colony survives up to ~58° latitude at the default
reference.

### Banking and the currency hierarchy

`Bank` is the payment and credit system. Every agent holds a checking and a
savings account at a bank; a negative savings balance is a loan. Payments are
agent-routed (a transfer debits the payer's bank and credits the payee's), so the
economy can run with **more than one bank**.

Each bank also has a **currency** — `COPPER`, `SILVER`, or `GOLD` — at a fixed
exchange rate (silver = 100 copper, gold = 1,200 copper). The standard colony
runs a **three-tier hierarchy**: commoners (laborers and firms) bank in copper,
nobles in silver, the ruler in gold. Because every price is quoted in copper, a
non-copper account converts on every payment, and the silver and gold banks are
**money-changers** that skim a small **exchange fee** on each conversion (the
copper bank, already in the base currency, charges nothing). That fee — together
with the export sector's earnings — is what turns the otherwise zero-profit
banks into ones that accumulate equity.

By default the export-holding copper bank, the silver bank, and the gold bank
are the only profit-makers; the commoners' copper bank is a zero-profit
pass-through, which is **load-bearing for the model's calibrated stability**.

### Settlement size and slots

A settlement occupies a disc of radius `size` with a fixed number of build
**slots** that hold its firms (and, later, housing). A precalculated table maps
each size to its slot counts: of the total slots (≈ `π·size²`), a
linearly-growing share goes to **roads** (congestion) and a circumference-sized
share to **walls**, leaving the **effective** slots that firms occupy. A colony
is founded at size 3 (15 effective slots) and **grows just enough to fit its
firms** — the firm-heavy default colonies reach size 4, the smaller ones stay at
3. A *live* colony can only grow through its builder. Placing a firm on a slot
moves no money and draws no randomness.

### Names and mortality

A session shares a `NameRegistry` (tiered, weighted name tables loaded from JSON)
and a `Demography` service. Dynasty surnames are unique across every living
household in the session and are recycled when a dynasty goes extinct; abler
households get rarer, more distinctive given names. Mortality and skill draws run
on separate salted RNGs, so they never perturb the economic random stream.

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
mvn exec:exec -Dsim.main=eos.simulation.AristocraticEconomy    # noble owners + 3 currencies
```

Every standard colony carries a default **export sector** (a strategic firm
staffed by nobles, whose earnings build the holding bank's equity) and a default
**ruler** banking in gold. The entry points are:

| Class                  | Description                                                                                          |
|------------------------|------------------------------------------------------------------------------------------------------|
| `HomogeneousEconomy`   | Homogeneous agents, a single copper bank (plus the gold ruler) — the default run.                    |
| `HeterogeneousEconomy` | Heterogeneous agents (randomized initial state).                                                     |
| `TwoBankEconomy`       | Two copper banks, with agents split across them (a multi-bank example).                              |
| `SmallOpenEconomy`     | An economy opened to external money inflow + immigration, growing past its starting size.            |
| `AristocraticEconomy`  | The default colony plus five noble owner households and a silver bank — the full 3-currency hierarchy. |
| `StrategicEconomy`     | Nobles bank in silver while the export firm banks in copper, so their export wages cross currencies.  |
| `HanseaticEconomy`     | Two neighbouring colonies near Lübeck in one session — independent economies, shared name pool.       |
| `BuilderEconomy`       | A colony founded at the floor size with a builder, which grows it a ring at a time during the run.    |

These scenario entry points are the simulation *product*. Two analytical
**tools** live apart from them, in the `eos.simulation.tools` sub-package:

| Class                  | Description                                                                                          |
|------------------------|------------------------------------------------------------------------------------------------------|
| `ScaleSweep`           | Scales the firm/laborer counts down together to find the smallest colony that stays stable.          |
| `LatitudeSweep`        | Places the standard colony across latitudes to find the highest one where it still feeds itself.      |

Each scenario consists of a `static run()` that builds and runs the colony via
`SimulationHarness`, plus a `main()` that calls it — except the sweeps
`eos.simulation.tools.ScaleSweep` and `LatitudeSweep`, whose `main()` runs a
multi-colony sweep and prints a stability/survival table to stdout (their
`run()` is just the convention hook returning one default harness).

## Configuration

A run is configured through `SimulationConfig` — an immutable record (with a
`DEFAULT`) holding the calendar (`startDate`, `durationYears`), population sizes,
market price bounds, each agent type's initial state, and the colony's
environment: founding-age mean, target necessity stock, **mean skill**,
**latitude/longitude**, the open-colony money inflow, and the firms' wage rule.
Model parameters for agents and banks live in their own config records
(`LaborerConfig`, `FirmConfig`, `BankConfig`, `NobleConfig`, `BuilderConfig`),
each with a `DEFAULT` and a Lombok builder, so a variant run can be derived
cheaply:

```java
SimulationConfig.DEFAULT.toBuilder().durationYears(10).build();
BankConfig.DEFAULT.toBuilder().exchangeFeeRate(0.02).build();   // a money-changer
```

## Output

Each run writes CSV files to an `output/` directory (created relative to the
working directory), one row per in-game month. The first column of every file is
the in-game **date** (`yyyy-MM-dd`). Floating-point cells are formatted to two
decimals; monetary values are shown in the holder's currency; the bank's
interest-rate and inflation columns are shown as percents (e.g. `0.45%`).
Discrete events — a notable person's birth or death, a price skyrocketing — are
logged to stderr, prefixed with the in-game date, e.g.:

```
1452-03-14: WARN Enjoyment skyrocketed to 51.30 (>10x init)
```

## Reproducibility

A `GameSession` derives each colony's `Rng` deterministically from a single
seed, so **the same seed and the same code produce the same run**. Naming,
mortality, and skill draw from separate salted RNGs, so they never perturb the
economic stream. (Strict byte-for-byte stability *across* feature additions is no
longer a design constraint — see the roadmap.)

## Roadmap

`eos` is being grown from a headless economic model toward a playable strategy
game. The current direction, in order:

- **Taxation and a working treasury.** The ruler will tax income, wealth, and
  (once trade exists) cross-border trade, and the treasury will *fund public
  works* — paying the builder for roads, walls, and megaprojects — instead of
  draining a fixed fortune.
- **Inter-settlement trade.** Goods will move between colonies by caravans and
  ships over real geographic distance, taking days to arrive and incurring a
  transport cost. Tariffs then tax those crossings.
- **Toward a playable game.** Longer term, a player will take a seat in the
  world. The presentation layer and the exact player role are still open; the
  near-term engine work stays headless and CSV-driven.

As these land, calibration favors **stability** (colonies that stay alive and
prices that stay bounded), and runs remain seed-reproducible even though
individual features may shift outputs from one version to the next.

## Tests

`mvn test` runs a JUnit 5 smoke test per simulation: each runs the full
25-year colony (assertions on) and checks it stays healthy — most laborers
survive, prices stay finite and positive, and banks remain sound. Targeted tests
cover the slot table, the solar/daylight calculation, the open-economy growth,
the builder mechanism, and the noble/ruler three-currency setup. Colony state is
per-instance, but the logging handler is a process-global static, so Surefire
forks a fresh JVM per test class to isolate it.

## Credits

`eos` was originally written by **Zhihong Xu** (~2011) and is being modernized
and extended (Maven build, Java 21, configuration records, tests, multi-bank and
multi-currency support, nobles and a ruler, daylight-scaled labor, named
settlements with build slots, and a builder). The original agent behavior and
market-clearing model are his; see `CLAUDE.md` for detailed architecture notes.

## License

Licensed under the **Apache License, Version 2.0** — see [`LICENSE`](LICENSE)
and [`NOTICE`](NOTICE).
