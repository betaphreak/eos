# Design note: caravan march logistics (daylight-bounded movement)

**Status:** **implemented (first cut — metric march + camp + journal).** The daylight-bounded
daily march is live: a band computes its daylight `H` from a `SolarClock` at its moving
position, its column length `L` and net distance `D = max(0, v·(H − H_camp) − L)` from its
size, spends `D` along a distance-accurate `Route` (Level-1 land routing, `docs/land-routing.md`),
and pitches a transient-plot **camp** each night. The full **order-of-march** enum + per-stage
HH:mm schedule is computed and written, together with the day's provinces-traversed and camp,
to a per-session **caravan march journal** (`CaravanMarch.csv` + `CaravanTimetable.csv`, via
`io/printer/CaravanMarchPrinter`). Code: `agent/march/` (`March`, `MarchDay`, `MarchConfig`,
`MarchElement`, `MarchFlavor`, `MarchReport`, `Camp`), the rewired `MigrantCaravan.tick`
(`tick(LocalDate, Rng)`), and the `SessionRunner` wiring; tests in
`agent/march/MarchTest`, `simulation/MigrantCaravanTest` and the journey tests
(`simulation/DhenijansarToWexkeepTest`, `simulation/ParallelCaravansTest`).
**§6 corridor-metric movement is implemented.** The band now spends its daily distance `D`
over the **plot corridor**: each leg costs `KM_PER_PLOT × corridor.totalCost` (the plots'
move cost across the current province — rough/wild ground is slower) **plus** the
centroid-to-centroid **boundary hop** into the next, with partial progress carried across
days (so a big/rough province takes several days to cross — e.g. Parusapa ≈ 9 days vs
Dhenijansar ≈ 5). **Rivers cost a full day**: `Plot` now carries a river flag, a
`PlotCorridor` counts its `riverCrossings`, and each is a ford that halts the day's advance.
The journal reports the **notable bonuses** encountered on the corridor and camps on a
corridor plot. **Still deferred:** the road/terrain speed factor (the corridor `moveCost`
road discount is a dormant hook, fed by `data/civ4/CIV4RouteInfos.xml` when roads are laid),
and promoting the camp plot into the founding `HOLDING` seat on the settle decision.
**Calibration note:** charging both the corridor *and* the full centroid hop double-counts a
province's extent somewhat; with `KM_PER_PLOT`/edge weights still placeholders this is a
tuning item, not a structural one.

**Original status:** design only — not yet implemented
**Date:** 2026-07-01
**Depends on:** the `Caravan` / `MigrantCaravan` band (`docs/caravan.md`), the **solar
clock** (`docs/solar.md` — daylight hours per position/date), the **plot model**
(`docs/plots.md`), the **province graph** (`docs/geography.md`), and the shared
**`ProvincePlotPool`** (`docs/province-plots.md`).
**Relationship to `docs/caravan.md`:** that note defines the band and the settle/unsettle
cycle, and today moves a band **one province-hop per day** — a purely *topological* step,
uncorrelated with daylight, distance, or the band's own size. This note **replaces that
hop** with a **metric, daylight-bounded daily march** and defines the **nightly camp**.
Its physics is the Roman-army march model: a day's advance is what the band can cover
between sunrise and sunset *after* paying the logistics tax of coiling and uncoiling its
own column.

## Motivation — movement should cost daylight and scale with the band

The seed observations (from the "True Size of a Roman Army on the March" analysis):

- **A day's march is bounded by daylight**, which varies with **latitude and season** —
  the exact quantity the colony's `SolarClock` already computes (`docs/solar.md`). A
  Mediterranean summer gives ~14 h of potential marching light; a northern winter far
  less. Movement should read that, not a flat hop.
- **A large band wastes daylight on itself.** An army is "a city on the move": a column
  ~25 km long for ~50 000 souls, which takes ~5.5 h just to file out of camp at 4.6 km/h.
  The "snake" uncoils at dawn and recoils at dusk; **past a certain size it spends almost
  no daylight actually advancing.** This is the in-model pressure that makes big bands
  slow (and, later, want to split or settle).
