# Notifications

**Status:** shipped 2026-07-16 (server v0.9.42).

A per-session notification board: every line the simulator logs surfaces as a card that lives for
**30 in-game days**, piling up from the bottom of the screen and drifting toward red as it ages out.

## The shape of it

```
   ⋮  (clipped off the top — oldest, reddest, about to expire)
┌────────────────────────────┐
│ 1456-02-18                 │  age 24d — deep red
│ ⚠ Necessity skyrocketed…   │
└────────────────────────────┘
┌────────────────────────────┐
│ 1456-01-01  digest  pop=12 │  age 11d — amber   (routine: dim one-liner)
└────────────────────────────┘
┌────────────────────────────┐
│ 1455-12-30                 │  age  2d — neutral (curated: full card)
│ ⚑ Dhenijansar was founded  │
└────────────────────────────┘
        ▲ newest enters here
```

- **Every** SimLog line becomes a card. Curated lines (foundings, deaths, collapses, warnings) get a
  full card; routine churn (the annual digest) renders as a dim one-liner.
- **First come, first served.** Newest enters at the bottom; older cards move up; the oldest exits
  off the top.
- **30 in-game days**, then the card expires and pops.
- **Background ramps toward red** as a card approaches expiry.
- **Right-click dismisses** a card.
- **Per session.** A new session starts an empty board.

## Why the volume is tractable

"Every line" sounds like thousands. It isn't: a real run (`output/24680/24680.log`) emits **22 lines
across 12 in-game years** at the default `INFO` floor (`SimLog.java:166`) — about one line per month
per colony — so a 30-day window holds **~1–3 cards**. The board is sparse by design; `MAX_CARDS` (60)
is a burst backstop, not the common case.

## Layout is CSS, not code

A bottom-anchored `flex-direction: column` host with `justify-content: flex-end` and
`overflow: hidden` gives, from `appendChild` alone: newest at the bottom, older pushed up, oldest
clipped off the top. FCFS order, upward motion and top-clipping need no JS layout and no
virtualization. The host sits *inside* `.stage` (unlike the `position: fixed` `#toastHost`) so it
reflows with the rail rather than being covered by it.

## What reaches the board (two floors)

`SimLog` gates the file sink and the live tap on **separate floors**, because they answer different
questions. The file is a *developer* artifact you turn down to read one run's economics
(`-Deos.log.level`, default `INFO`). The tap feeds this *player-facing* board, which wants the
dynasty/demographic narrative turned up (`-Deos.log.tap.level`, default `FINE`).

They used to share one floor, and that made the board structurally unable to show the things it most
wanted: **every** promotion, ennoblement, notable arrival and POI death is logged at `FINE`, below the
`INFO` file floor, so the board only ever saw colony lifecycle and the annual digest. All 11 FINE
statements in the engine are exactly that narrative. The demotion was deliberate
(`Settlement.java` calls the digest "the always-on alternative to the high-frequency per-event logs");
splitting the floor keeps that decision for the file and still feeds the board.

**Only `com.civstudio` records travel.** The dispatch handler attaches to the sim's own logger and
raises only *its* level — never the root's. Raising the root turns FINE on for every logger in the
JVM, and the JDK's `jdk.event.security` then dumps all **144** trusted root CAs the moment the default
`SSLContext` initialises (no network call needed). A run at `-Deos.log.level=FINE` used to write 144
`X509Certificate` lines into `output/<seed>/<seed>.log` — 79% of the file — burying the 39 lines of
actual narrative, and they would have become 144 notification cards.

Measured on `HomogeneousEconomy` (seed 7654321, ~9 in-game years): **39 sim lines at FINE** — 13 on
founding day, then ~1–5 per year — and only 12 more at FINER. Volume is a non-issue.

### Mass deaths

