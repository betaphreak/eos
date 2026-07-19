# Design note: session management — identity, kind, lifecycle

**Status:** **Phase A SHIPPED** (the map-switch fix, commit `51231bc`); Phases B–F **PLANNED /
IN PROGRESS** (this doc is the plan). **Date:** 2026-07-19.
**Depends on:** [`docs/client-server.md`](client-server.md) (the hosted-session spine),
[`docs/spectator-lobby.md`](spectator-lobby.md) (the lobby, save slots, Timelines),
[`docs/game-over.md`](game-over.md) (the terminal state), [`docs/realms.md`](realms.md) (a session
carries its realm), [`docs/authentication.md`](authentication.md) (accounts + ownership).

## Why this note exists

Session management grew one feature at a time — a demo, then a lobby, then save slots, then ranked
Timelines, then restore — and it shows. The pieces work, but the *model* underneath them was never
designed; it was inferred. This note names the improvisation, states the target model, and tracks
the cutover.

The trigger was a concrete bug: **founding a single-player session from the lobby left the map stuck
on the public demo.** That is not a one-off — it is a symptom of the root problem below.

## The root problem: "which session" is re-derived, not identified

Ask *"which session is this client watching?"* and today the answer lives in **three** places that
must agree:

- `window.__spectate` — set by the lobby before the overlay module exists;
- `sessionStorage["cs.spectate"]` — the same intent, stashed to survive a realm-switch reload;
- the `preferred ?? list[0]` fallback in `live.mjs` `connectStream`, re-evaluated on every reconnect.

On each (re)connect the client **re-fetches the session list and re-picks**. That is fragile by
construction, and it broke in the obvious way:

1. `found()` creates an **owned, private** single-player session and asks to spectate it.
2. `connectStream()` fetched `/api/sessions` **without credentials** (cross-origin to the server, so
   the cookie was dropped) — an anonymous list.
3. The server lists a private run only to its owner, so the new session was **not in the anonymous
   list**; `preferred` never matched.
4. `preferred ?? list[0]` fell through to `list[0]` — the public demo. The map "never switched."

**Phase A** (shipped) put the cookie on that fetch and returned the session's realm from
`POST /api/sessions` so the founder can cross to it. That fixes the instance. The rest of this note
fixes the class.

### The fix for the class: URL-addressable session identity

The session id becomes a first-class piece of URL state: **`?session=<id>`**, alongside the existing
`?realm=`. Then:

- discovery is deterministic and reload-survivable — no `window.__spectate` / `sessionStorage`
  handoff, no `list[0]` fallback;
- reconnect just reopens *that id's* `/stream` — the list fetch is needed **only** for the bare
  visitor who picked nothing ("show me whatever's running");
- sessions become **shareable / bookmarkable** — which spectating a Timeline wants anyway;
- a dead link (private / over / gone) shows a **notice and stays put**, instead of silently swapping
  the viewer onto the demo (the very silent-swap that hid the original bug).

`?realm=` and `?session=` are one intent carried by one navigation, replacing the reload +
`cs.realmSwitch` flag dance.

## The other improvisation smells

**1. "Kind" is inferred from coincidence.** `kindOf()` reads demo / single-player / timeline off
`(owner == null, scenario == "timeline")`. "Demo = unowned" is load-bearing: the moment a public
sandbox or a tutorial exists, `owner == null` misclassifies it. And several SP/MP **modes** and
**difficulty** (Civ4 handicap) are coming — an inferred kind cannot carry them.

**2. Visibility / ownership rules are duplicated.** `SessionController.list()` hand-rolls the public
filter, `isPublic()` restates it, and `control` / `command` / `delete` each re-derive owner and
re-check. `SessionAuthz` owns *writes* but not *visibility*.

**3. One enum carries two orthogonal facts.** `HostedSession.State =
{CREATED, RUNNING, PAUSED, STOPPED, GAME_OVER}` mixes *clock state* (running / paused) with *contest
outcome* (finished / abandoned). Hence the recurring trap "`STOPPED` ≠ finished, only `GAME_OVER`
is," which the registry, `create()`, restore, and the overlay each re-implement.

**4. The three "beginnings" are branched inside `create()`** — a Timeline born empty, a save slot
`startPaused`, the demo `start`. A factory decision leaking into the endpoint.

## The target model

### Two orthogonal lifecycle axes (Phase D)

Replace the one 5-value `State` with two enums that fully capture every distinction it carried
without loss:

```
enum ClockState { CREATED, RUNNING, PAUSED, STOPPED }   // what the clock is doing
enum Outcome    { LIVE, WON, LOST, ABANDONED }          // the contest result
```

The current `State` maps cleanly:

