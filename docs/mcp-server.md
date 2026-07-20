# Design & plan: `civstudio-mcp` — the simulation as an MCP tool surface

**Status (by phase):**
- **Phase 1 — analysis/calibration harness.** **Partly BUILT** (2026-07-15, v0.9.34).
  *Shape changed from the original sketch:* rather than a standalone stdio
  `civstudio-mcp` module, the calibration tools **co-host on the existing `/mcp` HTTP
  server**, **dev/admin-gated** (`civstudio.mcp.calibration.enabled`, on under the
  `dev` profile) so they're absent on the deployed demo — Claude connects to
  `http://localhost:8080/mcp` and gets both live-session and calibration tools (no
  stdio/stdout conflict, no third module; see §How an LLM / skill consumes this).
  **Shipped:** the JDBC/H2 **SQL run store** (via the `io/sink` seam — `JdbcRowSink`,
  §Data backend); the run tools `list_scenarios` + `run_scenario` + **`sweep`**
  (`ScenarioMcpTools`), which found a **standard ruler colony** at a seed with
  whitelisted `SimulationConfig`/pool overrides (`CalibrationRun`) and write the typed
  time series to the store, keyed by `runId`; and the **read tools** `list_outputs` /
  `query_timeseries` / `compare_runs` / **`get_event_log`** (`CalibrationQueryTools`)
  over the store, with LLM-supplied identifiers validated against the live schema.
  `run_scenario`/`sweep` also mirror the run's `SimLog` into a `sim_log` table, so
  `get_event_log` reads a finished run (the counterpart of the live `get_events`).
  `sweep` **retired the `CalibrationSweep` dev tool**. **Still to build:** only optional
  resources/prompts. *Divergence:*
  `run_scenario` runs a generic standard-colony setup + overrides, not a
  reflectively-invoked scenario `main` (scenarios hardcode their seed/printers).
- **Phase 2 — live-session tools (Streamable HTTP, co-hosted in `civstudio-server`).**
  **Read half BUILT** (2026-07-15, v0.9.34): a Spring AI 2.0 MCP server
  (`spring-ai-starter-mcp-server-webmvc`, Streamable HTTP at `/mcp`) exposing the
  read-only tools `list_sessions` / `get_snapshot` / `get_person` /
  `get_command_log` and the `civstudio://session/{id}/snapshot|events` resources
  over the running `SessionHost` beans (`com.civstudio.server.mcp`). The **write
  half BUILT too** (v0.9.34): `create_session` / `control_session` / `submit_command`
  (`SessionWriteMcpTools`) — thin peers of `SessionController`'s create/control/commands
  through the tick-stamped `CommandLog` seam — **admin-gated** (`McpAuthz` →
  `ROLE_ADMIN` / `civstudio.auth.admins`) rather than owner-gated, since MCP calls
  carry no per-session request identity as cleanly as REST does. (The `/mcp` servlet
  runs behind the same Security filter chain, so the caller's identity — including the
  dev-user header — is on the request thread; verified live.)
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

### Plan — sink layer BUILT (v0.9.34)

