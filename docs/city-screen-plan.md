# Plan: the city screen — buildings on the map, the queue in the player's hands

**Status:** BUILT 2026-07-24 (C1–C5; not yet deployed — see C6). Phase notes are marked as built
below. The read-and-write surface the build queue never got:
[`build-queue-plan.md`](build-queue-plan.md) B6 shipped the *verb* (`queue_build`, the
pause-and-choose decree modal) but its **step 4 — the web read surface** — was never built. This
plan lands it, and fixes the one real bug on the map side: the district feed carries a plot
`index` the browser cannot resolve, so **every building draws on the city center** regardless of
which plot it stands on.

Companion to [`district-buildout.md`](district-buildout.md) (D1–D5, the district view this
extends), [`build-queue-plan.md`](build-queue-plan.md) (the engine that produces the state) and
[`zoom-bands.md`](zoom-bands.md) (band 6 — the city-micro footprint skeleton this finally fills).

## Decisions (user, 2026-07-24)

| # | Decision |
|---|---|
| **Where the queue lives** | A **dedicated city screen**, opened by clicking the **city center** on the map — not a section of the colony rail. Modelled on the tech tree (`#techModal`): a full-canvas overlay over `#stage`, top bar stays live, Esc closes. |
| **What it must show** | The crown's queue (active item + progress + pending, reorder/cancel/decree) **and the colony's plots**, each with the buildings standing on it and — the explicit ask — **housing under construction on the plots households are working**. A colony's construction is not only the ruler's queue; the households' huts are the other half. |
| **Map rendering** | **Per-plot icons + band-6 footprints.** Buildings ring the plot they actually stand on (the `index` bug fixed by shipping `x`/`y`); past band 6 the icon spiral gives way to per-plot building **footprints** — the city-micro skeleton `zoom-bands.md` speced and nothing ever drew. |
| **Write scope** | **Owner-gated, as today.** Read-only for spectators; add/reorder/cancel appear only when the caller may command the colony. The server decides (`SessionAuthz.denyColonyCommand`'s rule, projected as a `canCommand` flag) — the client never re-implements the authz rule. The demo stays interactive (an unowned run is commandable by any signed-in user). |

## Current state (what exists to build on)

- **Engine state is all there.** `BuildEconomy.getActiveBuildId()/getActiveRemaining()/
  getPendingOrders()/buildableCandidates()/submitBuildOrders()/clearBuildOrders()`; `Laborer`'s
  housing project (`targetRungId`/`targetRungCost`/`houseProgress`) and own-building project
  (B5); `PlotField.activeProjects()` for BuilderFirm commissions (elite housing legs carry
  `buildingId`). Missing: the active item's **total cost** (only the remainder is kept) and a
  **donation rate** for an ETA — both one field each.
- **Server** projects `DistrictView(index, buildings[])` per plot carrying buildings
  (`Snapshots.districtViews`) — sparse, bare ids, **no coordinates**, no owner, no in-flight work.
  `SessionSnapshot` carries `awaitingBuildChoice` + `buildCandidates` and nothing else about the
  queue: no active item, no progress, no pending list.
- **Web** `districts.mjs` is `colony.districts`' only consumer in the whole frontend, and it
  **flattens every district into one list and spirals the icons around the city center** — the
  `index` is dropped, because a browser cannot turn a position in `getDistrictPlots()` into a
  raster plot. Plot hover (`maptip.mjs`/`plotlabel.mjs`) reads the static plot cache and knows
  nothing of session buildings.
- **The verb works end to end**: `QueueBuildCommand(items, clear)` — append, or clear-then-append,
  which is already enough to express reorder and cancel — owner-gated `POST /commands`,
  tick-stamped, codec round-trip tested.
- **Precedents to copy**: `#techModal` (full-canvas overlay screen), the `#buildchoice` decree
  modal (candidate rows: icon · name · cost, click order = queue order — the picker is lifted from
  here so both surfaces stay one component), `caravan-detail.mjs`/`colony-detail.mjs` (an on-demand
  detail fetch beside the per-tick snapshot), `S.markers` (the map's non-polygon hit-test channel,
  how a caravan icon takes a click).

## C1 — Engine: the two missing readings

**Status: BUILT.** `BuildEconomy.activeCost` + `getDonationRate()` (a daily EWMA rolled from the
existing fallback hook), `Laborer.HomeProject` with `getHousingProject()`/`getOwnBuildingProject()`,
`Settlement.getActiveProjects()`, and `BuildingCatalog.displayName(id)` — the prose name the event
log now uses instead of raw `BUILDING_*` tokens (the notification board was reading them out loud).

**Goal.** Nothing behavioral; the state the surface needs, said out loud.

1. `BuildEconomy` keeps the active item's **total cost** (`activeCost`) beside its remainder, so a
   progress bar has a denominator.
2. `BuildEconomy` keeps a **trailing donation rate** (hammers/day, EWMA over the daily hook
   `applyUnhiredFallback` already runs) — the ETA's divisor. Instrumentation only; no draw on the
   economy.
3. `Laborer` exposes its two projects read-only (housing rung + own building: id, cost, progress)
   and its home plot.
4. `Settlement` exposes the builder's **active projects** (today `PlotField.activeProjects()` is
   package-private) so elite commissions in flight can be projected.