- **Practical daily distance ≪ the daylight ceiling.** 14 h of light could in theory carry
  a column 65 km, but a real foot force sustains ~20–30 km/day once rest, food,
  foraging, and camp-building are paid. The model must charge those overheads.

So a lean band in a long summer day covers ground; a huge band in a short winter day
crawls. That coupling is the point.

## Decided behaviour (this cut's four answers)

1. **Metric movement — plot-step within, hop across.** Within a province the band advances
   metrically (real km, measured in plot-length ground units); a province boundary is a
   single edge **hop** as today. (Chosen over a pure km-budget or pure gated-hop model.)
2. **Size → speed via column-length overhead.** The daily marching window is daylight
   *minus* the time to uncoil and recoil the column (which scales with band size) *minus*
   camp/forage/meals. The full "snake" model. The column's structure is a **fixed-order
   enum** (`MarchElement`: Scouts → Vanguard → Surveyors → Command → Main Body → Baggage
   Train → Rear Guard, with a roaming Flank Guard), and each stage's **depart / arrive
   hour** is computed from its length, speed and buffers (§5).
3. **Camp = a transient plot claim.** Each night the band **occupies one plot** as a
   lightweight camp — no rank reform, no banks, no markets. It becomes a real **`HOLDING`**
   only if the band decides to **settle permanently** there (that camp plot is then the
   village center — `docs/village-founding.md`).
4. ~~**Design only** — no code in this cut.~~ **Now implemented** (metric march + camp +
   journal); the plot-corridor within-province spend of §6 is the one deferred piece (see
   *Status*).

## The daily march model

### 1. Daylight budget — the solar correlation

Each day the band computes its **daylight hours `H`** from its current
`(latitude, longitude)` at the current in-game date, via the same standalone `SolarClock`
the colony uses (`docs/solar.md`): `new SolarClock(lat, long).update(date)` →
`getDaylightHours()`. The band already carries `latitude`/`longitude` on the `Caravan`
base (derived from its province), so this needs only the **current date** threaded into
the tick (see *Integration*). **At extreme latitude the band does not travel:** when
daylight is near-zero or the solar events are undefined (high-latitude winter), `D = 0`
— the band **halts and lives on its larder** until the season lengthens, rather than
crawling (decided). Polar *day* clamps to a long marching window.

### 2. Marching speed `v`

A constant from human gait, unchanged across eras:

| Quantity | Value | Source |
| --- | --- | --- |
| Pace length | 0.76 m | Vegetius-derived |
| Regular cadence | 100 paces/min → **`v_reg` ≈ 4.6 km/h** | regular march |
| Quick cadence | 120 paces/min → **`v_quick` ≈ 5.5 km/h** | forced/quick march |

