# Design & plan: the Spectator Lobby, single player & ranked Timelines

**Status:** PLANNED (no code yet). **Date:** 2026-07-17.
**Depends on:** [`docs/authentication.md`](authentication.md) (accounts + ownership, Phases 1–3 shipped),
[`docs/client-server.md`](client-server.md) (the hosted-session spine), [`docs/game-over.md`](game-over.md)
(the terminal state a run ends on).

## The idea

Today the site drops you straight onto a map spectating whatever the server seeded. There is no
place to *be* — no way to see what's running, no way to start your own run, and chat only exists
once you're already inside a session.

The **Spectator Lobby** is that place: a room with a session browser and a chat, reached before you
pick anything and reachable again from "home". From it you can **spectate** anything public, **spawn
a single-player run**, or take your seat in the **ranked Timeline**.

And **Ranked is a royale, not a leaderboard.** Every ranked player founds a colony in *one shared
world*, ticking in lockstep on one clock. Colonies collapse — that is what this simulation does —
and the last one standing wins the Timeline.

## Decisions

Confirmed with the owner (2026-07-17):

| Question | Decision |
|---|---|
| Where does the lobby live? | **Extend the existing server-picker splash** — already a dismissable full-viewport overlay, already the "home" gesture. |
| Chat reach | **Global chat in the lobby; per-session chat while watching.** No persistent dock, no channel switcher. |
| Ranked model | **Royale** — one shared world, many players' colonies, elimination. *Not* parallel solitaire on a shared seed. |
| Ranked world | **One shared session** on seed **7654321**, all ranked colonies in it, lockstep. |
| Ranked clock | **Always running; admins only** may pause (maintenance). "Starts paused" applies to single-player only. |
| Match structure | **Timelines** — the ranked world runs a Timeline, then resets to a fresh one. One colony per player **per Timeline**. |
| On elimination | **Stay and spectate.** Your colony's final date is your record; you keep watching whoever outlives you. |
| Visibility | **Ranked public, single-player save slots private.** |
| Not signed in | **Spectate anything public.** Sign-in gates *acting* (chat, found, command), never *watching*. |
| New sessions | **Single-player sessions start PAUSED**, landing on the map with a press-play cue. |
| The session clock | **The session owns its own clock** (`date = f(tick, startDate)`), no longer scavenged from live colonies. A prerequisite — see below. |
| On elimination (mechanic) | **You are demoted to `Rank.CARAVAN`** and wander as your departed band, able to climb back. The "stay and spectate" decision above is what you do *while* the band wanders / if it dies. |
| The public demo | **The demo IS the current Timeline.** Retire the standalone six-caravan demo scenario from the public site. |
| Information | **Spectators see everything; players see their rank window** (±1 rank, bounded above). |
| Persistence | **Sessions and Timelines are tracked in the database**, following the existing JDBC-store pattern. Not optional — see Phase 6. |
| AI / test sessions | Owned by a reserved **"AI"** identity (that is what the lobby shows), **uncapped by default**, and isolated by their own seed — they never touch the ranked world. |

### AI & test sessions (decided 2026-07-17)

A session can already be driven **entirely over MCP**: `create_session`, `control_session`
(pause/resume/step/rate/stop), `submit_command`, and `get_snapshot`/`get_events` to read back
(`docs/mcp-server.md`). That is a complete agent loop, and `step(n)` is the primitive that makes it
a good one — the sim waits for the agent instead of racing it.

Three decisions follow:

- **Owned by "AI".** An agent- or test-driven session is owned by a reserved `AI` identity and the
  lobby renders it as such (`● ranked · AI`, `● run · AI`). Honest to spectators, and it is the same
  owner column everything else already keys on.
- **Uncapped by default.** An AI session's tick rate defaults to 0 (flat out) — nothing human is
  watching it in real time, and a test that waits 1s/tick is not a test. The demo/Timeline keep
  their ~1 day/second pacing, which is for human eyes.
- **Isolated by construction.** `sessionKey` is `scenario-seed@owner`, so a test session on its own
  seed can never collide with `ranked-7654321` — real tests can run against a live server without
  disturbing the ranked world. **Guard it anyway**: creating the *ranked* spec must go through the
  join flow, never through generic `create_session`, so no agent can found or restart a Timeline by
  accident. (Verified in this shape already: the game-over work drove a full collapse on seed 555
  against a live server, uncapped, reaching game over in seconds and touching nothing else.)