**Risk / tests.** None mechanically — getters and one arithmetic field. Test: the rate is 0 on a
colony that donates nothing; `activeCost` matches the catalog cost the brain picked.

## C2 — Server: the feed the screen reads

**Status: BUILT.** `DistrictView(index, x, y, buildings[{id,owner}], underway[{id,cost,progress,owner}])`
over every plot; `BuildQueueView` on `ColonyView`; `ColonyDetail.candidates` + `canCommand`
(answered by `SessionAuthz` in `ColonyController`, never re-derived client-side).

**Goal.** Every plot of the colony, what stands on it, what is rising on it, and the crown's queue.

1. `DistrictView` becomes `(index, x, y, buildings[], underway[])`:
   - `x`/`y` — the plot's raster coordinates (same space as the web's plot grid), **the fix**;
   - `buildings[]` — `{id, owner}` where owner is `RULER` | `HOUSEHOLD` | `NOBLE` | `NONE`
     (unowned/inherited), so the screen can say *whose*;
   - `underway[]` — `{id, cost, progress, owner}`: household housing projects and own-building
     projects on their home plot, BuilderFirm commissions on theirs, and the ruler's active item
     on the center. **This is the "housing in progress" the city screen shows.**
   - **All** colony plots project now, not only the built ones (a colony's plot count is bounded by
     its province cap — tens, not thousands), so the screen can lay out the whole settlement.
2. `ColonyView` gains `queue`: `{active, cost, remaining, pending[], ratePerDay, awaiting}`. The
   existing top-level `awaitingBuildChoice`/`buildCandidates` stay exactly as they are — the decree
   modal is not disturbed.
