# Design & plan: the Spectator Lobby, single player & ranked Timelines

**Status:** Phases **0, 1, 2, 3 (core), 4 and 6** are SHIPPED — the whole server side is in. What
remains is **Phase 5** (the lobby UI — all frontend), plus the Timeline registry and scoring.
**Date:** 2026-07-17.
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
| Naming a run | **Deferred.** Eventually a run is named after its **country**; until then a row shows its colony. No naming UI, no name column — see below. |
| Starting a run | **A setup panel** (seed + province) before founding, not one click. |
| The public site | **Keep the demo for now**; it switches to the live Timeline when the **registry** lands. |

### Naming, setup and the public site (decided 2026-07-17)

**A run will be named after its country.** Not its colony, and not "slot 2 of 5" — so the naming
work is *deferred rather than designed around*: a lobby row shows its colony's name today
(`Dhenijansar · 1447-02`), which is generated, meaningful, and needs no new column or rename
endpoint. When countries arrive the label changes in one place. The political layer already has the
raw material — `Country` / `Province.ownerTag` / `WorldMap.provincesByOwner`
(`docs/political-map.md`) — so this is a real destination, not a placeholder.

**Founding takes a setup panel, not a button.** Seed and province are chosen before a run is
founded. It costs a screen, but it lets a player replay a known world (the seed *is* the world) and
it is where real game setup will grow — race, difficulty, start date. The one-click alternative was
rejected: the survey-before-you-play moment is what the paused start already gives you, so the panel
is not paying for that.

**The public site keeps the demo** until the Timeline registry exists. The demo now reseeds itself
when it collapses (Phase 6's `forget` path), so it self-heals rather than sitting on a permanent
game-over — which was the objection amendment 3 answered. The switch to a live Timeline then happens
**once**, when there is a registry to say which Timeline is current, rather than half-done twice with
a hardcoded id.

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

### Phase 1 — the lobby room ✅ DONE (2026-07-17)

**Shipped:**
- **`LobbyRoom`** — the one chat channel that belongs to no session, so you can talk *before* you
  have picked anything (what makes the lobby a place rather than a menu). It is a `ChatStore` room
  under the reserved key **`@lobby`** rather than a store of its own: the store is keyed by session
  id, and a key no `<scenario>-<seed>` can produce buys the same persistence, the same backlog
  replay and the same server-resolved display names for free. Durable exactly when chat is.
- **`LobbyController`** — `GET /api/lobby/stream` (chat SSE, backlog replayed on connect) and
  `POST /api/lobby/chat` (any signed-in user; the poster's name is resolved server-side, never taken
  from the body). Listening is anonymous, talking is not — the house rule everywhere here.
- **`SseFeed`** — the SSE transport, extracted from `SessionController` now that a second feed
  exists. This is the refactor flagged earlier as coming due *at the second caller, not before*:
  queueing, the drain thread, drop-oldest and unsubscribe-on-disconnect live there, and a controller
  now says only what its feed carries and when it ends. `SessionController` 452 → 389 lines.
- **The enriched list** — `GET /api/sessions` rows now carry `kind` (timeline / demo /
  single-player), the in-game `date`, `watching` (the eye count, from `HostedSession.spectators()`),
  `mine`, and for a Timeline `seats`/`standing` — how the contest stands.
- **Visibility** — public runs (a Timeline, the demo) are listed for everyone including the signed
  out; a player's save slots only for them; admins see all.

**Deviation from the plan, deliberate:** the lobby feed carries **chat only**. The plan had it
pushing session-list changes too, but a browser list does not need frame-accurate pushes — the lobby
refreshes `GET /api/sessions`, which is one fewer moving part and no worse to look at. Chat genuinely
does need a push: a conversation on a poll is not a conversation.

**Verified:** server **103/103** (+7), and live over the wire: a listener received a message pushed
as it was posted; a **late arrival got the three-message backlog replayed and then the next message
live**; anonymous posting is 401. Those 103 include every session-stream test, which is what makes
the `SseFeed` extraction a refactor rather than a rewrite.


### Phase 2 — per-colony ownership ✅ DONE (2026-07-17)

The seam change: ownership asks about the **colony**, not the run.

**Shipped:**
- **`HostedSession.ownerOf(colonyName)` / `claimColony(colonyName, userId)` / `colonyByName(...)`.**
  A `colonyOwners` map holds the claims; `ownerOf` falls back to the run's owner when a colony is
  unclaimed, which is exactly what keeps today's sessions behaving as before (a single-player
  colony answers with its owner; the unowned demo's answers `null` = open to any signed-in user).
  A seat cannot be taken from under its owner: re-claiming for the same user is a no-op, claiming
  someone else's throws.
- **`SetTaxRateCommand` names its colony.** This was the real bug in waiting: `apply()` looped over
  **every colony in the session** and set the lever on all of them. In a Timeline, one player's tax
  command would have moved every rival's taxes. It now moves the named colony only. The colony is
  named (not indexed) because names are drawn from the seed and so are stable across a replay.
- **`POST /commands` gained `colony`**, gated by `denyColonyWrite` — the caller must own *that*
  colony (admins bypass; an unknown colony is a 404, not a silent no-op). `submit_command` over MCP
  takes the same parameter, so an agent is held to the same rule.
- **Replay compatibility**: a `null` colony means "every colony" — what pre-Phase-2 commands *meant
  when they were issued* — and the codec omits the field entirely for them, so old rows in the
  command log replay to the state the run actually had. Pinned by `CommandCodecTest`.

**Verified:** server **78/78**, with 4 new cases — a command gated on the colony's owner rather than
the run's; a *claimed* colony belonging to its player even though the run is house-owned (the shape
a Timeline needs); the target surviving the persistence round-trip; and a pre-Phase-2 row still
meaning every colony.