An optional **terrain/road speed modifier** (roads > 1, forest/hill/mud/marsh < 1, read
from the transited plot's `Terrain.buildModifier` / a future `ROAD` improvement) is a
**later cut** — a hook, defaulting to 1.0. It is the model's answer to "why the Romans
built roads" and to Teutoburg-style disasters.

### 3. Column length `L` — the snake

The band marching in file is a column whose length scales with its size:

```
L = ceil(bandSize / abreast) × rowSpacing        (+ a baggage/animal footprint term)
```

- `abreast` — how many march shoulder-to-shoulder (road-width dependent; ~4 on a Roman
  road).
- `rowSpacing` ≈ 1.2 m between ranks.
- The **baggage/animal footprint** (the *impedimenta*) is less space-efficient than
  marching soldiers; model it as a heavier per-member footprint for the non-combatant
  fraction. First cut: a uniform footprint; a richer split later.

Sanity check against the transcript: ~50 000 beings, 4 abreast, 1.2 m ⇒ L ≈ 15 000 rows
× 1.2 m ≈ 18 km of infantry, ~25 km with baggage/cavalry — matching the stated ~25 km
column and its ~5.5 h to file out at 4.6 km/h (`L / v ≈ 25 / 4.6 ≈ 5.4 h`).

### 4. Net daily distance `D` — daylight minus the logistics tax

The camp fully relocates in a day only if the whole column can **file out, march, file
in, and rebuild camp** within the marching window. The head marches `D`; the tail starts
`L` behind and must reach the far camp, so the last element travels ≈ `D + L`. Reserving
`H_camp` for the parts that cannot overlap the march (camp construction, foraging, meals):

```
D = max(0,  v · (H − H_camp)  −  L)
```

- **`v · (H − H_camp)`** — how far a *point* could march in the usable window.
- **`− L`** — the coil/uncoil tax: the column's own length, paid once net.
- **`max(0, …)`** — a band longer than `v · (H − H_camp)` makes **zero net progress**: it
  spends the whole day filing out and back in. This *is* the transcript's "past a certain
  size the snake barely advances," and it is the in-model incentive to keep bands small
  (or settle). **`H_camp` scales with band size** (decided): a bigger band's camp takes
  longer to build and strike, so a large band loses daylight at *both* the march (the `−L`
  tax) and the camp — first cut a base ~3–4 h (build) + forage/meals, plus a size term.

**Worked example (reproducing the transcript's day).** `v = 4.6`, `L ≈ 25 km`,
`H ≈ 15 h`, `H_camp ≈ 4 h` ⇒ `D ≈ 4.6 · 11 − 25 ≈ 25 km` gross point-march minus the
column ≈ a **~20 km net camp relocation** — the transcript's planned 20 km day, with the
army spanning both camps (column 25 km > march 20 km). A band a tenth the size (`L ≈
2.5 km`) covers `≈ 4.6 · 11 − 2.5 ≈ 48 km` — dramatically faster, the reason armies
dispersed.

### 5. The order of march — a fixed-order enum and per-stage schedule

The column is not homogeneous: it files out of camp in a **fixed order of march**, each
block a named element with a set composition and place in line. Model the order as an
**enum whose ordinal *is* the position in the column**, front → back:

```java
enum MarchElement {          // ordinal = position in the column, front → back
    SCOUTS,                  // recon screen (light cavalry + archers), given a head start
    VANGUARD,                // lead fighting force (a legion + cavalry), baggage left behind
    SURVEYORS,               // engineers + escort — go ahead to lay out the next camp
    COMMAND,                 // general, bodyguard, staff
    MAIN_BODY,               // the bulk of the heavy infantry + embedded baggage
    BAGGAGE_TRAIN,           // the impedimenta — the single largest, slowest block
    REAR_GUARD;              // rear screen (cavalry + archers), no baggage
    // FLANK_GUARD is roaming — see below — not part of the ordered column
}
```

Each element `e` carries its **composition** (which units, hence head-count `size(e)` and
`abreast(e)` / footprint), from which its **length** `L(e)` and **file-out time**
`f(e) = L(e) / v` follow (§3), plus a **buffer** `b(e)` (head-start / breathing room)
granted after it fully clears camp before the next departs. `FLANK_GUARD` is **roaming** —
it trickles out over the day to patrol the column's vulnerable sides (above all the
baggage), so it sits **outside** the ordered sequence with no single depart/arrive time
(flag it `roaming` on the enum, or hold it apart).

**Per-stage timing.** With `T0` = first departure (= sunrise + a prep span `P` for
striking camp, meals and forming ranks):

```
depart(SCOUTS) = T0
depart(e)      = depart(e−1) + f(e−1) + b(e−1)     // e leaves once e−1 has cleared + its buffer
arriveHead(e)  = depart(e) + D / v                  // the head reaches the new camp
arriveTail(e)  = depart(e) + f(e) + D / v           // the whole element is in
```

The **whole column is unfurled** (the rear guard's tail clears the old camp) at
`depart(REAR_GUARD) + f(REAR_GUARD) = T0 + Σ f(e) + Σ b(e)` — total unfurl time
`= L_total / v + Σ buffers`, the ~5.5 h of the worked example. **The net-distance formula
(§4) is the aggregate of this schedule**: `D` is exactly the march distance for which
`arriveTail(REAR_GUARD)` — plus the camp-build the surveyors/main body begin on arrival —
still fits before dusk. §4 sizes the day; §5 is its hour-by-hour breakdown, and the two
agree by construction.

**Worked schedule (reproducing the transcript, `v = 4.6 km/h`, `D = 20 km`):**

| Element | Depart | Fully exits | Head arrives |
| --- | --- | --- | --- |
| Scouts | 6:00 | 6:14 | 10:21 |
| Vanguard | 6:34 (+20 m head-start) | 7:01 | 10:55 |
| Surveyors | 7:02 | 7:15 | 11:22 |
| Command | 7:16 | 7:26 | 11:37 |
| Main Body | 7:27 | 9:17 | 11:48 |
| Baggage Train | 9:22 | 11:13 | 13:43 |
| Rear Guard | 11:14 | 11:34 | 15:35 |

(Each `Fully exits = Depart + f(e)`, `Head arrives = Depart + D/v` with `D/v = 4.35 h`;
these track the transcript's 6:00 / 6:30 / 7:00 / 7:15 / 7:25 / 9:20 / 11:10 departures
and its ~10:22 scout arrival, the small deltas being the buffer choices.) Camp
construction begins as the Surveyors / Main Body arrive (~11:15–11:50) and runs
`H_build` ≈ 3–4 h, finishing ~15:30 as the rear guard files in — well before a ~21:00
Mediterranean-summer sunset, which is why the 20 km day is feasible. **Shorten the day**
(winter, higher latitude — `H` from the solar clock) and the same schedule no longer
fits before dusk: `D` must fall (§4). That is the concrete pressure daylight puts on the
march.

**Flavor note.** The full seven-element order is *army*-shaped. A **settler/admin band**
(`MigrantCaravan`) has no legions — it uses a reduced subset (conceptually
`VANGUARD → MAIN_BODY → BAGGAGE_TRAIN`, or a single block for a small band), while a
**military band** (the future army — `docs/caravan.md`) uses the full order with scouts,
surveyors, command and guards. The enum is the general structure; each flavor populates
the elements it fields (an unfielded element has `size 0`, contributing no length or
time).

### 6. Spending the budget over the route — plot-step in, hop across

The band holds a **route** — a distance-accurate province path plus, per province, the
**plot corridor** it crosses (entry portal → exit portal). Producing that route (real km
between provinces, and the plot count/cost through each) is the **land-routing** system
designed in **[`docs/land-routing.md`](land-routing.md)** — a hierarchical province-graph +
per-province plot-corridor scheme that reuses the existing plot generator (and is
road-ready). Each day the band spends its `D` km along that route:

- **Within a province** — advance along the province's **plot corridor** (entry portal →
  exit portal; `docs/land-routing.md`), spending `KM_PER_PLOT × plotCost` per plot crossed,
  where `plotCost` reads the plot's terrain/feature/relief (and, later, **roads** — a road
  plot is cheap). The corridor is generated from that province's plots and **serialized**,
  so a route province's plots are materialized once and cached — bounded to the ~tens of
  provinces a route touches, never a global plot graph.
