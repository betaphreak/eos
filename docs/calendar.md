# The liturgical calendar

Current-state architecture reference (extracted from `CLAUDE.md`). How the
15th-century rest-day calendar classifies days and gates firm labor.

- `calendar/` — classifies every in-game date as a `DayType`
  (`WORKDAY`/`WEEKEND`/`HOLIDAY`) on the **15th-century Western (pre-Reformation)
  calendar**. `LiturgicalCalendar.dayType(LocalDate)` is a pure date lookup: a feast
  day is a `HOLIDAY` (it **outranks** the weekend — a feast on a Sunday reports as
  `HOLIDAY`); any other **Sunday** is a `WEEKEND` (in the period the *only* weekly
  rest day is Sunday — Saturday is a workday, so there is no modern Sat–Sun weekend);
  everything else is a `WORKDAY`. The feast set is a **curated list of fixed-date
  universal feasts** loaded once from `/feasts.json` (31 feasts: Christmas, Epiphany,
  Candlemas, the Apostles' days, Michaelmas, All Saints, etc.) — the **movable**
  Easter-derived feasts (Ascension, Pentecost, Corpus Christi…) are deliberately
  *not* modeled, and feasts do **not** vary by region. Like `SlotTable` it is pure
  (seed- and location-independent), so a single `LiturgicalCalendar` is **shared** by
  every colony in a `GameSession` (loaded at session start, threaded into the
  `Settlement` constructor) and exposed via `Settlement.getDayType()` /
  `getDayType(LocalDate)`.

- **`DayType` gates labor by firm.** Each firm declares which day types it operates
  on via `Firm.operatesOn(DayType)` (default: **workdays only**). On a day it does
  not operate a firm hires no one, so it produces nothing — its workers rest. The
  overrides realize the rest calendar: **enjoyment firms** also run on the
  **weekend** (`EFirm` → workday + Sunday: the leisure trade keeps going on the day
  off, but shuts on feast days); the **export firm** runs **every day**
  (`StrategicFirm` → the colony's strategic lifeline, worked by the dedicated noble
  class, so it ignores the rest calendar — on feast days the laborer firms are all
  shut, leaving the nobles the only ones working, as required); **necessity, capital
  and the builder** keep the default workday-only rule. So on a workday everything
  runs; on Sunday only enjoyment (laborers) and export (nobles); on a feast only
  export (nobles). The gate applies only once the colony is `started()` — the pre-run
  seeding clear hires every firm so step 0 has a workforce whatever day it falls on.
- **No labor-pool flooding.** Because labor is a single fungible pool, naively
  closing most firms on a rest day would dump the whole workforce into the few open
  ones. `LaborMarket` avoids this with a **fair-share cap**: a closed firm is still
  registered (so its wage budget still counts toward the total against which every
  firm's share of the workforce is sized) but is allocated *no one* — it reserves its
  budget-proportional slice and those workers simply rest. So an open firm gets only
  its own normal share; the rest of the workforce sits idle. On a normal day (all
  firms open) this is identical to the old allocation, so normal days are unchanged.
- **Revenue smoothing keeps closed firms solvent.** A `ConsumerGoodFirm`'s
  labor-share wage budget is sized off a **30-day average revenue**
  (`REVENUE_SMOOTH_WIN`), not the single day's revenue. This is load-bearing for the
  rest calendar: with the produce-from-yesterday's-labor lag, a firm that rests has a
  zero-supply day on reopening, and budgeting off that single zero day would collapse
  the wage budget to zero, starve the firm of labor, and oscillate into a production
  collapse. Smoothing over the rest-day cycle keeps the budget positive through
  closed-day runs.
- **Food gets extra capacity.** Necessity firms run a higher Cobb-Douglas `A` than
  the other consumer firms — `SimulationHarness.NECESSITY_TECH_FACTOR` (2.0) —
  because food production stops on the ~80 rest days a year while the population eats
  every day, so output on the working days must cover the whole year (and the extra
  hiring it drives also keeps necessity workers training `PLANTS`). **Caveat:** like
  the bank zero-profit pass-through, this whole rest-day wiring is
  calibration-sensitive — the smoothing window, the necessity factor and the
  productivity curve were tuned together against the test invariants; changing one
  needs re-validation.