Ordinary deaths are logged nowhere at any level (only POI deaths, at FINE), so a starvation wave was
invisible until the yearly digest. `Settlement.noteDeathToll` raises **one aggregate event** for a day
whose household toll spikes over the colony's own trailing 30-day norm (≥3× and ≥3 deaths, and only
once that window is full). Measured against a *baseline* rather than a fixed count because a
metropolis buries people daily and a hamlet almost never — one threshold cannot serve both. A
sustained wave stops being news as it becomes the norm, so a year-long collapse does not post a card
a day (25 events across the 9-year run).

### Replacement churn is deliberately silent

An ordinary peasant promoted to succeed a dead laborer is **not** logged. That path fires once per
death, not once per new household — **258 times in a 9-year run, 36 of them on one collapse day** —
and a successor is succession, not growth. At that volume it buried the very events it sat beside (the
starvation wave that caused those deaths). Genuine new households (a fresh surname: fissions,
expedition returns, immigration) do log, as routine one-liners; notable arrivals and promotions log as
full cards. The digest still carries the totals.

## The two feeds

The board reads the same session's log twice, and the distinction is the whole design:

- **The stream** — `SessionSnapshot.log` (`render/SessionSnapshot.java`) is a per-tick **delta**,
  drained once into the frame it rides on (`SessionLogBuffer`), pushed every tick
  (`HostedSession.snapshotEveryTicks = 1`). It carries what happened *while you were watching*.
- **The tail** — `GET /api/sessions/{id}/events` serves `SessionEventLog`'s retained 4096-line ring.
  It carries what happened *before you arrived*.

The delta alone is not enough, and the demo proves it: seed 7654321 logs its founding, its
1445-01-01 digest and two starvation demotions on 1445-02-10/-11 — ticks 0, 21, 61, 62 — and nothing
else for the rest of the year. Connect at tick 170 and the stream will never show you any of it. Same
for a page reload. So `live.mjs` **rehydrates from the tail before subscribing** (`rehydrateNotify`),
asking only for the window the board can show (`from = now − 30 days`, via `notify-age.minusDays`).

A line can be in **both**: the tail records it the instant it is logged, while the delta only hands it
over at the next emit. So `seedNotify` remembers each seeded line and lets it swallow exactly one
duplicate from the stream (`consumeSeeded`) — one claim per line, so a genuine later repeat still
gets its own card.

**Both feeds derive their flags through `LogLine.of`.** They did not always: `SessionEventLog` flagged
only warnings as `curated` while `SessionLogBuffer` also matched the notable-event allow-list, so the
very same founding was a full card live and a dim routine one-liner once recovered from the tail.
Invisible until something read both — which the board now does. `LogLineTest.bothFeedsDeriveTheSameLine`
pins it.

## Modules

- **`web/js/notify-age.mjs`** — the pure clock: `ageDays`, `expired`, `ramp`, `rampColor`,
  `minusDays`. Zero imports, so it unit-tests under node (`notify-age.test.mjs`, 13 tests), following
  `band-math.mjs` / `river-geom.mjs`.
- **`web/js/notify.mjs`** — the board: `ingestNotify` (stream), `seedNotify` (tail), `resetNotify`,
  right-click wiring, the per-tick age sweep.
- **`civstudio-server` `web/SessionController.events`** — the tail endpoint; `render/LogLine.of` — the
  one place `curated`/`sev` are derived.

Wired from `live.mjs`: `ingestNotify(s.log, s.date)` beside the existing `ingestLog(s.log)` — a call
site deliberately not skipped on a hidden tab, since the log is a delta — plus `resetNotify()` on sid
change and in `stopLive`.

## Decisions worth recording

**Age is in-game days, not wall clock.** A **paused** session freezes every card's colour, and at
**speed 5** a card is born, reddens and dies within a couple of real seconds. Correct for a
clock-driven sim HUD, but it makes the board a *simulation-time* surface.

**Colour carries age; severity carries a separate channel.** Tinting the background by both collides —
an `error` line would start red with nowhere to ramp. So the background ramp is age alone, and `sev`
is a left border accent.

**The ramp is one style write per card per in-game day.** Each card caches its age and `restyle` is a
no-op unless the day advanced — the sweep runs every tick, and at speed 5 that rate is uncapped.