- **Across a boundary** — a single **hop** into the neighbour (as `Caravan.moveTo` does
  today), costing the **centroid-to-centroid haversine** distance between the two
  provinces' `(lat, long)` (real km from the map). Crossing consumes that much of the
  day's budget.

The band stops where `D` is exhausted and camps there. If `D` carries it across several
short provinces, it hops several times in one day; if a province is large, it may take
several days to traverse — the pure "hop per day" is gone.

- **Crossing a river costs a full day** (decided): a river on the corridor is a ford delay
  that halts the day's advance, not a small per-plot cost — so river-heavy routes are
  materially slower (see `docs/land-routing.md`).

## The nightly camp — a transient plot claim

At dusk the band **claims one plot** at its stopping position from the province's
**`ProvincePlotPool`** (the session-level, shared pool — `docs/province-plots.md`),
**occupies** it overnight as a `PlotOccupant`, and **releases** it at dawn when it moves
on. This is deliberately lightweight:

- **No rank reform, no banks, no markets.** The band stays a `CARAVAN`; the camp is
  infrastructure it pitches and strikes, not a settlement. Its money stays the carried
  **gold** hoard (`docs/caravan.md` — the bankless rung); no copper/silver bank is
  chartered for a camp.
- **Reuses existing seams.** `PlotOccupant` (the band, or a small `Camp` marker, is the
  occupant) and `ProvincePlotPool.claim`/`release` already exist; a transient claim is a
  claim released the next dawn rather than held for a colony's life.