3. `ColonyDetail` (`GET /api/sessions/{sid}/colony`, already the rail's on-demand fetch) gains
   `canCommand` (the authz answer, not the authz rule) and `candidates[]` (the brain-scored
   buildable set) — fetched when the screen opens, not streamed every tick.

**Risk / tests.** Low. Tests: a colony with a house on a household plot projects it at that plot's
`x`/`y` with `HOUSEHOLD` owner (the regression the current draw hides); an in-flight housing
project shows as `underway` with a fractional progress; `canCommand` false for a stranger, true for
the owner and for any signed-in user on the unowned demo.

## C3 — Map: buildings where they actually stand

**Status: BUILT.** Per-plot icons, band-6 footprints (`footprints.mjs`, unit-tested), a buildings
line in the plot tooltip, and the deletion of `colonyProvince`'s bounding-box inference in favour
of the `provinceId` the feed already carried.

**Goal.** The map stops lying about where a building is.

1. `districts.mjs` rings each district's icons on **its own** plot (`x`/`y` → screen), not the
   center. The center keeps the crown's monumental stack; a household plot shows its hut.
2. **Band 6 footprints** (`zoom-bands.md` band 6, *Ground*): past the icon band, each developed
   plot draws building **footprints** — small blocks on the plot, sized/counted by what stands
   there, with an under-construction variant (scaffold/outline) for anything in `underway`. The
   spiral of button icons is an overview LOD; the footprint is the deep LOD.
3. Plot hover gains a **buildings line** (name · owner, and *"raising X — 62%"* for work in
   flight), joined from the live snapshot at that plot's `x`/`y`.

**Risk / tests.** Low-medium (a draw path with a new band). Tests: web unit tests for the
plot-keyed index (snapshot districts → by-plot lookup) and the footprint layout math.

## C4 — The city screen (read)

**Status: BUILT.** `#cityModal` + `city-screen.mjs`, opened by clicking the city centre (and via
`window.__city.open` for the headless checks), closed by Esc/✕. Empty ground folds behind a
counted "show" row.

**Goal.** Clicking the city center opens the settlement.

1. `#cityModal` — a full-canvas overlay over `#stage` (the `#techModal` pattern: sibling of
   `#stage`, top bar and advisor bar stay live, Esc/✕ closes, map painting pauses behind it).
2. Opened by **clicking the city center** on the map: the colony's centre plot registers an
   `S.markers` entry (the same channel a caravan icon uses), so the click path is the existing one.
3. Layout: **left** — the settlement's plots, each a row/tile: place name · terrain, the buildings
   standing on it (icons, owner-tinted), and a progress bar for anything `underway`; **right** —
   the crown's queue: active item (icon, name, progress, hammers remaining, ETA from `ratePerDay`),
   then the pending list in order.
4. Live off the snapshot (it already updates every frame); `candidates`/`canCommand` come from the
   one detail fetch on open.

**Risk / tests.** Medium — it is the biggest new surface. Kept honest by reusing the tech-tree
shell and the decree modal's row component rather than inventing chrome.

## C5 — The city screen (write)

**Status: BUILT.** Decree/reorder/cancel through one whole-list `queueBuild {clear, items}`, with
the reducer in `queue-edit.mjs` (unit-tested). **This is where the B6 interrupt's real bug
surfaced**: a command submitted during tick T is stamped T+1, so it was not yet due when
`maybeAwaitBuildChoice` ran — the clock stopped on the still-empty queue and stranded the very
order it was waiting for, needing a second submission to release the first. Fixed by not pausing
while `CommandLog.hasPending()`; pinned by `BuildChoiceInterruptTest`.

**Goal.** The player runs the queue without waiting to be interrupted.

1. **Decree** — a picker (the decree modal's rows, reused) appends to the queue.
2. **Reorder / cancel** — ↑↓ and ✕ on pending rows. Both express as one
   `queueBuild {clear: true, items: [...]}`: the client owns the list and submits it whole, which
   is the only race-free semantic against a queue the engine also consumes.
3. Controls render only when `canCommand`; a spectator sees the same screen, read-only. A denied
   POST (a race — ownership changed under us) shows the reason on the status line and reverts.
4. The pause-and-choose decree modal keeps working unchanged; the screen is the *unforced* path to
   the same command.

**Risk / tests.** Low-medium. Tests: web unit tests on the queue-edit reducer (reorder/cancel →
the exact `items` submitted); the server codec test already covers the wire.

## C6 — Ship

Trivia line, reactor patch bump (three poms by hand), `mvn test`, local verify via
`tools/dev-local.ps1` + `tools/webverify`, then the deploy train — **server first, then web**
(`web/` auto-deploys on push; a frontend reading fields a live server lacks is the drift this
ordering avoids).

## Sequence

```
C1 (engine getters) ──► C2 (feed) ──┬──► C3 (map draw + hover)
                                    └──► C4 (screen, read) ──► C5 (screen, write) ──► C6 (ship)
```

C3 and C4 are independent after C2 and can land in either order; C3 is the smaller and fixes a
visible wrong, so it goes first.

## Open questions (deferred)

- **A real hex plot map inside the screen** (the Civ6/D5 LSystem layout) instead of the plot
  roster — deferred with D5; the roster is honest and ships now.
- **Household queues.** A player commands the *crown's* queue only. Directing a household's own
  hammers is estate-system territory (whose labour is it?), not this feature's.
- **Off-center ruler placement** (the ≤2 outer slots the data model allows) — the screen is where
  that verb would eventually live, but the command has no plot argument yet.
- **Shared/ranked sessions** — the screen is read-only there for anyone but the colony's seat, and
  the lockstep interrupt question stays open (see `build-queue-plan.md`).