This also opens a real possibility for the royale, noted not decided: **AI-held colonies in a
Timeline**, so a world is never empty and a human always has rivals. It needs per-colony ownership
(Phase 2) — an AI colony is just a colony whose owner is `AI`, which the seam gives for free.

### The four amendments (2026-07-17)

Added after the royale decision, each grounded in something the code already says:

**1. The session owns its clock — a prerequisite, not a nicety.** `HostedSession.advanceOneDay()`
derives `date` from *live colonies* and passes it to `tickBands(date)`, which no-ops on `null`. That
one coupling is why: the band a dissolved colony departs as freezes instantly; `SettlerCaravan`'s
re-founding (which `SettlerCaravanTest` asserts) can never fire in a hosted session;
`explorer-caravan.md` §15's "explorers rally on the abandoned site" is unbuildable; and a Timeline
cannot tick through a lull. The session already owns the authoritative **tick** — the date should be
derived from it and the run's start date, not from whoever happens to still be alive. Small change,
four designs unblocked. **Do this first** (it is Phase 0 below), because amendments 2 and 3 depend
on it and the royale endgame does too.

**2. Elimination demotes you to `Rank.CARAVAN` — the ladder goes down, not off.** A collapsing
colony *already* dissolves into a `SettlerCaravan` carrying its survivors, its hoard and its tech
(`SettlerCaravan.dissolve`, `docs/caravan.md`). Today that band is created and immediately
abandoned. But `Rank.CARAVAN(1, …)` — "a Caravan of households" — is **rung 1 of the ladder**, and
the single-player-rank-window note has a player *starting* there as an Anbennar adventurer company.
So elimination becomes: **collapse → demoted to rung 1 → wander → re-found → climb back**. It is not
a respawn (you keep only survivors and coin, and you have lost your seat), so it does not cheapen
elimination — it makes the royale a ladder you fall *down* rather than off, and "last standing" a
longer story. Needs amendment 1 to work at all. If it proves too generous, the fallback is the
shipped default: your band is flavour and you spectate.

**3. The public demo IS the current Timeline.** The six-caravan demo and the Timeline would run the
same seed anyway. Point anonymous visitors at the live Timeline and: a whole scenario retires, the
"demo spoils the ranked world" question disappears, and `docs/game-over.md`'s awkward corollary — a
public demo stuck on a permanent game-over screen — goes with it, because a Timeline always has a
next one. A visitor's first sight of CivStudio becomes real people losing real colonies. (The
`caravan-demo` scenario stays in the codebase for tests and local dev; it just stops being what the
public sees.)

**4. Spectators see everything; players see their rank window.** The Timeline row's survivor tally
(`⚔ 7/12`) contradicts the rank window — a player is supposed to see only ±1 rank around their own,
bounded above ("a caravan has no use for politics"). The split: **in the arena you do not know where
you stand; step out to spectate and you see the board, but you have given up your seat to do it.**
So the lobby renders the full tally to spectators and the eliminated, and a rank-windowed view to a
player with a living colony. `LogLine` already carries an event's Rank on the wire, which is the
filter this needs.

### Why royale, and why the engine is ready for it

The alternative — same seed, private world each, compare scores — is a daily challenge, and
"ranked" against people you never meet is a thin promise. The royale is what the codebase has been
quietly building toward:

- **`GameSession` already creates many named `Settlement`s from one seed**, with per-colony RNG
  streams, name slices and agent-ID spaces. Built for a shared world.
- **`SessionRunner` already runs several settlements in lockstep**, and `HostedSession` already holds
  a `List<Settlement>` and advances them sequentially (deterministic by construction).
- **Colonies collapse by design.** Elimination is not a mechanic to invent — it is the existing
  behaviour, and `GAME_OVER` (see `docs/game-over.md`) is already the elimination event.
- **The Rank window** (see the single-player-rank-window note: you see only ±1 rank around your own,
  bounded above — "a caravan has no use for politics") only pays off when *other players* occupy the
  other ranks. `LogLine` already carries an event's Rank on the wire.

## What already exists (and what that saves us)

- **Owner-scoped session ids — already done.** `SessionHost.sessionKey(spec, owner)` returns
  `spec.id()` unowned, `spec.id() + "@" + owner` owned. Save slots need no id rework. (The ranked
  world is a *single, house-owned* session, so it uses the plain spec id.)
- **Idempotent create.** `SessionHost.create` returns the existing session for the same spec+owner —
  which is what makes a save slot's Resume and Start the same call.
