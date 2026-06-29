# Design note: the daily rhythm (solar-clock day schedule)

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-29
**Depends on:** the solar package (`docs/solar.md` — `Settlement.getDawn()`/`getSunrise()`/
`getSunset()`/`getDusk()`/`getDaylightHours()`, recomputed each `newDay`), the
daylight-scaled labor model (`LaborMarket`, the `daylight/8` factor), and the travel-time
plot ladder (`docs/plots.md`), which consumes the work window this note defines.

## Motivation

The solar clock — dawn / sunrise / sunset / dusk, plus the daylight duration — is already
computed at the top of every `Settlement.newDay()`, but today only the daylight *length*
feeds the economy (it scales labor output). This note gives the day a **schedule**: the
four solar events bracket the phases a household moves through — **eat → work → enjoy →
rest** — so consumption and the market acquire a *time of day*, and the otherwise-cosmetic
twilight events (dawn, dusk) earn a functional role.

This is a **notional within-day schedule** layered over the existing discrete step loop
(one step = one day; phase order `act → bank → replace → clear → print → inflation`), not a
reordering of it — the same way the travel-time commute is a within-day accounting applied
when the labor market clears. It does not split a day into multiple steps.

## The schedule

| Window | Activity |
|---|---|
| **dawn → sunrise** (morning twilight) | households consume their **necessity** — the day's meal, drawn from the larder, before setting out |
| **sunrise → sunrise + N** | the **labor market clears** — it opens at sunrise and takes **one second per participating worker** (`N` = participant count); the goods markets resolve here too |
| **sunrise + N** | workers **depart** to their firms |
| **sunrise + N → sunset** (less commute) | **work** — what remains of the daylight window `D` after the market time `N` and each worker's round-trip commute `2·T(i)` to its firm's plot (see `docs/plots.md`) |
| **sunset → dusk** (evening twilight) | households consume **enjoyment** — evening leisure after the workday |
| **dusk → dawn** (night) | rest |

## The labor market: sunrise to `sunrise + N`

The labor market **opens at sunrise** and is **not instantaneous** — it takes **one second
per participating worker**, so a market with `N` participants runs for `N` seconds, and the
workers **depart for their firms at `sunrise + N`**. Because every laborer lives at the
center (plot 0 — the same place the market is held) there is **no inbound commute**: workers
are already present when it opens, so clearing can begin the moment there is light.

This makes the market's clearing time a **throughput cost that scales with the labor force**:
the larger the workforce, the longer `N`, the less of the day every worker has left to
work — a soft, population-scaling brake on top of the per-plot commute frontier (`docs/plots.md`).
At today's scale it is negligible (a ~400-worker colony loses `N ≈ 400 s ≈ 7 min` of a
~28 800 s day, ~1.4%); it only bites at much larger populations. The deduction is **uniform**
across all workers (everyone departs together at `sunrise + N`), unlike the commute, which
is per-plot.

**Forward note (future, with housing).** Once laborers live in houses out on plots rather
than at the center, they must first travel *in* to the central market before it can resolve,
so the **market-resolution time will be delayed past sunrise** by that inbound commute (and
the outbound trip to the firm shrinks to the home → firm leg). `D` itself stays the full
sunrise → sunset window; only the resolution time and the travel legs shift. For now, with
everyone at the center, that inbound delay is zero and resolution is exactly at sunrise.

## The working window `D`

`D` is the day's **sunrise → sunset span, to the second** —
`Duration.between(getSunrise(), getSunset()).toSeconds()` — read from the precise
`LocalTime` solar times (so it accounts for minutes and seconds, not a rounded hours
figure), and the **full window** (it is the denominator; the market time and commute are
deducted from the *numerator*, not from `D`). It is what the labor coupling in
`docs/plots.md` consumes: of the `D` seconds, the market eats the first `N` (uniform) and
the worker's round-trip commute `2·T(i)` eats more (per-plot), so the worker delivers labor
scaled by `workFactor(i) = max(0, 1 − (N + 2·T(i)) / D)`, on top of the existing daylight
scaling. Short winter days make `D` small, so the same `N` and commute eat a larger
fraction — coupling the rhythm to the seasonal/latitude daylight model already in place.

At a polar day/night where `getSunrise()`/`getSunset()` are `null` (no sunrise/sunset
event), fall back to `getDaylightHours() × 3600` (or a 0/24 h clamp) for `D`.

## Consumption windows

Necessity and enjoyment consumption are today **flat per-step events** — the `RationSize`
meal each household eats (laborer `FINE`, noble `LAVISH`, ruler `GOURMET`, pool `SIMPLE`),
and the enjoyment buy posted to the market. The rhythm gives them a **time of day**: the
meal in the **dawn → sunrise** window, enjoyment in the **sunset → dusk** window.

**Schedule only, not quantity (decided).** The windows place *when* the existing flat
consumption happens; they do **not** scale how much is consumed (the `RationSize` meal and
the enjoyment buy keep their current amounts). This keeps the food calibration intact and
avoids a second consumption coupling. So the twilight spans are presentational/temporal
here, not an economic lever.

## Open questions

- **`null` twilight.** At high-latitude midsummer there is no astronomical dawn/dusk
  (`getDawn()`/`getDusk()` are `null`). Since consumption is schedule-only this is cosmetic,
  but the window still wants a defined fallback (a nominal span). Likewise the polar
  `null`-sunrise fallback for `D` above.
- **Deep-winter `D`.** Confirm that short-day `D` under the combined daylight + travel
  scaling does not over-penalize the far farms (the deep-winter labor-scarcity concern in
  `CLAUDE.md`).
- **Mapping onto the step loop.** Exactly where the eat / enjoy events attach within the
  existing `act()`/`clear()` phases (the schedule is notional, but the implementation must
  pick a deterministic point), and whether this stays orthogonal to — or merges with — the
  plot/yield/travel work, which it shares the solar clock with but is otherwise independent
  of.