| old `State` | `ClockState` | `Outcome` |
|-------------|--------------|-----------|
| `CREATED`   | `CREATED`    | `LIVE`    |
| `RUNNING`   | `RUNNING`    | `LIVE`    |
| `PAUSED`    | `PAUSED`     | `LIVE`    |
| `STOPPED` (from outside) | `STOPPED` | `LIVE` |
| `GAME_OVER` | `STOPPED`    | `WON` \| `LOST` \| `ABANDONED` |

The derived questions each read one axis, and the traps disappear:

- `isFinished()` = `outcome != LIVE` — never ticks again; the client gives up; the registry will not
  restore it; it holds no save slot.
- `isThreadDone()` / `isTerminal()` (the SSE feed should close) = `clock == STOPPED`.
- `canRestore()` (a redeploy brings it back) = `clock == STOPPED && outcome == LIVE`.

**Client presentation of a stopped run (owner decision).** Whenever the client receives a
`clock == STOPPED` frame — a plain external stop *or* a real game-over — it shows the **"Game Over"
screen and disables play/pause**: a stopped run is not something you can drive, so the transport must
not pretend otherwise, and the card reads **Game Over** either way (the owner's call — a stopped clock
is over as far as the player is concerned). This is a *presentation* rule keyed on `clockState`, and it
does **not** change the server's restore semantics: an external stop keeps `outcome == LIVE`, so a
redeploy still brings it back (that recovery is critical infra — see `docs/game-over.md`). The only
behavioural difference between the two is the background reconnect: the client gives up for
`outcome != LIVE`, and keeps trying for a suspended `LIVE` stop so the card clears and the map
re-attaches when the run is restored.

