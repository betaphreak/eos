# Design & plan: `civstudio-mcp` — the simulation as an MCP tool surface

**Status (by phase):**
- **Phase 1 — analysis/calibration harness (stdio, engine-only).** Proposed. A
  standalone `civstudio-mcp` module that runs scenarios and reads their
  deterministic outputs (a queryable SQL run store, `SimLog`) as MCP tools/resources, for a
  local LLM consumer (Claude Code, an editor agent). No Spring, no live server.
- **Phase 2 — live-session tools (Streamable HTTP, co-hosted in `civstudio-server`).**
  Proposed / later. An MCP endpoint over the running `SessionHost` beans:
  list/create/control/query live sessions, submit player commands through the
  existing tick-stamped `CommandLog` seam. Reuses the server's auth.
- **Phase 3 — in-game LLM advisors.** Speculative. The privy-council
  `AdvisorRoster` seats driven by an LLM that reads a colony's render snapshot as
  MCP resources and acts *only* through `CommandLog`. Deferred until the command
  vocabulary is richer than one lever.

**Date:** design 2026-07-15 (sketch; no code yet).

**Depends on:** the engine's seed-reproducibility contract (`GameSession` +
`util/RngSeed` — same seed ⇒ byte-identical run); the scenario convention (a
`static run()` building a colony via `SimulationHarness`, `main()` calling it;
inventory in the README table and `docs/architecture.md` §Scenarios); the
printer/CSV output convention (`output/<seed>/`, told apart by a column) and
`io/SimLog` (per-session event log, per-colony prefixes); for Phase 2, the
client/server spine — `HostedSession` (tick authority, `submit`/`replay`,
`subscribe`, `currentSnapshot`), `SessionHost` (create/get/list), `CommandLog`,
`GameCommand`/`SetTaxRateCommand`, and the server auth (`ROLE_ADMIN` allow-list,
owner-gated writes via `denyWrite`).

**Related:** `docs/client-server.md` (the seams Phase 2 wraps — this doc does not
re-derive them), `docs/spring-boot-migration.md` (why the server is Boot 4 and the
hosting constraints), `docs/admin-console.md` (the existing privileged control
surface Phase 2 parallels), `docs/authentication.md` (the owner/ROLE gating Phase 2
inherits). The read-only projections Phase 2/3 expose are `server.render.*`
(`SessionSnapshot`, `ColonyView`, `PersonDetail`, `AdvisorView`).

---

## Motivation

The engine is already a headless, seed-reproducible, authoritative simulation, and
the server already exposes a tick-authoritative command seam. What's missing is a
way for an **LLM** to *drive* either one with typed calls and read structured
results back — today that means hand-writing throwaway Java scenarios, running
`exec:exec`, and eyeballing `output/<seed>/*.csv`.

That loop is exactly where the project's stated pain lives. CLAUDE.md and the design
notes are full of calibration-sensitivity: ruler colonies "collapse by design once
the pool's reserve drains," "births are implemented but don't yet outpace the
decline" (`docs/births.md`), food balance is a knife-edge (`docs/food-balance.md`),
and the calendar couplings that make rest days survivable are "calibration-sensitive"
(`docs/calendar.md`). Tuning any of these is a search over parameters against
reproducible runs — the kind of loop an LLM can drive well *if* the run/read step is
a tool call instead of a Maven invocation plus CSV parsing.

MCP is the fit because it's the standard way to hand an LLM typed **tools** (run,
query, control), read-only **resources** (docs, table schemas, live snapshots), and
canned **prompts** (a diagnosis playbook). The server side is cheap: the seams
already exist — an MCP server is a thin JSON-RPC adapter over them, not new
subsystems.

**Non-goals.** This is not a new gameplay system, not a replacement for the REST/SSE
browser transport (that stays), and not a rules engine — the sim stays the single
source of truth. MCP write tools never mutate engine state directly; they go through
`CommandLog` so every run stays reproducible and replayable.

---

## The reproducibility invariant (the one hard constraint)

Everything in this module is shaped by one rule the engine is built around: **the
seed is the run.** A `SessionSpec` (`seed`, `scenario`, `provinceId`) plus its
ordered `CommandLog` replays byte-identically. So:

- **Read tools are unconstrained.** Running a scenario, querying its run tables,
  reading a live snapshot or the event log — none of it perturbs the RNG streams.