**Deferred to Phase 3 (with the Timeline that needs it):** `/control` is still gated on the run's
owner, which means an *unowned* session is controllable by any signed-in user — fine for the demo,
wrong for a Timeline, where the clock must be **admins only**. There is no Timeline yet to gate, and
inventing a "shared session" flag before the scenario exists would be guesswork.

### Phase 3 — the ranked Timeline ✅ CORE DONE (2026-07-17)

**Shipped:**
- **A `timeline` scenario** (`SessionSpec.timeline(seed, anchorProvinceId)`), house-owned and born
  **empty** — it founds no colony of its own, opening in `CREATED` and filling as players join.
- **`SessionHost.joinTimeline(sessionId, userId)`** — picks the site, founds the colony there,
  claims it. **One seat per player**: asking twice returns the seat you hold, so a double-click
  cannot make you two players. Only before the gun.
- **`TimelineSites`** — one province each, spread across the map: the first joiner takes the anchor,
  each later joiner takes the viable province (settleable, ≥ `MIN_FOUNDING_PLOTS`) **furthest from
  its nearest rival** (max-min distance, the idea `ProvincePlotPool.foundingCenter` uses within a
  province, lifted to the world). Deterministic — no randomness, ties on province id — so a
  Timeline's roster replays exactly.
- **The gun**: `POST /control {action:"start"}`, admin-only, closing the roster. `POST /sessions`
  no longer auto-starts a Timeline (it would fire the gun on an empty world).
- **`POST /sessions/{id}/join`** — 401 anonymous, 201 with your colony + province, 409 once running.
- **The clock is admins-only** (the piece deferred from Phase 2): no player may pause the world
  their rivals live in. Authenticate *then* authorize — anonymous is 401, a seated player 403.
