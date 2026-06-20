# Persistence: CSV and Postgres

`eos` writes its time-series output through a small **sink seam** so the same
printers can emit to CSV files (the default), a Postgres database, or both. The
simulation domain has **no dependency on Spring or JDBC** — the database backend is
an opt-in launcher that installs a different sink factory; plain `main()` runs and
the test suite write CSV exactly as before.

## The sink seam (`io/sink/`)

A `Printer` no longer owns a `CSVPrintWriter`. Instead it declares a **typed
schema** and writes rows through a `RowSink`:

- `Printer.tableName()` — the logical table name (stable across colonies, e.g.
  `"prices"`).
- `Printer.columns()` — an ordered `ColumnSpec[]`; each is a name + a `ColumnType`
  (`DATE` / `TEXT` / `INT` / `REAL`). The names are the CSV header and the SQL
  column names; the order matches the values passed to `RowSink.writeRow(...)`.
- `RowSink` — `writeRow(Object... values)`, `flush()`, `close()`, `name()`. Values
  are **raw** (`LocalDate`, `Double`, `Integer`/`Long`, `String`, enum); each sink
  renders or binds them its own way.

The colony holds a `RowSinkFactory`; `Settlement.addPrinter` binds each printer's
sink from it. Implementations:

| Factory | Sink | Backend |
| --- | --- | --- |
| `CsvRowSinkFactory` (default) | `CsvRowSink` | CSV file via `CSVPrintWriter` (unchanged output) |
| `JdbcRowSinkFactory` | `JdbcRowSink` | Postgres table, batch inserts |
| `CompositeRowSinkFactory` | `CompositeRowSink` | fans out to several of the above (`OutputMode.BOTH`) |

`Settlement` defaults to `CsvRowSinkFactory`, so nothing changes unless a launcher
calls `Settlement.setSinkFactory(...)`. That call is made for the whole run by the
persistence hook below.

### One behavioural change to CSV

`BanksPrinter` used to pre-format its rate/inflation columns as strings like
`"0.45%"`. Those columns are now raw **percent-valued** numbers (a fraction of
`0.0045` is written as `0.45`), so they store as typed numeric DB columns and read
cleanly in both backends. This is the only intentional change to existing CSV
output.

## Identity: runs and colonies

CSV separated colonies by **file-name prefix** (`Lubeck-Prices.csv`). The database
separates them by keys instead: every metric row carries `run_id` and `colony_id`.

- `runs` — one row per JVM run (scenario, seed, start/finish timestamps). Owned by
  a Flyway migration.
- `settlements` — one row per colony (name, founding date, lat/lon, `run_id`).
  Owned by Flyway.
- the **metric tables** (`laborers`, `prices`, `banks`, …) — one per printer,
  **derived at runtime** from the printer's `ColumnSpec[]` (see
  `io/sink/jdbc/JdbcSchema`), each with a surrogate `id` and the
  `run_id`/`colony_id` foreign keys. Deriving them from the printers means a metric
  table can never drift out of sync with the printer that fills it; add a column to
  a printer and its table grows with it. They are created lazily (`CREATE TABLE IF
  NOT EXISTS`) the first time a printer of that type is bound, guarded so concurrent
  colonies don't race the DDL.

A metric table only exists if some scenario registers its printer — e.g.
`HomogeneousEconomy` has no `retinue`/`builder` tables because it registers neither
printer, exactly as it produces no such CSVs.

## How a run is persisted (the hook)

The core exposes a Spring-free hook in the `simulation` package:

- `ColonyPersistence.bind(Settlement, SimulationConfig)` — a backend implements
  this to record the colony and install a sink factory on it.
- `Persistence.setHandler(...)` / `Persistence.bind(...)` — the registration point.
  The `SimulationHarness` constructor calls `Persistence.bind(colony, cfg)` right
  before any printer is added (so the factory is in place when printers bind). With
  no handler registered it is a no-op — hence plain runs and tests are unaffected.

The Spring Boot launcher (`app/`) registers the handler for the duration of a run:

- `SimApplication` — `@SpringBootApplication`; Boot auto-configures a pooled
  `DataSource` (HikariCP), runs Flyway, exposes a `JdbcTemplate`.
- `DbColonyPersistence` — the `ColonyPersistence` implementation: inserts the `runs`
  row once and a `settlements` row per colony, then installs a `JdbcRowSinkFactory`
  (or a `CompositeRowSinkFactory` for `BOTH`) on the colony.
- `SimRunner` — a `CommandLineRunner` that reads `--sim=<scenario>`, sets the
  handler, invokes the scenario's `run()`, then clears the handler and stamps the
  run finished.

Because the hook is in the harness constructor, it fires for **every** colony of
every scenario, including the two concurrently-threaded colonies of
`HanseaticEconomy` (each gets its own `colony_id`; the shared `JdbcTemplate`/pool is
thread-safe, and run-row creation and table DDL are guarded).

## Running it

Prerequisites: a local Postgres and a database that already exists (Flyway creates
tables, not the database).

1. Put credentials in `src/main/resources/application-local.yml` (git-ignored):

   ```yaml
   spring:
     datasource:
       username: <user>
       password: <pass>
   ```

   The host/port/db default to `jdbc:postgresql://localhost:5432/civstudio` in
   `application.yml`; override the `url` there too if yours differs.

2. Run a scenario:

   ```powershell
   mvn spring-boot:run
   mvn spring-boot:run -Dspring-boot.run.arguments=--sim=SmallOpenEconomy
   ```

   `eos.output-mode` (in `application.yml`) selects `CSV` / `DB` / `BOTH` (default
   `BOTH`). Assertions stay on (`-ea`) via the Boot plugin's `jvmArguments`.

The plain entry points are unchanged: `mvn exec:exec` (and the JUnit suite) still
run without Spring and write only CSV.

## Querying

```sql
-- the latest run
SELECT * FROM runs ORDER BY id DESC LIMIT 1;

-- a colony's monthly bank series (rates are percent-valued)
SELECT b.date, b.bank, b.currency, b.loanir, b.inflation, b.equity
FROM banks b
JOIN settlements s ON s.id = b.colony_id
WHERE s.run_id = (SELECT max(id) FROM runs)
ORDER BY b.date;
```

## Notes / future work

- The metric schema is derived, not migrated. If you want versioned, hand-tuned
  metric DDL (extra indexes, constraints), move those tables into Flyway and have
  `JdbcRowSinkFactory` stop auto-creating them.
- There is no automated `@SpringBootTest` persistence test (it would couple the
  default suite to a live DB). The backend is covered by the end-to-end smoke: run
  `spring-boot:run` and inspect the tables. A Testcontainers-based test is the
  portable way to add one later.
- Rows batch-insert per sink (default 256) and flush on `close()` (run end). For
  crash-resilient partial output, lower the batch size or flush more often.