- **Write tools have exactly one legal path: `HostedSession.submit(GameCommand)`.**
  A command is tick-stamped and applied at the deterministic top of its tick (never
  retro-mutating the in-flight day). An MCP tool that "sets the tax rate" constructs
  a `SetTaxRateCommand` and submits it — identical to what `POST /{id}/commands`
  does. No tool may reach past the command seam into agents, markets, or banks.
- **New randomness, if a tool ever needs it, gets its own salted stream** — same
  rule as everywhere else in the codebase. In practice the tools consume randomness
  only *via* the engine, so this shouldn't come up.

If a tool can't be expressed as (read a projection) or (submit a command), it
doesn't belong here.

---

## Data backend — a SQL store behind the existing sink seam, not CSV

The read tools query the historical time series, and **CSV is the wrong shape to
hand an LLM**: it's untyped text the model must re-parse and type-guess; there's no
filter/aggregate, so `query_timeseries` would slurp whole files and `sweep` /
`compare_runs` couldn't aggregate at all; and there's no run identity *in* the data
— runs are told apart by directory (`output/<seed>/`) and printers by file, so
nothing can `JOIN` across printers or `GROUP BY` run, which is exactly what a
calibration LLM wants.

The engine already has the seam to fix this. Printers write through a **`RowSink`**
(`io/sink/`), whose own contract is "a CSV file, a database table, or both without
knowing which," and every printer already declares a **typed** schema —
`ColumnSpec` + `ColumnType` (`DATE`/`TEXT`/`INT`/`REAL`), with `ColumnType` mapping
straight to Postgres types. Today only `CsvRowSink` / `CsvRowSinkFactory` are built;
`RowSinkFactory`'s Javadoc already anticipates "the Spring Boot launcher installs a
database- or composite-backed factory carrying the run/colony identity."

**So the MCP-friendly format is Postgres, reached by building the sink the seam was
designed for — not a new file format and not a printer rewrite.**

### Plan

- **Add `JdbcRowSink` + `JdbcRowSinkFactory`** (the anticipated implementations). The
  factory carries **run identity** — `run_id`, `seed`, `scenario` become real
  columns — and `ColumnType` gives each printer a typed table for free. Printers are
  untouched; only the factory a colony is handed changes.
- **Store: Postgres.** The server already depends on `spring-boot-starter-jdbc` +
  `postgresql` (it backs `JdbcUserStore` today), so the in-server surface writes to
  the same database it already runs. The engine-only Phase-1 harness, which can't
  assume a running Postgres, writes to an **embedded H2 file** (`output/<seed>/run.db`,
  H2 in PostgreSQL-compatibility mode — already a project dependency) so the *same*
  `JdbcRowSink` and the *same* SQL work with no server to stand up. JDBC keeps the
  engine driver-free (the `java.sql` API is in the JDK); whichever module launches
  provides the driver (H2 for the harness, Postgres for the server).