- **A Timeline ends when the contest does**, not when everyone is dead: `runOver()` is
  scenario-aware — one colony standing decides it — and the end reason is a **verdict** ("… stands
  alone and wins the Timeline on <date>"). A solo Timeline is the exception: with no rival to
  outlive it runs until its colony dies, like a single-player run.

**The trap worth recording:** `allDead()` over an **empty** roster is vacuously true, so a Timeline
nobody joined would break on the loop's first pass and report itself **won**. `launch()` now refuses
an empty run outright, and a test pins it.

**Verified:** server **86/86**, +8 cases — born empty and filling; the anchor then a distant second
site; one seat per player; the roster closing at the gun; every colony starting on the same day; the
empty-Timeline trap; join being Timeline-only; and a **real two-player Timeline run out to a named
winner**. Plus the wire path: join/roster/clock authz end to end.

**Deferred (unbuilt):**
- **Demotion to `Rank.CARAVAN`** (amendment 2) — the most speculative piece; the plain "eliminated,
  go spectate" path is what ships. Phase 0 removed its blocker, but the loop's break on the terminal
  condition is still the gate.
- **Rank-windowed views** (amendment 4) — a read-model change: a player with a living colony sees
  ±1 rank; spectators and the eliminated see the full board.
- **The Timeline registry** — which Timeline is current, its seed, when it opened/ended, and past
  results. This is a **DB** concern (Phase 6), and inventing an in-memory registry first would be
  throwaway. Until then a Timeline is created explicitly, and the public site still points at the
  demo (amendment 3 waits on the registry).

### Phase 4 — single player ✅ DONE (2026-07-17)

**Shipped:**
- **Five save slots** (`SessionHost.SAVE_SLOT_LIMIT`), enforced in `create`: a sixth run is a 409
  naming the limit, while re-founding a run you already have is not a new slot.
- **A save slot starts PAUSED** — you land on the world and survey it before committing, which is
  the whole point. Three beginnings now, and they are not interchangeable: a **Timeline** is not
  started at all (its gun is admin-only), a **save slot** starts paused, the **demo** runs
  immediately (a demo nobody pressed play on is a dead demo).
- **`DELETE /api/sessions/{id}`** — your own single-player runs only. Not the demo (nobody's), and
  emphatically not a Timeline: deleting one would destroy its verdict and hand every player in it
  another attempt. Not even an admin does that through this door.
- **Private** — already true from Phase 1's visibility rule: a slot is listed only to its owner.

**A rule the plan did not have, and needed:** **a finished run does not hold a slot.** Colonies
collapse *by design*, so if a dead run kept its slot, five collapses would lock a player out of the
game permanently. Its record persists — the run happened, and the lobby can still list it — but the
slot is free. A run merely `STOPPED` **does** hold its slot: it is still playable. That is the same
`STOPPED`/`GAME_OVER` distinction `docs/game-over.md` drew, doing real work a third time.

**Verified:** server **109/109** (+6) — paused start vs the demo's running start; five slots and no
sixth; a finished run freeing its slot while keeping its record; a stopped run keeping its slot;
delete refused to strangers and the signed-out; a Timeline refusing deletion even for an admin.

**A test-isolation trap worth knowing:** `SessionOwnershipTest`, `LobbyTest` and `SaveSlotTest`
declare identical `@SpringBootTest` properties, so Spring hands them **one cached context** — one
`SessionHost`, one registry. Records outlive `stopAll()` *by design*, so one class's leftover runs
spent another's save slots (which is how this surfaced: a green class, red in the suite). Those
classes now forget their runs in `@AfterEach`.


### Phase 5 — the lobby UI (web) — the only phase left

Everything below is frontend; the server endpoints it needs all exist.

- Grow `#ldPicker` into the lobby: server choice, then the session list + chat + play row.
- **The list** polls `GET /api/sessions`, whose rows already carry `kind` / `date` / `watching` /
  `mine` / `seats` / `standing` / `endReason`. A row is labelled by its **colony** for now (naming
  is deferred to countries — see §Naming).
- **The chat** is `GET /api/lobby/stream` (backlog replayed on connect) + `POST /api/lobby/chat`.
  Signed-out: the list and spectating work, the composer becomes a sign-in prompt.
- **Single Player** opens a **setup panel** (seed + province) and `POST /api/sessions`; the run lands
  **paused** on the map with a press-play cue. A sixth run is a 409 the panel must show honestly
  ("finish or delete one"), and a run is deletable from its row (`DELETE /api/sessions/{id}`).
- **Ranked** resolves its faces from the list + your colony's state. Its entry point waits on the
  **Timeline registry** — until there is a current Timeline to name, the button has nothing to point
  at (see §The public site).
- Unit-test the pure parts under node (`web/js/*.test.mjs`), as `district-plots.mjs` and
  `snapshot-dedupe.mjs` do: the row model (kind/state/standing → label) and the Ranked button's state
  resolution. Drive the real thing with `tools/webverify`.

### Phase 6 — sessions & Timelines live in the database (required) — ✅ DONE

**Decided 2026-07-17: sessions and Timelines are tracked in the actual DB**, not in memory. Today
`SessionHost` is a `ConcurrentHashMap` and nothing survives a redeploy — which silently erases every
run and hands out ranked retries. The DB is what makes "one colony per player per Timeline" and "the
Timeline result is final" *true* rather than true-until-the-next-deploy.

Follow the established store pattern exactly (`JdbcCommandStore` / `JdbcChatStore` / `JdbcUserStore`
via `PersistenceConfig`): an interface with an in-memory default and a JDBC implementation when a
datasource is configured, each store owning its own table, created on first use with
`CREATE TABLE IF NOT EXISTS` (portable across H2 and PostgreSQL), touching nothing else.

#### ✅ The record — DONE (2026-07-17)

**Shipped** (`registry/`, wired in `PersistenceConfig` exactly like the other stores — interface +
in-memory default + JDBC once a datasource is configured):

```
game_session(id PK, scenario, seed, province_id, owner,
             state, end_reason, tick, created_at, updated_at)

session_seat(id PK, session_id, user_id, colony_name, province_id, seat_order, seated_at,
             CONSTRAINT uq_session_seat_player UNIQUE (session_id, user_id))
```

- **`UNIQUE (session_id, user_id)` is the point**: one seat per player is now a **database
  constraint**, not an emergent property of an idempotent hash map, so it holds across restarts and
  concurrent joins. `SessionHost.joinTimeline` consults the record, so **a redeploy cannot hand a
  ranked player a second seat** — the test that proves it stops the host, rebuilds from the spec, and
  watches the rejoin be refused.
- **The sketch's separate `timeline` table collapsed into `game_session`.** A Timeline *is* a session
  (its scenario), its anchor *is* `province_id`, and its winner *is* its `end_reason` — a second
  table would restate all of it and need keeping in step.
- **The outcome is recorded before it is published.** `HostedSession.EndListener` fires on the
  session thread *before* the terminal state is visible, so anything that can see a run has ended can
  trust the record of it. This was a real bug the test caught: recording after the flip left a window
  where `state()` said `GAME_OVER` while the database still said `CREATED`.
- **In-memory is not a stub** — it is the right implementation for a run not meant to outlive its
  process (a test, a dev server), and enforces the same rule. It is simply **wrong for ranked**.

**Verified:** server **91/91**, +5 against a real H2 datasource — the wiring; founding writing a row;
seats recorded in join order with their provinces; **a redeploy refusing a second seat**; and a
finished run's verdict/tick persisted without replaying a tick.

#### ✅ Restore — DONE (2026-07-17)

Restore is the engine's own model, not a snapshot: *state = f(SessionSpec, ordered command log)*.
`SessionHost.restore(id)` rebuilds a run from its record — spec re-founds the world, a Timeline's
**seats re-found in `seat_order`** (founding is deterministic, which is why the order is recorded),
the command log replays, and the run **fast-forwards** to its recorded tick, because there is no
shortcut to tick N but to run N days. Lazy: `getOrRestore` is what every endpoint now asks, so a
restore happens on first access rather than holding boot hostage. A run still open for joins (tick
0) needs no fast-forward at all — the cheap, exact case.

- **The recorded province is checked, not trusted.** If re-founding puts a player somewhere else,
  the world is not the one the run was played in, and restore fails loudly rather than quietly
  seating someone in the wrong place.
- **A finished run is never rebuilt** — its outcome is columns. And `create` now **refuses to
  re-found it** (`RunFinishedException`), which closed a real hole: `create` unconditionally saved a
  fresh `CREATED`/tick-0 record, so a redeploy re-founding the ranked spec would have *erased the
  verdict* — handing out the retry by overwriting rather than by forgetting.
- **`STOPPED` is not finished.** Only `GAME_OVER` is. A graceful shutdown stops *every* session, so
  conflating the two would make one redeploy permanently kill everything it touched. This is exactly
  why `docs/game-over.md` split the states, and `SessionRecord.isFinished()` is the predicate that
  keeps them apart.
- **The demo is the one thing allowed to forget itself.** Its colony collapses by design, and a
  finished run stays finished — so a fresh boot would find its own shop window permanently dead.
  `SessionRegistry.forget` exists for that, and only that: forgetting a Timeline would destroy its
  verdict. (This disappears when the public site points at the live Timeline — amendment 3.)

**Verified:** server **96/96**, and end-to-end against a live server with a **file-backed** H2 across
a real process kill: a Timeline created, alice seated at the anchor (Dhenijansar 4411) and bob far
off (Sardach 2063); the process killed; a **fresh process** listing `[]`; alice's rejoin rebuilding
the whole Timeline and returning **her original seat**; and later joiners landing in third and fourth
distinct provinces — proving the picker sees the restored roster.

**Where that leaves ranked:** both halves are now in — integrity (no retries, verdicts final) and
continuity (a run survives a redeploy). Ranked's remaining blockers are product, not persistence:
the Timeline registry (which Timeline is current), scoring, and the lobby itself.

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