<b>Settled (owner, 2026-07-19):</b> a run stopped from outside stays `LIVE`/**restorable** — the
"Game Over" screen is presentation-only. Making it a decided outcome would have a graceful
shutdown/redeploy permanently kill every running session (including players' save slots), which is
exactly what restore exists to prevent.

`WON` / `LOST` / `ABANDONED` are decided in `HostedSession.run()`'s terminal block, where
`describeEnd()` already distinguishes them (a Timeline verdict; a colony that died; survivors who
departed as a band).

### Explicit, extensible session taxonomy (Phase C)

Three orthogonal axes, **persisted** so future modes / difficulty are additive (new enum values, not
a schema migration):

```
enum SessionKind { DEMO, SINGLE_PLAYER, MULTIPLAYER, TIMELINE }   // auth/visibility category
String mode         // the specific variant, e.g. "sandbox", "royale" — extensible, open set
String difficulty   // a Civ4 handicap key, nullable → the standard default
```

`SessionKind` is the **category that gates visibility and control**; `mode` is the *flavour* within
it; `difficulty` is the handicap. Only today's values populate now; `MULTIPLAYER` modes and applied
handicap effects land later without touching the schema.

### Visibility / lifecycle policy, in one place (Phase E)

A single policy object answers the four questions the controller currently smears across itself:

```
Visibility.canSee(principal, session)      // does this session appear in your list?
Visibility.canWatch(principal, session)    // may you open its stream?  (spectating stays open)
Visibility.canControl(principal, session)  // clock — folds today's SessionAuthz.denyClock
Visibility.canDelete(principal, session)   // your own single-player runs only
```

…and `SessionKind.begin(hs)` replaces the three-way `if` ladder in `create()`: each kind knows how
it starts (Timeline waits for the gun, a save slot starts paused, the demo runs).

### Two difficulty selectors (Phase F)

Difficulty is chosen along **two independent dimensions**, and the persisted model carries both:

**1. Civ4 handicap — *how hard*.** The numeric challenge scaling (AI bonuses, upkeep, growth
rates). Import `Civ4HandicapInfo.xml` via the existing `Civ4Files` seam (as tech / units / buildings
are), producing a read-only catalog the `difficulty` key is validated against. **Multipliers are not
applied to the sim yet** — this is the catalog + validation only; wiring the effects is a later,
separate feature.

**2. The alignment grid — *who you are*.** A prototype the owner sketched
([`docs/reference/anbennar-alignment-grid.csv`](reference/anbennar-alignment-grid.csv)): a start
nation / campaign chooser laid out on a two-axis alignment matrix, which doubles as a difficulty
*flavour* (the establishment is a gentler start than an evil-chaos underdog):

- **Good ↔ Evil** — vertical, `+5 … 0 … −5` (Good → Neutral → Evil).
- **Order ↔ Chaos** — horizontal, `+5 … 0 … −5` (Order → Neutral → Chaos). The Order columns are
  tagged by the Anbennar expansion whose *Cannor* content fills them — **Final Empire**, **Fires of
  Conviction**, **Forbidden Valley**, **Scions of Sarhal** — so the grid also gates on which content
  pack is enabled.
- Each cell is a nation/campaign at that alignment: e.g. *Empire of Anbennar* at (Good +1, Order +5),
  the *Jadd Empire* at (Neutral 0, Order +5), *Black Demesne* at (Evil −5, Order +5), *Brambleskinner*
  at (Evil −4, Chaos −5). The CSV holds the full grid with per-campaign blurbs.

**Model implication.** A founding therefore carries, beside the numeric `difficulty` (handicap), a
**campaign selection**: a start-nation/campaign tag, its `(good_evil, order_chaos)` alignment, and the
enabled content pack(s). The extensible `mode`/`difficulty` seam hosts the flavour, but the start
nation is closer to `provinceId` than to a mode — it deserves its own founding field
(`startCampaign` / `startTag`) when it lands. This phase captures the model and the reference data;
building the grid picker UI + wiring campaigns to real Anbennar start tags is a later feature (it
depends on the country/campaign catalog, not just the handicap XML).

## The persisted model + migration

`game_session` gains columns; the migration is **expand-and-contract** so a rollback to old code
still reads the table:

```
ALTER TABLE game_session ADD COLUMN IF NOT EXISTS kind        VARCHAR(32)
ALTER TABLE game_session ADD COLUMN IF NOT EXISTS mode        VARCHAR(64)
ALTER TABLE game_session ADD COLUMN IF NOT EXISTS difficulty  VARCHAR(64)
ALTER TABLE game_session ADD COLUMN IF NOT EXISTS clock_state VARCHAR(32)
ALTER TABLE game_session ADD COLUMN IF NOT EXISTS outcome     VARCHAR(32)
-- backfill from the legacy `state`:
--   kind        <- (owner IS NULL ? DEMO : scenario='timeline' ? TIMELINE : SINGLE_PLAYER)
--   clock_state <- state, except GAME_OVER -> STOPPED
--   outcome     <- GAME_OVER ? decode(end_reason) : LIVE
```

The legacy `state` column is **kept written** (a derived mirror) through the transition, so a
rollback is code-only. Portable DDL (H2 + PostgreSQL), same house style as the rest of
`JdbcSessionRegistry`.

The **campaign selection** columns (`start_campaign`, `good_evil`, `order_chaos`, `content_pack`) from
the alignment grid are **not added in this migration** — they are Phase G, and land with the picker
that populates them. `difficulty` (the handicap key) is the only difficulty column now.

## Wire change

`SessionSnapshot.state` (a single string the web reads) is **replaced** by `clockState` + `outcome`.
Because the web deploys automatically on push while the server is manual, the server must ship
**first** — the [deploy-order rule](spectator-lobby.md) — or the site briefly reads fields the server
does not send. (This note's changes are being held **before prod** for exactly that sequencing.)

## The plan

| Phase | What | Status |
|-------|------|--------|
| A | Map switches to the founded session (credentials on discovery + realm in the create response) | **SHIPPED** `51231bc` |
| B | URL-addressable session identity (`?session=`) + dead-link notice; retire the `__spectate` / `cs.spectate` handoff | **SHIPPED** |
| C | `SessionKind` / `mode` / `difficulty` on spec + record; registry migration | **SHIPPED** |
| D | `ClockState` + `Outcome` split (engine-adjacent server, wire, MCP, web) | **SHIPPED** |
| E | `SessionAuthz.canSee` visibility + `SessionKind.begin()` beginnings | **SHIPPED** |
| F | `Civ4HandicapInfo.xml` handicap catalog + `difficulty` validation (effects deferred) | **SHIPPED** |
| G | Alignment-grid campaign selector — now its own design + plan + interactive mockup in [`docs/campaign-selector.md`](campaign-selector.md) (astrolabe picker, heraldry, campaign exporter → Strapi) | PLANNED (designed) |

Phase F baked the catalog to a committed engine resource `/handicaps.json` (12 handicaps) via
`com.civstudio.handicap.export.HandicapInfoExporter` (`mvn -pl civstudio-engine exec:exec
-Dsim.main=com.civstudio.handicap.export.HandicapInfoExporter`); `HandicapCatalog` loads it and
`SessionController.create` resolves/validates `difficulty` against it. Multipliers stay unapplied.

Design decisions locked with the owner (2026-07-19): full model cutover (not a non-breaking
shim); extensible schema in one migration; **`TIMELINE` is its own `SessionKind`** (alongside `DEMO`,
`SINGLE_PLAYER`, `MULTIPLAYER`); a **stopped run shows GAME OVER with play/pause disabled** on the
client (presentation only — restore semantics unchanged); import the handicap catalog now but defer
its multipliers; the alignment-grid selector is model + reference data now (Phase G); dead links show a
notice rather than falling back to the demo; **verify on the local stack, then hand back — the owner
runs the prod deploy** (server-first).