- **Keep CSVs if wanted** via a *composite* factory (the Javadoc's "both") — the
  human-readable files stay for eyeballing while MCP reads the database.

### What the read tools become

| Tool | With a SQL store |
|---|---|
| `query_timeseries` | `SELECT … WHERE date BETWEEN ? AND ? AND colony = ?` |
| `compare_runs` / `sweep` | one `… GROUP BY run_id` query, aggregated in the DB |
| `list_outputs` / csv-schema resource | `information_schema` — the typed schema already exists |

If a general `run_sql` tool is exposed alongside the parameterized ones, it uses a
**read-only** connection so a hallucinated `DROP`/`DELETE` can't land.

### Reproducibility

The store is a **reporting mirror, not sim state** — printers emit to it *after* the
day settles, exactly as they emit to CSV, so it never touches an RNG stream. The
seed-is-the-run invariant is unaffected; the DB is another sink, nothing more.

### Live sessions don't need it

The Phase-2 render projections (`ColonyView`, `PersonDetail`, `SessionSnapshot`) are
already typed records — those MCP tools return them as JSON directly. This backend
question is only about the *historical time series* the calibration tools read.

---

## Phase 1 — analysis/calibration harness (the one to build first)

A standalone Maven module `civstudio-mcp`, depending on `civstudio-engine` (plus an
embedded H2 driver for the reporting store — see *Data backend*; still no Spring),
speaking MCP over **stdio** to a local consumer. Because the engine is plain Java 25
and reproducible, these tools are deterministic and side-effect-free except for the
`output/<seed>/` run artifacts (the H2 `run.db`, and CSVs if the composite sink is on)
a run already writes.

### Home & transport

- New reactor module wired into the root `pom.xml` alongside `civstudio-engine` /
  `civstudio-server`; parent-inherited toolchain (Temurin 25) and Lombok config.
- Transport: the official **MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`) over
  stdio — no web stack needed for a local dev tool. (Spring AI's MCP server starter
  is the Phase-2 choice; keeping Phase 1 Spring-free avoids coupling the calibration
  harness to the server's Boot-4 dependency set — see *Open questions*.)
- Launched as an MCP server the user registers with their agent (e.g. a
  `claude mcp add` stdio entry running `java -jar civstudio-mcp.jar` with `-ea`, so
  the sim's `assert` invariant checks stay live).

### Tools

| Tool | Args | Returns | Notes |
|---|---|---|---|
| `list_scenarios` | — | `[{mainClass, blurb, isRulerColony}]` | The README/§Scenarios inventory, so the LLM picks a valid target. |
| `run_scenario` | `mainClass`, `seed`, `steps?`, `configOverrides?` | `{runId, seed, finalDate, died, outputDir}` | Runs a scenario headless to completion or `steps`. `runId` keys later reads. Deterministic in `seed`. |
| `query_timeseries` | `runId` (or `seed`), `table`, `columns`, `dateRange?`, `colony?` | rows (JSON) | `SELECT` against the run's table (see *Data backend*); the colony column selects the series. |
| `list_outputs` | `runId` | `[{table, columns, rowCount, dateRange}]` | Schema discovery via `information_schema` so the LLM doesn't guess column names. |
| `get_event_log` | `runId`, `level?`, `colony?`, `dateRange?`, `grep?` | filtered `SimLog` lines | Level-by-frequency filtering + the per-colony prefixes; the annual digest is a level. |
| `compare_runs` | `runIdA`, `runIdB`, `table`, `columns` | aligned diff + summary stats | The A/B primitive for "did this knob help?" — a single `GROUP BY run_id`. |
| `sweep` | `mainClass`, `param`, `values[]`, `table`, `metric` | `[{value, metric}]` (+ per-run `runId`) | Fans a single parameter over a range, reports the metric per run. The calibration workhorse. |

`configOverrides` maps onto the immutable `*Config` records (`FirmConfig`,
`RetinueConfig`, …) and `SimulationConfig` via their `toBuilder()` variants — the
tool applies overrides to `DEFAULT` before the harness builds the colony. Only
whitelisted, tunable fields are exposed; structural constants are not overridable
(they aren't `*Config` fields to begin with).

### Resources

- `civstudio://docs/*` — the design notes (`architecture.md`, `food-balance.md`,
  `births.md`, `calendar.md`, …) as read-only context the LLM can pull on demand.
- `civstudio://scenarios` — the machine-readable scenario inventory.
- `civstudio://schema/*` — column dictionaries for each printer's table (from
  `information_schema`), so `query_timeseries`/`compare_runs` calls are well-formed.

### Prompts

- `diagnose-collapse` — a playbook: run the scenario, pull the population/treasury
  series and the event log around the death date, correlate with the peasant-pool
  reserve, report the proximate cause. Encodes the "is the collapse *clean*?"
  question the smoke tests assert (`SimulationAssertions.assertCollapsed`).
- `tune-food-balance` — sweep the food knobs, report the stability frontier.

### Why this phase first

It has a guaranteed consumer today (the assistant loop already doing the tuning),
needs no server, no auth, and no network surface, and it directly compresses the
loop the project spends real effort on. Everything it touches is already
deterministic, so the tools inherit reproducibility for free.

---

## Phase 2 — live-session tools (co-hosted in `civstudio-server`)

Controlling a *running* session needs the server's in-process `SessionHost` /
`HostedSession` beans, so this surface lives **inside `civstudio-server`** as an MCP
endpoint (Spring AI MCP server starter, **Streamable HTTP** transport), not in the
Phase-1 module. It is a peer of `SessionController` — same beans, same auth, MCP
framing instead of REST.

### Tools (thin wrappers over `HostedSession` / `SessionHost`)

