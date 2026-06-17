# Daylight and the solar package

Current-state architecture reference (extracted from `CLAUDE.md`). How the colony
computes the day's solar times, which feed the daylight-scaled labor output (see
the `LaborMarket` notes in `CLAUDE.md`).

- `solar/` — a vendored sunrise/sunset calculator (the Lucky Cat Labs / `ca.rmen`
  algorithm, ported from `BigDecimal` to `double`). `SolarEventCalculator.computeSunriseTime/computeSunsetTime`
  turn a `GeoLocation` (a `(latitude, longitude)` record — the project-local
  replacement for the original's external `Node`), a `Zenith` (the solar
  declination: `OFFICIAL` 90.83°, `CIVIL` 96°, `NAUTICAL` 102°, `ASTRONOMICAL`
  108°) and a `java.util.Calendar` date into a local clock time. It is **legacy
  `Calendar`-based**, so `Settlement` bridges from its `java.time.LocalDate`. The
  calculator **throws** `UnsupportedOperationException` when an event does not occur
  (the sun never reaches the zenith — e.g. no astronomical twilight at high latitude
  in midsummer); callers must catch this. (Note: the port also fixed an inverted
  range-normalization bug in `getSunTrueLongitude`.)
- The colony has a fixed **geographic location** — `latitude`/`longitude` in decimal
  degrees (north/east positive), colony-start properties threaded from
  `SimulationConfig` through `GameSession.newSettlement` into `Settlement` exactly
  like `meanSkillMale`/`meanSkillFemale`/`targetNStock` (default London, 51.5074,
  −0.1278). From these, `Settlement` **computes and stores the day's solar times** at
  the top of every `newDay` (and seeds them in the constructor for step 0):
  `getDawn()`/`getSunrise()`/`getSunset()`/`getDusk()` (UTC `LocalTime`; **dawn/dusk
  are the astronomical** sunrise/sunset, sunrise/sunset the **official** ones) and
  `getDaylightHours()` (the precalculated sunrise→sunset span). An undefined event is
  stored as `null` (and `daylightHours` as `NaN`) rather than throwing — e.g.
  dawn/dusk are null at London around midsummer, when astronomical twilight lasts all
  night. These feed the daylight-scaled labor output (see `LaborMarket`).