**Continuity to settling — "the camp is a holding."** When the band **decides to stay**
(the readiness test of `docs/caravan.md` / `docs/village-founding.md`), the current camp
plot is **promoted from a transient claim into the permanent `HOLDING` seat** — the
**village center on plot 0** (`docs/village-founding.md` §*The village center*). So the
camp is literally the **seed of the holding**: every night the band pitches a would-be
center, and the night it chooses not to strike it is the night the settlement is founded
there. This is the precise mechanical reading of "the camping site should be a holding" —
a transient plot claim that, on the settle decision, *is* the founding `HOLDING`.

## Integration with the current model

- **`Caravan.tick` needs the date.** It currently takes only the band RNG
  (`tick(Rng)`); the march reads daylight, which needs the current in-game date. Thread it
  in — `tick(LocalDate, Rng)`, or give the band a small `MarchClock` (wrapping a
  `SolarClock` at the band's moving position) updated each day. `SessionRunner.tickBands`
  already drives bands once per lockstep day with a representative date, so the date is at
  hand.
- **Replaces `MigrantCaravan`'s one-hop wander.** Today `MigrantCaravan.tick` eats the
  wandering ration and takes **one hop** toward the nearest viable site. Under this model
  it instead **advances `D` km/day along the path**; the settle trigger
  (`isReadyToSettle` on reaching a viable province) is unchanged. The lean wandering
  ration (`WANDERING_RATION`) and larder-decay clock stay as `docs/caravan.md` defines.
- **Determinism.** `D`, `H`, `L` and the haversine distances are deterministic functions
  of the band's position and the date; any tie-break (which viable neighbour, among
  equals) stays on the **session band RNG**, as today. The colony index assigned at settle
  is unchanged, so "same seed → identical run" holds.
- **Band-as-data.** This composes cleanly with the **band-as-data** refinement
  (`docs/caravan.md` — *Eliminating compile-time-formed settlements*): the marching band
  is data (leader + following `Member`s + larder + hoard + position + route), and the
  camp plot is the only materialized object per night — nothing colony-scoped exists while
  the band moves.

## Calibration constants (all placeholders, to be tuned)

| Constant | First-cut value | Meaning |
| --- | --- | --- |
| `PACE_LENGTH_M` | 0.76 | stride length |
| `CADENCE_REGULAR` / `CADENCE_QUICK` | 100 / 120 paces·min⁻¹ | → `v` 4.6 / 5.5 km/h |
| `ROW_SPACING_M` | 1.2 | gap between marching ranks |
| `ABREAST` | road-dependent (~4) | how many march shoulder-to-shoulder |
| `H_CAMP_HOURS` | ~3–4 base + a size term (+ forage/meals) | daylight reserved for make/break camp; **scales with band size** |
| `PREP_SPAN` `P` | ~1 h | sunrise → first departure (strike camp, meals, form ranks) |
| per-element buffer `b(e)` | ~1 min (20 min after Scouts) | head-start / breathing room between elements |
| `KM_PER_PLOT` | **from the map's pixel↔degree scale** | ground distance one plot represents (derived, not free) |
| terrain/road speed factor | 1.0 (hook) | roads > 1, rough/wild < 1 (later cut) |