| Tool | Maps to | Write? |
|---|---|---|
| `list_sessions` | `host.list()` → id/scenario/seed/state/tick | read |
| `create_session` | `host.create(SessionSpec, owner)` (+ `start`) | write (auth) |
| `control_session` | `pause`/`resume`/`step`/`setTickRateMillis`/`stop` | write (auth) |
| `submit_command` | `hs.submit(new SetTaxRateCommand(tick, lever, rate))` | write (auth) |
| `get_snapshot` | `hs.currentSnapshot()` → `SessionSnapshot`/`ColonyView` | read |
| `get_person` | the `PersonController` projection (`PersonDetail`) | read |
| `get_command_log` | `hs.commandLog()` | read |

`submit_command`'s vocabulary is exactly the server's command registry — one entry
(`setTaxRate`) today; it grows as the interactive phase adds `GameCommand` types.
The tool validates the same way the controller does (lever known, `rate ∈ [0,1]`)
and lets `HostedSession` default the tick to `now+1` so it never retro-mutates the
in-flight day.

### Auth

Reuse the server's model wholesale: spectating (reads) can stay anonymous like the
SSE stream; control/create/submit are **owner-gated writes** — the MCP endpoint runs
the same `denyWrite` check (any authenticated user for the unowned public demo, the
owner for an owned session), and privileged/global actions sit behind the existing
`ROLE_ADMIN` / `civstudio.auth.admins` allow-list, exactly as the admin console
does. No new identity system.

### Live resources

- `civstudio://session/{id}/snapshot` — the tick-paced render snapshot, so an
  analyst LLM answers "what's happening now / why did colony X collapse?" against
  real projection data rather than prose.
- `civstudio://session/{id}/events` — the session's `SimLog` buffer
  (`SessionLogBuffer`).

---

## Phase 3 — in-game LLM advisors (speculative)

The scaffolding already exists: `AdvisorRoster` / the privy council, `AdvisorView`
render projections (role → seated noble, with portrait identity), and a lobby
`ChatMessage` broadcast. An LLM could fill an advisor seat — reading the colony
snapshot as a Phase-2 resource and producing in-character counsel, or *acting*
through `CommandLog` like any other client. Gated behind Phase 2 and, more
importantly, behind a command vocabulary richer than a single tax lever; until then
an advisor can advise but has almost nothing to *do*. Called out here only so the
seam (advisor reads projection, advisor acts via `CommandLog`) is on record.

---

## Testing

- **Determinism guard.** A test that runs the same `run_scenario` args twice and
  asserts identical run-table/event output — the module's core promise, and a cheap
  regression against anything that accidentally consumes shared RNG.
- **No-side-channel guard.** Assert the write surface is *only* `submit(...)`: reads
  never advance a tick, and there is no path from a tool to engine internals past
  the command seam.
- **Config-override round-trip.** Overrides applied to a `*Config` `DEFAULT` produce
  the expected builder variant; unknown/structural fields are rejected, not silently
  ignored.
- Phase 1 needs no Spring context; Phase 2 tests piggyback on the existing server
  slice tests and the auth `denyWrite` coverage.

---

## Open questions

1. **MCP dependency on Boot 4.** Spring AI's MCP server starter must line up with
   Spring Boot 4.1 (the same alignment care the server takes with the Boot BOM in
   `docs/spring-boot-migration.md`). If it lags, Phase 2 uses the transport-agnostic
   MCP Java SDK directly behind a plain `@RestController`-style endpoint instead of
   the starter. Phase 1 sidesteps this entirely by being Spring-free.
2. **Run lifecycle / disk.** `run_scenario` writes an H2 `run.db` (and optional CSVs)
   under `output/<seed>/`; the module needs a retention story (and, in the remote
   container, awareness of the fixed disk allowance) so sweeps don't fill the disk.
   With the SQL store this is cheaper than files — an explicit `drop_run` tool is a
   `DELETE … WHERE run_id = ?` (server/Postgres) or dropping the `run.db` file
   (harness/H2), plus an LRU cap on retained runs.
3. **Long runs vs. call timeouts.** A full-length scenario can be slow; `run_scenario`
   may need to be async (return a `runId` immediately, poll a `run_status` tool)
   rather than blocking the MCP call.
4. **Config surface.** Which `*Config` fields are safe to expose as `configOverrides`
   — the whitelist is a curation task, not automatic reflection over every record
   component.

---

*Pointer for `CLAUDE.md` / `docs/architecture.md` once this ships: one line under the
subsystem map — "MCP tool surface — the sim as typed tools for an LLM (run/query
scenarios; control live sessions), `docs/mcp-server.md`."*
