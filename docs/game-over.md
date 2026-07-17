# Design note: game over — a session that ends

**Status:** PLANNED (no code yet). **Date:** 2026-07-17.
**Depends on:** `docs/client-server.md` (the hosted-session spine), `docs/caravan.md` (dissolution).

## The problem

A ruler colony collapses by design. When Dhenijansar's workforce crosses
`DISSOLUTION_WORKFORCE_FLOOR` it dissolves into a `SettlerCaravan` and the survivors take to the
road — and the run is over. But nothing in the system *says* so, and three things go wrong.

**1. `STOPPED` means two different things.** `HostedSession.State.STOPPED` is both "the sim ended
itself" and "an admin stopped it". A client cannot tell a finished run from an idle one, so it
treats a dead session as a live one that went quiet.

**2. The client reconnects to a corpse, forever.** `live.mjs` reconnects on a backoff and *never
gives up* (deliberately — a redeploy must not drop the map to the picker). A stopped session emits
no frames, so the SSE connection goes idle, the Container Apps proxy drops it, `es.onerror` fires
with `readyState 2`, and `retryOrLost` reconnects. Forever.

**3. Every reconnect replays the final log delta.** A subscriber — including a late joiner —
receives the **cached last snapshot** (`HostedSession.emit()`), and a snapshot's `log` is a
**delta**. The cached final frame's delta is the departure itself, so every reconnect re-delivers:

```
Dhenijansar departed as a Caravan (196 in the following, hoard 502142 copper)
```

and the notification board adds another card. Measured against production (2026-07-17,
`caravan-demo-7654321`, `STOPPED` at tick 2639): the retained event tail holds **66 lines with zero
duplicates** — the engine logs the departure exactly once, `finishRun()` being guarded by
`dissolving && departedBand == null`. Two successive subscribes each replayed that one-line delta.
**The repetition is entirely client-side.** It is not specific to game over: a redeploy reconnect
double-posts the last frame's lines the same way.

## The decision

Confirmed with the owner (2026-07-17):

| Question | Decision |
|---|---|
| When is it game over? | **Dissolution is the end.** The colony dissolving ends the run; the departed band is flavour in the final log, not a continuation. |
| The server's game-over session? | **Stays hosted, frozen and viewable** until an admin removes it. No reaper. |
| The browser? | **Game-over screen, and stop reconnecting.** |
| How does a client tell? | **A new `GAME_OVER` state + a reason**, distinct from `STOPPED`. |

### Why "dissolution is the end" is the honest answer

`SettlerCaravan.dissolve` exists so survivors can re-found (`SettlerCaravanTest` asserts a band
re-founds at a fresh site, not the abandoned origin), and `docs/explorer-caravan.md` §15 imagines
outstanding explorers rallying on the abandoned site. **None of that can happen today, and the
blocker is structural:** the session's clock is derived from its *live colonies* —
`HostedSession.advanceOneDay()` takes `date` from non-dead colonies only, and `tickBands(date)`
no-ops when that date is `null`. So the moment the last colony dies, every band freezes: the one
that just departed, and the demo's six marchers with it.

Letting the band re-found would mean giving the session a colony-independent clock. That is real
engine work and a separate decision. Until then, ending the run *is* what the simulation actually
does — labelling it game over makes the code honest rather than changing behaviour.

## The plan

### Phase 1 — the terminal state (server)

- **`HostedSession.State.GAME_OVER`** — a fourth state: *the sim ended itself*. `STOPPED` narrows to
  *stopped from outside* (admin/shutdown). The distinction is knowable at the break: the loop exits
  either because `allDead()` or because `awaitTickPermit()` returned false (a `stop()`), so the
  `finally` picks the state from which one fired rather than inferring it.
- **An end reason** — `GAME_OVER` carries why, since the client shows it: the colony *dissolved into
  a Caravan* (workforce below the floor) versus *died* (last laborer gone / foraging band spent).
  The engine already distinguishes these in `SettlementLifecycle` (`dissolving`, `departedBand`,
  `deathDate`); the reason is read from there after `finishRun()`, not re-derived.
- **`SessionSnapshot.endReason`** — a nullable field beside `state`. `state` already ships as a
  string, so `GAME_OVER` flows to the client with no transport change.