- **Chat is per-session, persisted and replayed.** `ChatStore` (in-memory, or `JdbcChatStore` with a
  datasource) keyed by session id; `HostedSession` appends and replays recent messages to a new
  spectator; the poster's name is resolved server-side so it can't be spoofed. The endpoint comment
  already calls it "a spectator lobby".
- **Auth + gating.** Steam OpenID (default) and OIDC are shipped; `POST /chat` needs any signed-in
  user; `/control` and `/commands` are owner-gated; reads are anonymous.
- **The picker.** `#loading`/`#ldPicker` is a full-viewport dismissable splash, exposed as
  `window.__picker` and opened by the panel title as "home". The lobby is this screen, grown.
- **Spectator counts.** `HostedSession.subscribers` already tracks live subscribers; the count needs
  only an accessor.

## The gaps that matter

### 1. Ownership is per session; the royale needs it per colony

`HostedSession.owner` is a **single field**, and `/control` + `/commands` gate on it. In the ranked
world one session holds many players' colonies, so:

- a **colony** gains an owner (the `app_user` who founded it);
- a **command** names a colony, and is accepted only from that colony's owner;
- **`/control` is refused for the ranked world** regardless of owner — the clock belongs to everyone,
  so it belongs to no player. Admins retain pause for maintenance.

This is the single biggest change in this note, and it is the reason to design the royale in now
rather than retrofit it: every other feature here is additive, this one is a seam change.

### 2. Durability — sessions live only in memory

`SessionHost` holds a `ConcurrentHashMap`; nothing restores sessions at boot (`DemoSessionSeeder`
only re-seeds the demo). A redeploy **destroys every run**. Fine for a spectator demo; fatal here:

- a **Timeline** that vanishes on redeploy erases everyone's run at once;
- a **save slot** that vanishes is not a save.

The engine's answer exists in principle — *state = f(SessionSpec, ordered command log)*, and
`CommandStore` is JDBC-backed — so a run is restorable by **replaying its command log** onto its
spec. Nothing does that today, and replaying a long Timeline at boot is not free.

**Ranked cannot open to the public before this is solved** (Phase 5). Until then the Timeline is
dev/owner-only.

### 3. The clock coupling (from `docs/game-over.md`)

The session date is derived from *live colonies*. In a royale this is benign — the world keeps
ticking while at least one colony lives — but it is why an eliminated player's band cannot wander
on: bands freeze when the last colony dies. Noted, not solved here.

## Game over means two different things now

`docs/game-over.md` defines a session-level terminal state. The royale splits it in two, and both
are needed:

- **Player game over (elimination)** — *your colony* collapsed. The session ticks on; you stay and
  spectate. This is `ColonyView.alive == false` for your colony, which the snapshot already carries.
- **Session game over (the Timeline ends)** — one colony standing (or none). *Then* the session
  reaches `GAME_OVER` and the client shows the terminal screen.

For a single-player session the two coincide, which is the case `docs/game-over.md` describes.

## The UI

**The lobby** — the picker splash, grown a second column and a play row:

```
┌────────────────────────────────────────────────┐
│                   CivStudio                    │
│          Spectator Lobby · dev server          │
│ ┌───────────────────────┐ ┌──────────────────┐ │
│ │ SESSIONS          (3) │ │ LOBBY CHAT       │ │
│ │ ● Timeline I  ⚔ 7/12  │ │ Alex: morning    │ │
│ │   RUNNING  1452-03-03 │ │ Bo:  ranked is   │ │
│ │   👁 4                 │ │      brutal      │ │
│ │ ● caravan-demo        │ │                  │ │
│ │   RUNNING  1452-03-03 │ │                  │ │
│ │   👁 1                 │ │ ┌──────────────┐ │ │
│ │ ● my run · slot 2     │ │ │ type…        │ │ │
│ │   PAUSED   1444-12-11 │ │ └──────────────┘ │ │
│ └───────────────────────┘ └──────────────────┘ │
│  [ Single Player ]  [ Ranked ]    Esc → map    │
└────────────────────────────────────────────────┘
```