- **`JdbcRowSink` + `JdbcRowSinkFactory` shipped** (`io/sink/`). The factory carries
  **run identity** — `run_id`, `seed`, `scenario` are real columns on every table — and
  each printer's `ColumnType` maps to a SQL type (`DATE`/`VARCHAR`/`INTEGER`/`DOUBLE
  PRECISION`) that binds via `PreparedStatement`. Printers are untouched; only the
  factory a colony is handed changes (`Settlement.setSinkFactory`). Rows batch and
  commit on flush/close; the one-off `CREATE TABLE` is serialized in the factory so no
  writer races an absent table. **Every identifier is quoted** (exact case, spaces —
  CSV headers have both), because H2 and Postgres fold *unquoted* names oppositely; the
  query layer therefore introspects real names from `information_schema` and quotes
  them. Verified against embedded H2 by `JdbcRowSinkTest`.
- **Store: Postgres (server) / embedded H2 (harness).** JDBC keeps the engine
  driver-free (`java.sql` is in the JDK); whichever module launches provides the driver.
  The **in-server** Phase-1 tools (the chosen shape — see Phase 1 below) write H2
  `run.db` locally for a dev-run calibration loop, or the server's existing Postgres.
- **Keep CSVs if wanted** via **`CompositeRowSinkFactory` (shipped)** — the
  human-readable `output/<seed>/*.csv` stay for eyeballing while the SQL store is what
  the MCP query tools read.

*Still to build on this foundation: the launcher that installs a `JdbcRowSinkFactory`
per `run_scenario`, and the query tools that read the store — the next Phase-1 steps.*

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

## Phase 1 — analysis/calibration harness

> **Shape as built (v0.9.34).** The original sketch below proposed a *standalone stdio
> `civstudio-mcp` module*. That was superseded: the calibration tools **co-host on the
> Phase-2 `/mcp` HTTP server**, gated by `civstudio.mcp.calibration.enabled` (on under
> the `dev` profile, off on the deployed demo). Rationale in §How an LLM / skill
> consumes this — stdio would hijack the server's stdout and the MCP auto-config is
> single-protocol, and the server already depends on the engine, so a local
> `http://localhost:8080/mcp` gives an agent both live-session and calibration tools
> with no third module. The rest of this section (the tool table, resources, prompts)
> stands as the target; the *transport/home* is HTTP-co-hosted, not stdio.
>
> **Built:** `ScenarioMcpTools` (`@ConditionalOnProperty`) — `list_scenarios` and
> `run_scenario`, the latter founding a **standard ruler colony** via
> `simulation.CalibrationRun` (create → found → install sink → `addCommonPrinters` →
> run → `cleanUpPrinters`) at a seed with a **curated override whitelist**
> (`SimulationConfig` fields + the peasant-pool `RetinueConfig` food levers; unknown
> keys rejected). Output goes through a `CompositeRowSinkFactory` — CSV under
> `output/<seed>/` **and** the H2 run store (`CalibrationStore`, one shared
> `output/calibration` db, rows keyed by `runId`). **`sweep`** fans `run_scenario` over
> one parameter (`seed` or an override key) and reports each run's collapse outcome —
> the structured replacement for the retired `CalibrationSweep`. The **read tools**
> (`CalibrationQueryTools`): `list_outputs` (schema via JDBC metadata), `query_timeseries`
> (a run's typed series, identifiers validated against the live schema and quoted, values
> bound), `compare_runs` (per-column finals/ranges/delta — "did the knob help?"), and
> `get_event_log` (a finished run's `SimLog` — `run_scenario`/`sweep` mirror it into a
> `sim_log` table via the same sink, tapping before founding so the founding lines are
> caught; filter by min severity / Date range / message substring). Verified by
> `ScenarioMcpToolsTest` / `CalibrationQueryToolsTest` and a live
> `sweep`→`list_outputs`→`query_timeseries`→`compare_runs`→`get_event_log` handshake.
> **Not yet built:** only optional resources/prompts.

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

> **Built (read half, v0.9.34).** The `org.springframework.ai:spring-ai-bom` (2.0.0,
> the line that targets Boot 4.1 — closing *Open question 1*) is imported in the
> server pom and pulls `spring-ai-starter-mcp-server-webmvc`; `application.yml`
> enables a `SYNC`/`STREAMABLE` server at `/mcp` named `civstudio`. The annotation
> scanner discovers `SessionMcpTools` (`@McpTool`) and `SessionMcpResources`
> (`@McpResource`) in `com.civstudio.server.mcp` — thin projections over
> `SessionHost` / `HostedSession` / the `render.*` records, verified against a live
> session (`McpEndpointTest`, `SessionMcpToolsTest`). The **write** rows (marked
> `write (auth)`) also shipped — `SessionWriteMcpTools`, **admin-gated** via `McpAuthz`
> (`RequestMcpAuthz` resolves the caller on the `/mcp` request thread; `SessionWriteMcpToolsTest`
> + a live handshake).
> The `events` resource serves a **retained per-session tail** (`SessionEventLog`),
> alongside a filterable **`get_events`** tool — see the retained-buffer section below.
> A first `@McpPrompt` playbook also shipped — `diagnose-live-colony`
> (`SessionMcpPrompts`), realizing consumption pattern 3.

### Tools (thin wrappers over `HostedSession` / `SessionHost`)

| Tool | Maps to | Write? |
|---|---|---|
| `list_sessions` | `host.list()` → id/scenario/seed/state/tick | read |
| `create_session` | validates the scenario against `ScenarioRegistry` (rejects unknown with valid keys), `host.create(SessionSpec, owner)` (+ `start`); returns shape/balanceProfile/contentVersion | write (auth) |
| `control_session` | `pause`/`resume`/`step`/`setTickRateMillis`/`stop` | write (auth) |
| `submit_command` | `hs.submit(new SetTaxRateCommand(tick, lever, rate))` | write (auth) |
| `get_snapshot` | `hs.currentSnapshot()` → `SessionSnapshot`/`ColonyView` | read |
| `get_person` | the `PersonController` projection (`PersonDetail`) | read |
| `get_command_log` | `hs.commandLog()` | read |
| `get_events` | `hs.eventTail(level, from, to, grep, limit)` → retained `LogLine`s | read |

`submit_command`'s vocabulary is exactly the server's command registry — one entry
(`setTaxRate`) today; it grows as the interactive phase adds `GameCommand` types.
The tool validates the same way the controller does (lever known, `rate ∈ [0,1]`)
and lets `HostedSession` default the tick to `now+1` so it never retro-mutates the
in-flight day.

### Auth

Reuse the server's model: spectating (reads) stays anonymous like the SSE stream. For
the writes, the sketch above imagined per-session *owner-gating* (`denyWrite`); the
built surface instead **admin-gates** them (the choice recorded when the write half was
built) — MCP calls don't carry a per-session owner as cleanly as an authenticated REST
request, so `control/create/submit` require `ROLE_ADMIN` / the `civstudio.auth.admins`
allow-list, exactly as the admin console does. The seam is `McpAuthz.requireAdmin()`;
`RequestMcpAuthz` resolves the caller from the `/mcp` request thread — the endpoint is a
servlet behind the same Security filter chain, so `SecurityContextHolder` and (via
`RequestContextHolder`) the request/dev-header are available, exactly as in
`SessionController`. No new identity system. (Owner-gating can be reintroduced later if
MCP writes need to target another user's owned session.)

### Live resources

- `civstudio://session/{id}/snapshot` — the tick-paced render snapshot, so an
  analyst LLM answers "what's happening now / why did colony X collapse?" against
  real projection data rather than prose.
- `civstudio://session/{id}/events` — the session's recent event-log lines, served
  from the **retained tail** (`SessionEventLog`); the `get_events` tool is the
  filterable form. See the retained-buffer section below.

### Retained per-session log buffer — BUILT (v0.9.34)

The original `events` resource was thin by construction. `SessionLogBuffer`
(`server.render`) is a **drain-once** queue: a `SimLog` tap fills it on the colony
threads, and the session thread **drains** it into each `SessionSnapshot.log()` every
frame — so once a frame is emitted those lines are gone from the buffer. The resource
returned only whatever had accumulated since the last emission (usually `[]`), not the
session's history.

The fix shipped: **`SessionEventLog`** (`server.render`) — a bounded (`CAP` 4096),
synchronized rolling window of `LogLine`s. The **same** `SimLog` tap in
`HostedSession.run()` that feeds `SessionLogBuffer.add(...)` now also appends to it, so
the live SSE drain path is untouched; `HostedSession.eventTail(level, from, to, grep,
limit)` is the read seam. On it:

- the **`get_events` tool** (`SessionMcpTools`) exposes the `get_event_log` filter
  vocabulary — `level?` (min severity `info|warn|error`), `from?`/`to?` (inclusive
  ISO-8601 in-game dates; ISO sorts lexically so a string compare is a date compare),
  `grep?` (case-insensitive substring), `limit?` (most-recent *N*, default 200);
- the **`events` resource** now serves that retained tail (most-recent lines) instead
  of the drained delta.
- **Reproducibility & cost unchanged:** the ring is a *reporting mirror*, populated as
  lines are logged (like the snapshot log and the SQL sink in *Data backend*), never
  touching an RNG stream, and bounded so a months-long session can't grow it without
  limit. Full history is still the `CommandLog` replay; this is a convenience tail.

Verified by `SessionEventLogTest` (filters/ordering/eviction) and a live `get_events`
+ `events`-resource handshake (`McpEndpointTest`). Aligning the arg names with the
Phase-1 `get_event_log` keeps one event-query vocabulary across a running colony and a
finished run.

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

## How an LLM / skill consumes this

The tools and resources are the *verbs*; a **skill** (a client-side `SKILL.md`
playbook, or the MCP-native `@McpPrompt` below) is the *procedure* that sequences
them. Both consumers reach the server the same way — the Phase-2 endpoint is
Streamable HTTP, registered once with the agent:

```
claude mcp add --transport http civstudio https://dev.civstudio.com/mcp   # or http://localhost:8080/mcp
```

After that the tools surface as `mcp__civstudio__list_sessions` /
`…get_snapshot` / `…get_person` / `…get_command_log` and the resources as
attachable `civstudio://session/{id}/snapshot|events` references.

### Consumer patterns

1. **Live-session observer skill (enabled by the Phase-2 read half, today).** A
   `diagnose-live-colony` skill encodes: `list_sessions` → pick the id →
   `get_snapshot` → if a colony is `alive:false` or its `poolSize` is draining, pull
   `get_command_log` for the levers pulled and `get_person` on the ruler/advisors to
   inspect the aristocracy. Turns "why is Dhenijansar dying?" into a fixed read-only
   sequence over real projection data. Safe against the deployed demo (reads only).
2. **Calibration skill (wants the Phase-1 harness).** The tuning loop — "sweep the
   food knobs, find the stability frontier" — needs `run_scenario` / `sweep` /
   `query_timeseries` over the SQL run store, not the live-session reads. A
   `tune-food-balance` skill fans `sweep` across a parameter, reads each run's
   population/treasury series, and reports where collapse stops. This is *why the plan
   builds Phase 1 first*: Phase-2 reads **observe** a running session; Phase-1 tools
   **drive experiments**. A good skill uses one filter vocabulary against both (the
   reason `get_events` is aligned with `get_event_log`).
3. **MCP prompts = server-shipped skills. (First one BUILT.)** MCP has its own
   `prompts` primitive, and the Phase-2 server advertises the `prompts` capability.
   `SessionMcpPrompts` ships **`diagnose-live-colony`** (`@McpPrompt`, optional
   `sessionId` arg) — a canned playbook that sequences the read tools into the plan's
   collapse-diagnosis procedure and returns it as a prompt message, so the **server
   itself** ships the skill and the client surfaces it slash-command-style, no local
   `SKILL.md` required. Verified over a live `prompts/list` + `prompts/get` handshake.
   So a "skill" can live client-side (a `SKILL.md` calling the tools) *or* server-side
   (an `@McpPrompt`). Remaining: the Phase-1 `tune-food-balance` sweep playbook (needs
   the harness) and a `diagnose-collapse` variant that *runs* a scenario rather than
   reading a live one.
4. **Phase 3 advisor is a skill that can also write.** An in-game advisor reads the
   same resources and *acts* through the (future, admin-gated) write tools via
   `CommandLog` — i.e. a skill whose tool list happens to include `submit_command`.
   Same seam as patterns 1–3, one row wider.

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

### Interactive MCP verification (used in practice)

Beyond the JUnit guards, the MCP surface is exercised **live from a Claude Code
session** — the standing way we smoke-test the running server and inspect its
sessions without hand-writing a client. The `civstudio` server is registered once
(`claude mcp add --transport http civstudio http://localhost:8080/mcp`, or the
`https://dev.civstudio.com/mcp` demo), after which the tools surface as
`mcp__civstudio__*` and can be driven directly in a turn:

- **Liveness / handshake smoke test.** `mcp__civstudio__list_sessions` returning the
  expected rows (e.g. the `caravan-demo-7654321` demo, `state:RUNNING`, its current
  `tick`) confirms the endpoint, the Security filter chain, and `SessionHost` wiring
  are all up — the read-only equivalent of hitting `/actuator/health`, but through
  the MCP transport the agents actually use.
- **Read-path verification.** `get_snapshot` / `get_person` / `get_command_log` /
  `get_events` against a running id confirm the `render.*` projections and the
  retained `SessionEventLog` tail serialize correctly over MCP — the live counterpart
  of `McpEndpointTest` / `SessionMcpToolsTest`, run against the *real* deployed or
  local session rather than a slice.
- **Write-path / auth verification.** `create_session` / `control_session` /
  `submit_command` exercise the admin gate (`McpAuthz.requireAdmin()`) end-to-end:
  a call with the dev-user/admin identity succeeds and mutates through `CommandLog`;
  without it, the same call is refused — proving the `/mcp` servlet inherits the
  server's `ROLE_ADMIN` allow-list.
- **Calibration-loop verification (dev profile).** With
  `civstudio.mcp.calibration.enabled` on, a `run_scenario` → `list_outputs` →
  `query_timeseries` → `compare_runs` → `get_event_log` handshake from a session is
  how the SQL run store and the sink seam get validated against real output, not just
  the H2 `JdbcRowSinkTest`.

This interactive use is why the tools stay strictly (read a projection) or (submit a
command): the same reproducibility invariant that makes them safe as tools makes them
safe as an on-demand verification surface — reads never advance a tick, writes only
go through the command seam.

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

*Pointer added to `CLAUDE.md` subsystem map (Phase-2 read half shipped): "MCP tool
surface — the sim as typed tools for an LLM (run/query scenarios; query live
sessions), `docs/mcp-server.md`." Phase 1 (the engine-only calibration harness) and
the Phase-2 write tools remain to build.*