- Leave `SessionHost` alone: a game-over session stays registered and viewable (the decision above).

### Phase 2 — stop the log from repeating (client)

Independent of game over, and worth fixing on its own terms: **`live.mjs` must not ingest the same
frame's log twice.** Track the last ingested tick and skip the `log` delta of any snapshot at or
below it — the frame is still rendered (it is the current state), only its lines are not re-posted.
That kills the duplicate cards on *any* replay of the cached snapshot, including the redeploy path.
The pure rule is unit-testable under node (`web/js/*.test.mjs`), like `district-plots.mjs`.

### Phase 3 — the game-over screen (client)

- On a `GAME_OVER` snapshot: close the `EventSource`, **do not** call `retryOrLost`, and show a
  terminal overlay naming the cause — the map frozen behind it. This is the one case where the
  never-give-up reconnect must give up.
- The reconnect loop is otherwise untouched: a redeploy or a network drop still reconnects straight
  through, because those sessions are not `GAME_OVER`.
- No restart button: session creation is admin-gated (`SessionWriteMcpTools`/`SessionController`),
  so an anonymous spectator's button would 401. Revisit when the single-player seat lands.
  **Resolved (2026-07-17)** by `docs/spectator-lobby.md` amendment 3: the public site will point at
  the current **Timeline**, and a Timeline always has a next one — so the public never sits on a
  permanent game-over screen, with no restart button needed. Until the lobby lands, an admin
  reseeds the demo; that is the status quo and it is acceptable for a dev demo.

### Phase 4 — tests

- **Server:** a session whose colony dissolves reaches `GAME_OVER` with a dissolution reason; an
  admin `stop()` still reaches `STOPPED`. `HostedSession` already runs deterministically under
  `startPaused()` + `step(n)`, so this is a unit test, not a timing race.
- **Web:** the tick-dedupe rule, unit-tested pure.
- **End-to-end:** a `tools/webverify` script driving a stopped session — the overlay appears, and no
  reconnect fires after it.

### Phase 5 — ship

Patch bump (a snapshot field — see the bump-patch-version-on-server-features note) and deploy per
`docs/client-server.md` §Deployment. No map/plot-cache change, so no rebake.

## Amendment (2026-07-17): ranked is a royale, so game over means two things

Decided while planning [`docs/spectator-lobby.md`](spectator-lobby.md): **Ranked is a royale** — one
shared world (a *Timeline*), every ranked player's colony in it, ticking in lockstep, last colony
standing wins. That splits the terminal state in two, and this note's design must not assume they
are the same:

- **Player game over (elimination)** — *one colony* collapsed. The session ticks on for everyone
  else; the eliminated player stays and spectates. This is already expressible: `ColonyView.alive`
  is per colony and the snapshot carries it. **No new state** — do not reach for `GAME_OVER` here.
- **Session game over** — the run itself is over: for a single-player session, its colony died; for
  a Timeline, one colony stands (or none). *This* is `State.GAME_OVER` + the end reason, and *this*
  is what stops the client reconnecting.

Everything in the phases above still holds — it is the **session**-level condition. The only
sharpening: `allDead()` is the right trigger for single-player, while a Timeline also ends at
**one** survivor, so the terminal condition belongs behind a scenario-aware check rather than
hard-coded to "all dead".

The **end reason** grows a case accordingly: *dissolved into a Caravan* / *died* (single-player), or
*won by \<name\>* (a Timeline). The reason is display text for the terminal screen either way.

## Notes for later

- **The clock coupling is the real constraint** and it outlives this note: bands cannot outlive
  their colony while the session date comes from live colonies. Any future "the band re-founds" or
  "explorers rally on the ruin" work starts by breaking that coupling.
  **Now scheduled (2026-07-17):** `docs/spectator-lobby.md` **Phase 0** breaks it — the session
  derives its date from its own tick. That does not change this note's plan (dissolution still ends
  a single-player run), but it is what later lets an eliminated royale player wander on as their
  departed band, demoted to `Rank.CARAVAN` (lobby amendment 2).
- **Ranked play** (see the sp-saves-and-ranked-play note) needs an end-of-run record. `GAME_OVER` +
  reason + the final tick is the natural seam — the terminal state is where a score would be
  stamped.