**Entry does not go through `requestAnimationFrame`.** `toast.mjs` uses rAF to trigger its fade;
the board forces the style flush with `void card.offsetWidth` instead. The map rasters terrain at
**~7fps under load** (measured: 1166ms frames), and an rAF-gated card stays invisible for that whole
frame. A card's arrival should be paced by the sim, not the renderer.

**Log text is data.** `toast.mjs:13` takes *trusted, pre-escaped* markup; the board cannot honour that
contract, so cards are built with `textContent` only.

**Right-click is scoped to the host.** `contextmenu` was unclaimed repo-wide. `preventDefault` fires
only when a card is actually hit, so right-clicking the map keeps the browser's own menu.

## Relationship to the existing surfaces

- **`livelog.mjs`** (the bottom strip) is the **chat** surface — scrollback plus the chat input. The
  board is the log surface. Both are fed from the same delta. *Follow-up:* the strip's now-redundant
  log rendering and curated/"show all" toggle could retire into the board.
- **`toast.mjs`** stays: a *transient wall-clock* primitive versus this *persistent sim-clock* one.
  They share a stacking idiom, not a lifecycle. A toast can transiently overlap the board's newest
  card — acceptable, since grabbing attention over other chrome is what a toast is for.

## Verification

`node tools/webverify/notify-verify.mjs http://localhost:3000 http://localhost:8080 out.png` drives a
real browser in two passes:

- **A) integration** — asserts the board matches the server's tail exactly, that a founding recovered
  from the tail is a full card (the curation round trip), that nothing is duplicated, and that the
  board **survives a page reload**. *Run it soon after a server restart:* the demo logs sparsely, so
  once the sim outruns its last line the 30-day window is legitimately empty and there is nothing to
  recover — the pass says so rather than failing. It reports an empty tail past ~tick 92 locally, and
  the window can even slide empty *between* two reads mid-test (seen against prod).
- **B) deterministic** — `stopLive()` freezes the feed, then feeds synthetic lines at known ages so
  the whole ramp (fresh → red → expiry) is exercised at once rather than waited out: 30-day card
  expires, red decreases downward (92 > 53 > 35 > 26 > 20), routine dims to 0.72, warn shows on the
  border, right-click dismisses, map right-click keeps the browser menu.

The waits are **conditions, not sleeps** — under a 1166ms map frame, a fixed 400ms wait is a coin
toss, and a card still sliding up from `translateY(8px)` has a rect 8px stale, exactly the width of
the gap between cards.

## Events carry a Rank

**Status:** shipped 2026-07-17.

`curated` is a boolean guessed from a **keyword allow-list** (`LogLine.of`), and it is already showing
its age: the tap's narrative forced `"promot"`/`"notable"`/`"succeeded"` onto the list, and the
ordinary-household wording has to deliberately *dodge* it to render routine. A keyword guess will not
survive the sprawl of many settlements — with fifty colonies logging, the board needs to know what
matters, not what a substring matched.

So each event carries the domain's existing **`Rank`** (`com.civstudio.agent.Rank`) — *the scope of
the thing the event is about*. The ladder is contiguous and alternates singular/plural:

```
 0 HOUSEHOLD   2 HOLDING   4 CITY     6 BARONY     8 COUNTY   10 DUCHY      12 KINGDOM     14 EMPIRE
 1 CARAVAN     3 VILLAGE   5 LEAGUE   7 VISCOUNTY  9 MARCH    11 PRINCIPATE 13 FEDERATION  15 HEGEMONY
```

### The visibility rule

A human player plays *at* a rank and sees a window **three rungs wide, centred on themselves**: one
below (how their vassals perform), their own level, and one above (the tier they answer to).

```
visible   ⟺  |event.rank.level() - playerRank.level()| <= 1
full card ⟺  event.rank.level() >= playerRank.level()          (your level and the rung above)
dim       ⟺  event.rank.level() == playerRank.level() - 1      (your vassals)
```