## Accepted limitations / later cuts

1. **Terrain/road speed modifier** is a hook only (default 1.0). Roads-faster,
   wilderness-slower, and the disaster case (ambush in bad terrain) come later.
2. **Baggage/animal inefficiency** (*impedimenta*) is a single footprint term now; the
   transcript's richer per-block model (main body vs. baggage train vs. flank guard) is
   future.
3. **No auto-dispersion.** The model creates the *pressure* to split a huge band (`D → 0`),
   but a band splitting into parallel columns is not modelled here.
4. ~~**Foraging is not a real food draw**~~ **Foraging is implemented.** As the band crosses
   its corridor, if the day left **surplus daylight** (the daily march is capped at a
   practical ~30 km — `MarchConfig.maxDailyKm` — so long summer days leave a forage window)
   and the corridor crossed a **food resource** (a necessity-class {@link
   com.civstudio.geo.Bonus}), the band gathers food into its larder: `surplusHours × band
   size × forageRatePerHour`, capped below the daily ration (`forageCapFraction` < 1) so it
   only **slows** the larder's decline — the band stays a decaying asset (`docs/caravan.md`).
   Free (no march cost). Reported in the journal's `Foraged` column.
   **Gathering is implemented too** (the per-good generalization of foraging, per
   `docs/manufactured-bonuses.md`): with the surplus hours foraging left over, the band
   gathers the **non-food** resources it identified on the corridor (ores, gems, luxuries…)
   into its carried **`Cargo`** (a per-good inventory on the `Caravan` base — the physical
   side the future trade caravan trades across, the hoard being the money side), at the
   slower `gatherRatePerHour`, split evenly across the distinct resources crossed. Cargo
   goods are **discrete** — no fractional elephants: part-unit work accrues as per-good
   *progress* on the band and only **whole units** enter the cargo, capped by the band's
   **carrying capacity** (`cargoCapacityPerHead` × head-count — a full band gathers nothing
   more, and a shrinking band's cap shrinks with it). Reported in the journal's
   `Gathered`/`Cargo`/`Carrying` columns. Pulling food from a settled province's market is
   still future.
   **Tech-gated identification:** a band departs with a **tech state** (its known tech ids —
   a dissolution band carries its colony's research; a fresh band defaults to
   `MigrantCaravan.DEFAULT_TECH` = `TECH_MEDIEVAL_LIFESTYLE`, i.e. the pre-known set of that
   era). It can only **identify** a resource whose {@code Bonus.techReveal} it knows, so it
   neither reports nor forages one locked behind a tech it lacks (a medieval band cannot see
   an oil/uranium/natural-gas deposit). Overridable via `setKnownTechs`.
5. **Transit accounts for plots via corridors, not a global plot graph.** Per
   `docs/land-routing.md`, a route province's plot **corridor** (entry portal → exit
   portal) is generated from the existing plot generator and gives the plots crossed and
   their cost; there is no monolithic 2.6 M-plot graph, and the exact plot-by-plot
   *sequence* is not needed globally (only the corridor length/cost and the camp plot).

## Decided (resolved 2026-07-01)

- **`KM_PER_PLOT` is derived from the map's pixel↔degree scale** (the province raster
  `ProvincePlotField` reads) — one plot's ground size follows from the map's real
  resolution, not a free constant. Shared with `docs/land-routing.md`.
- **`H_camp` scales with band size** — a bigger camp takes longer to build and strike, so a
  large band loses daylight at both the march (`−L`) and the camp.
- **The military flavor marches the same as the settler band for now** — the flavor split
  is structural only (which `MarchElement`s it fields); no distinct pace / camp rules yet.