- **Server choice stays step one** (the picker's current job); the lobby is what the splash becomes
  once a server is chosen.
- **The Timeline row** leads, showing survivors (`⚔ 7/12` — alive of founded), state, in-game date
  and spectator count. A finished Timeline reads `† ENDED · won by <name>`.
- **Chat is sign-in gated**: signed out, the composer becomes a "Sign in to chat" affordance. The
  list and spectating stay fully available.
- **Esc → map** keeps the picker's dismiss gesture; the lobby is never a wall.

**The play row** — one button per mode, each self-describing:

```
  [ Single Player ]   [ Ranked ]
         │                 │
         │                 ├─ never joined  → “Join Timeline I”
         │                 ├─ alive         → “Resume Timeline I · 1447-02-11”
         │                 ├─ eliminated    → “Spectate Timeline I · † 1451-08-02”
         │                 └─ timeline over → “View result · won by Bo”
         │
         └─ new PAUSED session (save slot 2 of 5)
            → opens on the map, paused, press ▶ to begin
```

A single-player session lands paused with the transport control highlighted — you survey the world
before committing. The ranked world is already running when you arrive; there is no press-play.

## The plan

### Phase 0 — the session owns its clock ✅ DONE (2026-07-17)

The session's date is now derived from the authoritative tick and the run's founding date, not from
whichever colonies are still alive.

**Shipped:**
- **`Settlement.getStartDate()`** — the colony's step-0 date. It already derived `getDate()` as
  `startDate.plusDays(timeStep)`; this exposes the origin so a host can keep the same clock.
- **`HostedSession.startDate` + `date()`** — `startDate.plusDays(tick)`. Read once at construction
  (a colony's origin never moves; reading it from a colony *later* would reintroduce the very
  dependency this removes). `LocalDate.EPOCH` for the degenerate colony-less session.
- **`advanceOneDay()`** no longer polls live colonies for the date; it passes `startDate + (tick+1)`
  — the day just stepped into, since the loop increments `tick` after it returns.
- **`tickBands()` lost its null-date guard.** *That guard was the freeze*: reached whenever no colony
  was left to date the day. The session's own clock has no such gap.
- **`Snapshots.of`** takes the date rather than re-deriving it, so the snapshot reports the session's
  clock.

**Why it is safe:** while a colony lives, the two clocks are identical — both are
`startDate + steps`, and a live colony steps exactly once per tick. Confirmed against **production
data**: the demo reported `1452-03-03` at tick 2639, and `1444-12-11 + 2639 days` is exactly
`1452-03-03`. A new `HostedSessionTest#theSessionClockAgreesWithItsLivingColonies` pins the
invariant, and the full reactor (engine + server 73/73) is green.

**What it does *not* do yet:** nothing observable changes today, because the run loop still breaks on
`allDead()` before the clock could matter. Phase 0 is purely enabling — it removes the *reason* a
band cannot outlive its colony. The features that spend it (amendments 1–2, the royale endgame,
caravan re-founding) come later and now have a clock to run on.

### Phase 1 — the lobby room (server)

- **A lobby chat room**, distinct from any session. `ChatStore` is keyed by session id, so the room
  is a **reserved key** (e.g. `@lobby`) — same persistence, same replay, same server-resolved name.
- **Its own SSE feed.** Session chat rides the session's snapshot stream; the lobby has no session,
  so it needs a small `LobbyController`: `GET /api/lobby/stream` (chat + session-list changes),
  `POST /api/lobby/chat` (any signed-in user, mirroring `SessionController#chat`).
- **Enrich the session list.** `GET /api/sessions` gains what a row needs: kind (timeline / demo /
  single-player), in-game date, spectator count, and for a Timeline its survivor tally.
- **Filter by visibility**: public sessions (Timeline + demo) to everyone, plus the caller's own save
  slots when signed in.

### Phase 2 — per-colony ownership (server, the seam change)

- **A colony carries an owner.** `HostedSession` grows a colony→owner map (the session-level `owner`
  stays, meaning "who owns the *run*" — null/house for the Timeline).
- **Commands name a colony** and are accepted only from its owner; the existing owner check moves
  from session to colony. Single-player is the degenerate case (one colony, one owner) and must keep
  working unchanged.
- **`/control` on the Timeline is admin-only**, refused for players.

### Phase 3 — the ranked Timeline (server)

- **A `timeline` scenario**: one shared session, seed 7654321, house-owned, always running.
- **Join** = found a colony in the Timeline for the calling user. **One colony per player per
  Timeline**, enforced explicitly (a clear error, not a silent second colony).
- **Elimination**: a player's colony collapsing does not end the session. The Timeline ends when one
  colony stands (or none) → session `GAME_OVER`, winner recorded.
- **Demotion to `Rank.CARAVAN`** (amendment 2): the collapsing colony's `SettlerCaravan` becomes the
  player's — it wanders, and may re-found to climb back. Needs Phase 0. Ship the plain "eliminated,
  go spectate" path first and add this behind it; it is the most speculative piece here.
- **Rank-windowed views** (amendment 4): a player with a living colony sees ±1 rank; spectators and
  the eliminated see the full board.
- **Timeline registry**: which Timeline is current, its seed, when it opened/ended, and the archived
  result of past ones. **The public site points at the current Timeline** (amendment 3); the
  `caravan-demo` scenario stays for tests and local dev.

### Phase 4 — single player (server)

- **Save slots**: up to 5 owned sessions per user. Enforced in `create` — `SessionSpec` has no slot
  concept, so N slots are N distinct specs under one owner.
- **Start paused**: `create` currently calls `hs.start()` (RUNNING); single-player calls
  `startPaused()`. The demo seeder keeps starting RUNNING — a demo nobody pressed play on is dead.
- **Private**: visible and controllable only to its owner.

### Phase 5 — the lobby UI (web)

- Grow `#ldPicker` into the lobby: server choice, then the session list + chat + play row.
- Signed-out: list + spectate work; the composer becomes a sign-in prompt.
- The Ranked button resolves its four faces from the session list + your colony's state.
- A paused single-player session lands with the press-play cue.
- Unit-test the pure parts under node (`web/js/*.test.mjs`), as `district-plots.mjs` does: the row
  model (kind/state/survivors → label) and the Ranked button's state resolution.

### Phase 6 — sessions & Timelines live in the database (required)

**Decided 2026-07-17: sessions and Timelines are tracked in the actual DB**, not in memory. Today
`SessionHost` is a `ConcurrentHashMap` and nothing survives a redeploy — which silently erases every
run and hands out ranked retries. The DB is what makes "one colony per player per Timeline" and "the
Timeline result is final" *true* rather than true-until-the-next-deploy.

Follow the established store pattern exactly (`JdbcCommandStore` / `JdbcChatStore` / `JdbcUserStore`
via `PersistenceConfig`): an interface with an in-memory default and a JDBC implementation when a
datasource is configured, each store owning its own table, created on first use with
`CREATE TABLE IF NOT EXISTS` (portable across H2 and PostgreSQL), touching nothing else.

Sketch — the columns the features above actually need:

```
game_session(id PK, scenario, seed, province_id, kind, owner,
             state, end_reason, tick, started_at, ended_at)

timeline(id PK, seed, name, state, opened_at, ended_at,
         winner_user, winner_colony)

timeline_colony(timeline_id, user_id, colony_name,
                founded_at, eliminated_at, final_tick,
                UNIQUE (timeline_id, user_id))     -- one colony per player per Timeline
```

That `UNIQUE (timeline_id, user_id)` is the point: the one-per-player invariant becomes a **database
constraint** rather than an emergent property of an idempotent hash map, so it holds across restarts
and races.

**Restore** stays the engine's own model — *state = f(SessionSpec, ordered command log)* — so a run
is rebuilt by **replaying its command log onto its spec** (`CommandStore` is already JDBC-backed).
Replay on boot is not free for a long Timeline, so restore lazily on first access rather than
holding boot hostage. A **finished** run needs no replay at all: its terminal state, end reason and
result are columns, which is enough for the lobby to list it and for a client to show the result.

**Ranked cannot open to the public before this phase.**

### Phase 7 — ship

Patch bump (new endpoints + list/snapshot fields) and deploy per `docs/client-server.md`
§Deployment. No map change, so no rebake.

## Open questions (deferred, not blocking)

- **Scoring & the win condition.** "Last standing" is clear enough for a Timeline, but what is a
  *score* for the eliminated — survival date? peak population? tech? The `GAME_OVER` state + final
  tick is *where* it gets stamped; *what* it is remains open.
- **Timeline cadence.** How long is a Timeline — fixed wall-clock, fixed in-game years, or until one
  stands? At ~1 in-game day/second a century takes ~10 hours of wall clock.
- **Founding sites.** Where does a joining player's colony go? Player-picked province, auto-assigned,
  or drafted? Spacing matters — `ProvincePlotPool.foundingCenter` already auto-spaces *within* a
  province, but not across the world.
- **The rank window.** SP is meant to start at the lowest Rank (adventurer company) and climb. In a
  Timeline, does everyone start at the bottom together, or can a player enter at a higher rank?
- **Presence.** The lobby shows a spectator count, not a roster. Rosters need presence tracking
  (subscribe/unsubscribe with identity), which the SSE subscription could carry later.
- **Demo vs Timeline seed.** Both 7654321 today; they differ by scenario and don't collide, but the
  demo spoiling the ranked world argues for a different demo seed eventually.