**Bounded above as well as below** — the part that is easy to get wrong. If you are playing an
adventurer company, a kingdom's war is news you can neither act on nor care about, and letting it
through would drown the band's own story. A hegemon has the same problem pointed the other way.

This replaces the `curated` heuristic outright: prominence stops being a guess about wording and
becomes a statement about scope, relative to the viewer.

The window **slides as you climb**, which is exactly the settlement-tier ladder (Captain → Ruler →
Mayor; see `docs/settlement-tier-ladder-plan.md`) — the game declutters itself:

| You play as | at rank | you see | i.e. |
|---|---|---|---|
| Adventurer company | CARAVAN (1) | HOUSEHOLD…HOLDING (0–2) | your families, your band, the holding you deal with |
| Ruler | VILLAGE (3) | HOLDING…CITY (2–4) | noble houses and the city above, not the peasantry |
| Mayor | CITY (4) | VILLAGE…LEAGUE (3–5) | the settlements under you, not their households |

A POI death is HOUSEHOLD-scope, so a captain gets a card and a mayor does not — the same event, ranked
once, filtered per viewer.

Mind the alternating ladder when reasoning about "one rank up": the rung above a COUNTY (8) is a
MARCH (9), the *collective* of counties — not the DUCHY (10) that consolidates it. One rung, not one
title you would recognise from a map.

### This is how single-player works

The player starts at the **bottom** of the ladder — an Anbennar-style **adventurer company** — and
climbs. The rank window is therefore not a convenience filter bolted onto a spectator view; it is the
game's information model. What you are told is a function of what you are, and growing changes both.
That is why the window must be bounded above: the lowest rank has to be playable on its own terms,
not a firehose of politics belonging to ranks you have not reached.

### As built

- **`SimLog.event(Rank, Level, String)`** is the event API — the honest answer to "the messages are
  logged to the default loggers instead of notifications". The rank rides as the `LogRecord`'s
  parameter, which is the one field JUL offers for structured data, so **every existing
  `log.info`/`@Log` call site stays exactly as it is** and simply reads as unranked downstream.
  Rank and level stay independent: *rank decides who cares, level decides whether it is written to
  disk.*
- **`SimLog.Entry.rank`** → **`LogLine.rank` / `rankLevel`** on the wire. The *level* is sent, not just
  the name, so the client needn't carry a copy of the ladder to compare two ranks. An unranked line is
  `-1`, below every real rank, and is always shown rather than silently dropped.
- **`web/js/notify-rank.mjs`** — the pure rule (`visibleTo` / `prominentTo`), unit-tested.
- **Mapped so far:** POI deaths, notable arrivals and promotions, household formation → `HOUSEHOLD`;
  ennoblement and noble houses → `HOLDING` (a noble *is* a holding, so a ruler's vassals reach them);
  colony founded/died/departed/dissolved, tier growth and starve-down, mass deaths, the annual digest
  → `VILLAGE`; a levy lost on the road → `CARAVAN`.

### Still open

- **The viewer's rank** is a default (`CARAVAN`, what the live demo's captain actually is) until there
  is a player seat to ask (see [[project-direction]]). `setViewerRank` re-filters the board, but only
  *downward*: cards already filtered out were never stored, so raising then lowering the rank does not
  restore them until the next rehydrate.
- **The remaining call sites** are still unranked (`Ruler`/`Captain` succession, firm charter/dissolve,
  band starvation, the harness's Mayor/Ruler reform). They show unconditionally until mapped.
- Whether a **warning/anomaly** should override the filter regardless of rank.
- `curated` still exists for the log bar and as the unranked fallback. Once every site is ranked it can
  go, and with it the keyword allow-list.

## Deferred

- **Filtering / muting** a category or a colony (subsumed by the Rank work above).
- **Click-through**: a card that frames the camera on the colony or plot it names. `LogLine` has no
  colony field (`SimLog.Entry` strips it), so this needs a wire change.
- **`stopLive` does not cancel a pending reconnect timer** — noticed while testing; a `stopLive`
  during a backoff can be resurrected by the armed timer. Pre-existing, not notification-specific.