- **At extreme latitude the band does not travel** — near-zero or undefined daylight ⇒
  `D = 0`, a forced halt on its larder until the season lengthens (a winter-bound northern
  route waits).
- **The settle decision reads the march model** — a band facing near-zero `D` (a winter it
  cannot march through) weighs settling where it stands, coupling the logistics back into
  the rise/fall cycle (`docs/caravan.md`).
- **A river on the corridor costs a full day to cross** (see §6 / `docs/land-routing.md`).

## Decided (2026-07-02 — from the RimWorld caravan comparison)

- **Load slows the march via a speed factor** (not yet implemented): `v` scales by
  RimWorld's `lerp(2× → 1×)` over the band's load fraction (cargo + larder mass
  against carrying capacity), while column length `L` stays headcount-based — one
  formula in `March`, no footprint rework, and gathering cargo finally costs pace.
  (A mass-derived baggage term in `L` is a possible later refinement; charging both
  from day one was rejected as double-counting with the per-member footprint.)
- **Over capacity, the band auto-abandons cargo** (not yet implemented): when the
  head-count shrinks under an existing load (deaths en route), the **lowest-value
  cargo units are dropped** until back under capacity — the larder is never abandoned
  before cargo — and the journal reports what was left behind. (RimWorld's
  immobilized-halt was rejected: with deaths as the shrink mechanism, a starving band
  could deadlock — unable to move, unable to recover.)

- **Pack animals are deferred to Phase C** (`docs/caravan-trade.md`): carrying
  capacity stays `cargoCapacityPerHead × head-count`. Animals (extra capacity, and
  RimWorld-style riding/draft speed) only become interesting once trade cargo volumes
  exist.
- **Route choice becomes cheapest-march-days Dijkstra** (not yet implemented): edge
  cost = the march formula itself (corridor cost + boundary hop + river days), so the
  router and the march can never disagree — Parusapa (~9 days) correctly loses to two
  3-day provinces — and roads pay off automatically the day the corridor `moveCost`
  discount activates. Plain Dijkstra: at 5,264 provinces, pathed once per journey,
  neither a heuristic nor a reachability precheck is warranted (an exhausted search
  *is* the unreachable answer); RimWorld's weighted A* + flood-fill precheck is
  planet-scale machinery this graph doesn't need. Prerequisite: resolve the
  corridor/centroid double-count (calibration note above) first, or a cost-optimizing
  router optimizes against the distortion.
- **Routes are priced at a departure-date snapshot**: all edges priced at the
  departure date; the route is then fixed unless invalidated (the target gone — the
  revalidation seam in `docs/caravan-trade.md`). Deterministic, one pathfinder run per
  journey; the seasonal error over a multi-week march is accepted (future-dated
  pricing and periodic repricing were rejected as complexity ahead of need).
- **A `MarchEstimator` gates both the settle and the trade-launch decisions** (not yet
  implemented): a deterministic, RNG-free days-of-larder-vs-ETA simulation — larder
  consumption (including expected forage) against the route walked day-by-day. The
  migrant's site choice prefers sites reachable before starvation; the trade launch
  (`docs/caravan-trade.md`) prices round-trip march-days with the same tool. The
  safety margin is an uncalibrated placeholder, and what a band does when *no*
  reachable site passes stays open (settling in place at the least-bad site is the
  presumed default).
- **Splitting stays deferred until the pressure is real**: current bands never
  approach the column sizes where `D → 0`, so the split mechanic (and merge as the
  `HOUSEHOLD → CARAVAN` gather) waits for warbands / large migrations. Accepted
  limitation 3 stands.
- **The final push is adopted** (not yet implemented): within a threshold of its
  target (~≤ one hour's remaining march), the band pushes past dusk and arrives
  instead of camping one hour short — RimWorld's rule (it exempts the last leg onto a
  destination from night rest). The journal shows a late arrival instead of a camp
  that night.
