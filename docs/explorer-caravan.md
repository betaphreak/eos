# Design plan: the Explorer caravan — foraging levies, Civ4 movement, and settlement fog of war

**Status:** PLAN — design only, no code yet. **Date:** 2026-07-14.
**Companion to** [`docs/food-balance.md`](food-balance.md) (the collapse diagnosis this
serves), [`docs/granary.md`](granary.md) (the sibling food-buffer lever),
[`docs/caravan.md`](caravan.md) (the band model), [`docs/caravan-march.md`](caravan-march.md)
(the daylight march this movement extends), and [`docs/caravan-trade.md`](caravan-trade.md).
**Supersedes** the deferred fog-of-war / discovered-map stub in
`agent/ExplorerCaravan.arrive()` and the `ExplorerCaravan` scaffold row of
`docs/caravan.md` §*Caravan types*.

This note plans replacing the server's six hard-seeded demo caravans with an **emergent**,
food-driven kind of band: the **Explorer** — a foraging **levy** a colony musters *under
food pressure*, which marches out to gather provisions, **reveals the map** for its home
settlement as it goes, and returns to **sell its haul** on the necessity market. Start with
**zero** caravans; split off **one at a time** as pressure demands.

## 1. Motivation — a food *import* source, and a reason to explore

`docs/food-balance.md` proved the ruler-colony collapse is a **coupled production↔renewal
trap**: food *output* is the binding constraint, and levers that only move *headcount*
(promote more, add reserve) fail because extra workers are net food *consumers* in the
crunch. What has never been tried is a **new food source that is neither grown on the
colony's plots nor bought on its own market** — food *imported* from the wider map. That is
what a foraging expedition is: it harvests wild food off provinces the colony does not farm
and brings it home. Like the granary (`docs/granary.md`) it adds supply without a worker to
feed; unlike the granary it is not limited to recycling the colony's own surplus — it draws
on the *map*.

Two things fall out for free once bands leave under their own economic logic:

- the long-deferred **fog of war** (the `ExplorerCaravan` scaffold's `TODO`): a scout that
  *reveals* the map has a purpose, and the reveal is naturally scoped **to the settlement
  that sent it** — a per-settlement point of view;
- a **live, self-explaining demo**: instead of six bands teleported onto the map at founding,
  the colony visibly *responds* to hunger by sending people out to forage — the mechanic tells
  its own story.

## 2. Decisions (resolved 2026-07-14, two Q&A rounds)

| # | Decision | Choice |
|---|---|---|
| 1 | **Scope** | Engine lifecycle — *every* ruler colony can muster explorers; deterministic, on a per-colony salted RNG. The server demo just visualizes it. |
| 2 | **Character** | A **food-import + scouting + renewal** expedition: it imports wild food, reveals the map, and sends young unmarried adults out to earn a nest egg and **come home to marry** — feeding the births/renewal loop `food-balance.md` says the colony needs. Not primarily a headcount cut. |
| 3 | **Trigger** | The **pool/Retinue larder starving** (the reserve that starves first, food-balance.md mode A). |
| 4 | **Cadence** | Start at **zero**; muster **one caravan at a time**; hysteresis cooldown between musters. |
| 5 | **Cash out** | On return the band sells **both** its foraged **food** (necessity market) **and cashes out its accumulated `Cargo`** (gathered resources/bonuses) for money — proceeds to the draftees' households (decision 14). A market act, so it happens **at the center plot** (decision 20). |
| 6 | **Draftees** | Unmarried adults from **the pool _and_ unmarried non-head adult household members** (the grown, unwed children a fission would emancipate). |
| 7 | **Draft accounting** | A draftee **stays accounted in its household/pool** ("drafted") — counted in population, kept for continuity, but **excluded from _every_ market** (labor, wedding, consumer, cash-out) while away, because it is **not physically at the center plot** (decision 20). |
| 8 | **Draft feeding** | **The caravan feeds its draftees** from its carried larder (muster provisions + forage) — while away they leave the colony's table (the relief). They do **not** draw colony/pool food remotely. |
| 9 | **Ticked by** | The **home colony** (end of `newDay`), on a **per-colony** band RNG — so it works in a single-colony `run()` (the food-balance path), not only under `SessionRunner`/`HostedSession`. |
| 10 | **Movement** | **Daylight-scaled movement points**: points/day scale with daylight hours and band size; plots are spent in **pure Civ4/C2C move-cost units**. |
| 11 | **Routing** | **Opportunistic** — cheapest Civ4-cost path out and home; forage/gather and camp on **unoccupied-resource plots that lie on the path**. |
| 12 | **Fog of war** | Per-settlement **revealed map**, from the **settlement's POV**; the settlement reveals its home area, its caravans reveal ground within a **sight radius** as they march. **Render-only** first cut (no gameplay gating). |
| 13 | **Provisioning** | Both: the **ruler buys** a provisioning larder on the necessity market **and** each draftee **takes half its household/pool ration share** out with it. |
| 14 | **Sale proceeds** | Distributed back to the **draftees' households** (the foragers are paid for their haul). |
| 15 | **On dissolution** | A collapse is **pretty much game over**: outstanding explorers **rally on the abandoned city site** and **try to re-form the colony** there (the abandoned province is the rally point; the converged bands re-found via the existing `SettlerCaravan`/`newSettlement(band,…)` seam). |
| 16 | **Future: buildable unit** | Later the Explorer becomes a **Civ4/C2C buildable unit** shown in the colony's **build queue** (visualizing the muster). Out of scope for this plan; the muster stays trigger-driven for now. |
| 17 | **Route visualization** | Draw the **actual plot corridor** the band walks (engine-computed, §9), **Overland-and-deeper only** (a dot at Atlas), **traversed + planned window**, annotated with **per-day segments + camps, resource-plot highlights, move-cost tint, and fog-reveal-ahead**, using the **full Civ6 `MovePath_*`/`MovePip_*` texture set** (§9.1). |
| 18 | **Always return** | Explorers are **never one-way**: they accumulate `Cargo` (resources/bonuses) whose **load slows them** (the RimWorld load→speed factor, `docs/caravan-march.md`), which with the pull to cash-out-and-marry (19) drives them home. |
| 19 | **Return to marry** | Back home **at the center plot**, undrafted adults re-enter the **wedding market**, and their expedition earnings fund household formation — tying the explorer loop to **marriage → births → renewal** (`docs/births.md`, `docs/food-balance.md`). |
| 20 | **Markets live at the center plot** | The colony's default markets (necessity, enjoyment, labor, wedding, capital) are hosted at the **city center plot**; an agent must be **physically present** there to participate. Away explorers are absent → no market access; on return they re-enter the center and can trade/marry (`docs/settlement-tiers.md`). The `drafted` flag is the interim implementation of "absent from the center plot"; a full presence-gated market model is the eventual generalization. |

## 3. The draft (levy) model

A colony musters an expedition by **drafting** people, not by expelling them. A draftee is
flagged and lent to the band; it returns to exactly the household/pool it left.

- **Who is draftable** (decision 6): an *unmarried adult* who is either (a) a **pool peasant**
  (`Retinue` — the reserve that starves first), or (b) a **non-head adult member of a laborer
  household** who is unmarried (a grown, still-resident child from a birth — the same person
  household **fission** would otherwise emancipate; see `docs/food-balance.md` item 4 /
  `docs/births.md`). The head and spouse are never drafted (they run the household); children
  below working age are never drafted.
- **The `drafted` flag** (decision 7). A drafted `Member` stays in its owner
  (household/pool) but is marked drafted. While drafted it:
  - **supplies no labor** — the labor-market seam skips it. Laborer households post *every
    member* to the `Labor` market (`Laborer` → `lMkt.addEmployee(this)`, "head and any
    spouse"); a drafted member must be skipped there. The pool lends its peasants as the
    builder's corvée (`Retinue.afterFeeding` → `builderLaborMkt.addEmployee`); a drafted
    peasant must be skipped there too.
  - is **not promotable and not weddable** — `Retinue.promoteHighestSkilled(...)` /
    `bestSpouseCandidate(...)` and the ennoblement scan skip drafted members (they are not
    present to be promoted or wed).
  - is **not fed by the colony** (decision 8) — the household/pool `feed()` skips a drafted
    member's ration; the **caravan feeds it** instead (§4). This is the food *relief*: the
    colony's necessity demand drops by the draftees' rations for the excursion's duration.
  - is still **counted in population** and the annual digest (it is a citizen away on
    service, not gone).
- **Undraft on return** — clearing the flag restores labor/promotion/marriage/feeding. A
  draftee that **dies on the march** (§5 mortality) is removed from its owner as a normal
  death (its household settles the estate; a pool peasant simply leaves the pool).

**Why references, not a transferred `Retinue`.** `SettlerCaravan.dissolve` moves `Member`s
*out* into the band's following (a colony is vanishing). An explorer is a **round trip** into
a *living* colony, and decision 7 keeps the people accounted at home — so the explorer holds
its draftees by **reference** (a `List<Member>` + the flag on each), plus its own carried
larder. This is a deliberate divergence from the `MarchingCaravan` "owns a following
`Retinue`" shape (see §7).

## 4. The expedition lifecycle

```
  pool larder starving ──▶ MUSTER ──▶ march OUT (forage/gather, reveal fog) ──▶ turn HOME ──▶ SELL haul, UNDRAFT ──▶ done
   (trigger, §6)          (draft K)    (opportunistic Civ4 march, §5/§11)                    (necessity mkt, food only)
```

1. **Muster.** The `ExplorerProvisioner` (§6) drafts up to `K` unmarried adults (lowest-skill
   first, so the ablest stay home for promotion/labor), picks the ablest of the cohort as the
   band's **leader**, provisions a **muster larder** (decision 13) — the **ruler buys** a
   share on the necessity market (a gamble the crown takes to relieve hunger, mirroring how it
   funds pool relief) **plus** each draftee **takes half its own household/pool ration share**
   out with it — and builds an `ExplorerCaravan` anchored at the colony's province, carrying a
   reference to the colony (home) and its draftees. *(Future — decision 16: the muster is later
   surfaced as a **Civ4/C2C buildable Explorer unit** in the colony's build queue; the trigger
   here is the interim driver.)*
2. **March out** (§5, §11). Each day the band spends its daylight-scaled movement points along
   the cheapest-Civ4-cost path away from home, **foraging** food and **gathering** goods off
   the unoccupied-resource plots it crosses (tech-gated identification, already on
   `MarchingCaravan`), **camping** on a resource plot where it can, and **revealing** the
   ground it sees into the home settlement's map (§12).
3. **Turn home** (decision 18 — explorers always return). The pull home builds as the band
   **accumulates `Cargo`**: the gathered resources/bonuses **weigh it down** (the load→speed
   factor, `docs/caravan-march.md`), so a laden band is slow and wants to *cash out*. It routes
   home when its haul is worth carrying back — a **load / haul target**, a **max-days-out** cap,
   or a low larder — drawn also by its unmarried adults' pull to **come home and marry**
   (decision 19).
4. **Cash out + marry + undraft** — all **at the center plot** (decision 20), where the markets
   live and the band must physically be to trade. On arrival it: posts its surplus **food** as a
   sell offer into the home **necessity market** (added supply, lowering the price — the imported
   food finally on the table); **cashes out its `Cargo`** (the gathered resources/bonuses) for
   money (decision 5); **distributes the proceeds to the draftees' households** (decision 14);
   and **undrafts** every surviving draftee — restoring its market access, so it resumes
   labor/feeding and **re-enters the wedding market** (decision 19), its earnings funding a
   marriage. Then the band ends.

The band is **ticked by its home colony** (decision 9) at the end of `newDay`, after market
clearing, on the colony's own excursion RNG (§7). A returned/spent band is pruned from the
colony's list.

## 5. Movement — daylight-scaled points at Civ4/C2C plot cost (decision 10)

The existing march (`docs/caravan-march.md`) bounds a day by **daylight** and taxes big bands
by **column length**; it spends a **km** budget over a plot corridor priced in `KM_PER_PLOT ×
Civ4-flat-cost × Tobler-slope`. This plan keeps the *daylight + size coupling* but re-denominates
the spend in **Civ4/C2C movement points**, so the per-plot ladder is *pure Civ4*:

- **Daily movement points** `M = base × daylightFraction − columnOverhead(size)` — more light
  and a leaner band buy more moves; a huge band in a short winter day makes almost none (the
  same pressure §4 of the march doc describes, now in move-points). At extreme latitude `M → 0`
  (the band halts on its larder), as today.
- **Per-plot entry cost — Civ4/C2C, sourced from the Civ4 XML** via `com.civstudio.data.Civ4Files`
  / `web/civ4.mjs` (dev-time fetch, the same path the terrain art uses):
  - **terrain** `iMovement` (`CIV4TerrainInfos.xml`) — flat=1, ocean/impassable excluded (land-only),
  - **feature** `iMovement` (`CIV4FeatureInfos.xml`) — forest/jungle/marsh add,
  - **hills** — the plot relief surcharge (Civ4's +1 on hills; peaks impassable),
  - **route discount** (`CIV4RouteInfos.xml` `iMovement`/`iFlatMovement`) — a road plot is cheap
    (the dormant hook `docs/caravan-march.md` already notes),
  - **river crossing** — the extra cost to cross a river edge without a bridge (Civ4's ⅓/full-move
    fording rule; `Plot` already carries the river flag `PlotCorridor` counts).
- **The Civ4 min-one-move rule** — a band with movement points left always enters **at least one**
  plot even if the plot costs more than it can pay (so a unit is never frozen by a single
  expensive tile). Fractional points carry across days.
- **Boundary hop** — crossing a province edge costs a per-hop unit (or the centroid haversine
  re-expressed in move-points); calibration below.

This replaces the km-corridor spend inside `MarchingCaravan.tick` with a move-point spend at
Civ4 costs; the daylight/column machinery (`March`, `MarchConfig`, `MarchDay`, the camp) is
reused. `KM_PER_PLOT` survives only for reporting distance, not for the movement decision.

## 6. The trigger — an `ExplorerProvisioner` (decisions 3, 4)

A colony step-action (registered by `SimulationHarness.foundStandardColony`, exactly like
`DynamicFirmProvisioner`), holding per-colony hysteresis state:

- **Signal**: the pool/`Retinue` larder is *starving* — `retinue.getLastStarved() > 0`, or the
  larder is below a small fraction of its per-peasant buffer (`getLarder() <
  starveFactor · size · bufferDays`). This is the reserve that empties first (food-balance.md
  mode A).
- **Zero-start, one-at-a-time** (decision 4): the colony founds with **no** explorers; each
  time the signal fires *and* the cooldown has elapsed *and* draftable adults exist, muster
  **one** caravan of up to `K` draftees, then start a **cooldown** (`MIN_MUSTER_INTERVAL_DAYS`,
  the firm-provisioning hysteresis pattern) before the next may leave. A cap on **concurrent
  outstanding** explorers bounds the fleet.
- Deterministic: draftee selection is lowest-skill-first (no RNG); the muster consumes no
  economic RNG (new band movement rides the per-colony excursion stream, §7).

## 7. Architecture & integration

- **`ExplorerCaravan` vs `MarchingCaravan`/`Retinue`.** The draft model (references + a carried
  larder, people still home — §3) does not fit `MarchingCaravan`'s "owns a following `Retinue`"
  contract. Plan: **lift the reusable march bits** (daylight day, forage, gather, camp,
  tech-gated identification, journal) to operate over a **head-count + a carried larder + a
  member list**, not necessarily a `Retinue` — so `ExplorerCaravan` reuses the march without a
  transferred pool. Either (a) generalize `MarchingCaravan`'s "following" to an interface the
  `Retinue` and a lean `DraftedBand` both satisfy, or (b) give `ExplorerCaravan` its own lean
  larder/forage over the member list. **(a)** is cleaner and keeps one march. *(Decide at
  implementation.)*
- **Home-colony ownership & ticking.** The colony holds `List<ExplorerCaravan> excursions`,
  ticks each at the end of `newDay` (after `market.clear()`), and prunes the returned/spent.
  It ticks them on a **per-colony excursion RNG** (`RngSeed.forColony(Stream.EXCURSION, idx)` —
  a new salted stream, per the "new draws get their own stream" convention) so multi-colony
  `SessionRunner` runs stay deterministic per colony and a single-colony `run()` (which does
  **not** go through `SessionRunner.tickBands`) still drives its explorers.
- **Render vs double-tick.** For the web map the band must appear in the render snapshot. Plan:
  **register excursions with the session** (`GameSession.addCaravan`) for rendering, but mark
  them **colony-ticked** so `SessionRunner.tickBands` / `HostedSession.tickBands` **skip** them
  (their home colony ticks them) — one predicate avoids the double tick. Session-level
  *migration/dissolution* bands are unaffected.
- **Determinism**: reveal (§12) and Civ4 movement are RNG-free deterministic functions of
  position/date; the only RNG (route tie-breaks, wander target) is the per-colony excursion
  stream. Band-free colonies draw nothing — byte-identical to before.

## 8. Fog of war — a per-settlement point of view (decision 12)

Each settlement keeps its **own** memory of the map, revealed by itself and its caravans:

- **State — a per-`Settlement` `RevealedMap`.** Two granularities, matching the viewer's two
  regimes: **revealed provinces** (coarse — the whole province is "known" once entered/seen)
  and, for provinces a caravan actually crossed, **revealed plots** (fine). Civ4's three tiers
  map on: **unrevealed** (never seen — black), **explored** (seen once, remembered — dimmed),
  **visible** (in sight *now* — bright). *Explored* is the persisted `RevealedMap`; *visible*
  is a live overlay (the settlement's home radius + its caravans' current sight this tick).
- **Reveal.** The settlement reveals its **home province + a radius** at founding. A **home
  caravan** reveals every province/plot within a **sight radius** of its moving position as it
  marches — the "explore the map" half of the explorer's role, now with somewhere to record it.
- **Resource knowledge.** The **tech-gated resources** a band *identifies* on the plots it sees
  (`MarchingCaravan.identifies`) are recorded into the settlement's known map — so the
  settlement learns *where the wild food and ore are*, the seam a future directed (non-
  opportunistic) forage or a trade route would read.
- **Render.** The web viewer draws unrevealed ground as **fog** from the **active settlement's**
  POV, explored-but-not-visible **dimmed**, visible **bright** — a real reason the map starts
  dark and opens up as the colony sends scouts out. First cut: **province-granular fog** (cheap,
  matches the coarse map); per-plot fog within explored provinces is a refinement.
- **Scope guard.** Revealing the whole world per plot is 2.6 M plots — untenable globally. The
  `RevealedMap` only ever holds what a settlement's bands actually reached: provinces (thousands
  at most) and plots of the handful of provinces they crossed.

**Render-only (decision 12).** Fog is a **visualization** — no gameplay effect. Bands still
route freely through unrevealed land (a scout's job is to *find* the ground). Whether fog should
later **gate** anything (a colony can only target known resources; bands prefer known-safe
corridors) is left for a future cut, and the `RevealedMap` is built so that gating can be
layered on without rework.

## 9. Route visualization — the per-plot daily path (decision 17)

Today a band is drawn as a trail of **province-centroid** dots (`web/js/overlays/live.mjs`
`trails`), so both its history and its heading are centroid-level. The plan replaces that with
the **real plot corridor** the band walks — the natural output of the Civ4 movement model (§5),
computed by the **engine** and exposed in the render snapshot. **The web never re-paths** (it
must not reimplement Civ4 movement, or the drawn line and the costed path drift); it draws what
the engine hands it.

- **Overland-and-deeper only** (17a). At World/Atlas zoom the band is just a dot (a per-plot path
  is sub-pixel there); the plot route appears once zoomed to **Overland** (`band() ≥
  BAND.PROVINCE`) and sharpens into plot tiles at **Ground**. Drops onto the existing band spine
  (`bands.mjs` `atLeast`/`bandAlpha`).
- **Traversed + planned window** (17b). A **solid** line for plots already crossed (the trail),
  plus a **dashed look-ahead** of the next `N` days' planned plots — a bounded window (recent
  traversed + planned ahead), not the whole 5,264-province journey (perf). The snapshot exposes
  that window as plot raster coords → lat/long, refreshed each tick.
- **Annotations** (17c — all four):
  - **per-day segments + camps** — the path is split into one segment per day's march with a
    **camp marker** at each night's stop, visualizing the daylight-bounded cadence (a short
    winter or rough-terrain day is a visibly short segment);
  - **resource plots highlighted** — the forageable food / gatherable goods plots on the path
    (tech-gated to what the band can identify — `MarchingCaravan.identifies`) are marked, showing
    *why* it routes where it does;
  - **move-cost tint** — each plot tinted by its Civ4 movement cost (flat cheap → forest/hill/
    river dear), so the terrain penalty driving the route is visible;
  - **fog reveal ahead** — the sight radius the planned path will reveal into the home
    settlement's fog map (§8) is shown, tying the route to the exploration it performs.

Net: the explorer becomes self-explanatory on the map — you see the plots it will cross, when it
camps, which resources it is after, why it detours, and what it will uncover. Building this atop
the engine-computed path also motivates fixing the corridor/centroid **double-count**
(`docs/caravan-march.md`) so the line drawn *is* the line costed.

### 9.1 The art — Civ6's movement-path lens textures

Civ6 ships **exactly this iconography** in its SDK, and we already bake Civ6 art (the district
hex tiles) — so the route reuses real game art rather than hand-drawn lines. Under
`.civ6-cache/Civ6/pantry/Textures/` (the Steam SDK Assets junction) is a complete
**`UILensMaterial`** family — flat overlay textures projected onto the map, ideal for the 2D
viewer:

- **`MovePath_Valid` / `MovePath_Invalid` / `MovePath_FOW` / `MovePath_Queue` / `MovePath_Shadow`**
  — the continuous **path ribbon** (reachable / beyond-reach / into-fog / queued-waypoint /
  drop-shadow casing);
- **`MovePip_{Valid,Invalid}[Plus|Minus][FOW]` / `MovePip_Queue` / `MovePip_*Shadow`** — the
  per-plot dotted **pips**, with ± and fog variants (Civ6's per-tile move markers).

The mapping onto §9's decisions is almost one-to-one:

| Route-viz element (§9) | Civ6 texture |
|---|---|
| plots crossed / traversed trail | `MovePip_Valid` + `MovePath_Valid` |
| planned look-ahead within **today's** march | `MovePip_Valid` (per-day segment 0) |
| planned plots on **later** days | `MovePip_Invalid` (dimmer) — per-day segments |
| planned path into **unrevealed** ground (fog reveal ahead) | `MovePip_*FOW` / `MovePath_FOW` |
| **destination / waypoint** marker | `MovePath_Queue` / `MovePip_Queue` |
| nightly **camp** marker (per-day segment ends) | `CR_GoodyHut` (a tents camp — CivRoyale scenario textures; `CR_BarbCamp` for a rougher/warband look) |
| dark **casing** under the path (live.mjs draws one by hand today) | `MovePath_Shadow` |
| move-cost **±** emphasis | `MovePip_*Plus` / `MovePip_*Minus` |

Pipeline: `.dds` → decode → **WebP** via the existing Civ6 art path (`web/civ6.mjs`
`resolveTexture`, the sharp bake in `build.mjs` — [[web-assets-webp]], [[civ6-cache-junction-bash]]),
the same route the district tiles and button icons already take. No Civ4/C2C movement art exists
(its go-to path is engine-drawn plot highlights, not sprites), so **Civ6 is the source**.

## 10. Phased implementation plan

Each phase is independently compilable/testable; earlier phases are inert until the trigger
(Phase 4) fires, exactly as the granary/fission machinery shipped dormant.

- **Phase 1 — the draft flag.** `Member.drafted` (or a colony-held drafted set) + the exclusions:
  labor supply (`Laborer`/`Retinue`), promotion/marriage/ennoblement scans, and colony/pool
  **feeding** all skip a drafted member; population/digest still count it. No caravan yet —
  drive it with a unit test that drafts a member and asserts it stops laboring/eating and is
  restored on undraft. Byte-identical when nothing drafts.
- **Phase 2 — the `ExplorerCaravan` round trip.** Muster from draftees (references + a carried
  larder + a leader), the OUT→HOME→SELL→UNDRAFT lifecycle over the **existing** march
  (km-corridor movement for now), the caravan **feeds** its draftees from its larder + forage,
  sells the surplus into the necessity market, undrafts on return. Home-colony ticking + the
  per-colony excursion RNG. Unit test: a colony musters a band, it forages a surplus, returns,
  the necessity market receives the food, the draftees are restored.
- **Phase 3 — Civ4/C2C movement.** Swap the km-corridor spend for **daylight-scaled movement
  points at Civ4 per-plot costs** (terrain/feature/hills/route/river from `Civ4Files`), the
  min-one-move rule. Test the per-plot cost ladder and that a road/flat route beats a
  forest/hill one.
- **Phase 4 — the `ExplorerProvisioner` trigger.** Pool-starvation signal, zero-start,
  one-at-a-time, hysteresis, concurrent cap; wired into `foundStandardColony`. **Measure** on
  the default Dhenijansar colony (seed 7654321) against the food-balance baseline — does the
  imported food move the collapse horizon? Record in `docs/food-balance.md`.
- **Phase 5 — route visualization (§9).** Expose the engine's per-plot corridor window
  (traversed + planned, plot raster→lat/long) in the render snapshot; draw it Overland-and-deeper
  with per-day segments + camps, resource highlights, move-cost tint. Replaces the centroid trail
  in `web/js/overlays/live.mjs`. Web unit tests where feasible (the [[web-unit-tests-wanted]]
  direction — `node:test`). Independent of fog; can land before or with it.
- **Phase 6 — fog of war + demo.** Per-settlement `RevealedMap`, reveal on march, the render fog
  layer from the active settlement's POV (and the fog-reveal-ahead annotation of §9); and
  **replace** `SessionHost.seedDemoCaravans` (the six hard-seeded bands) with the emergent
  explorers the colony now musters under pressure. Bump the reactor patch version + add a trivia
  line (`web/assets/loading/trivia.json`).

## 10. Open questions / calibration

- **Draft cohort size `K`**, **haul target**, **max-days-out**, **muster hysteresis**, and the
  **concurrent-explorer cap** — all placeholders, tuned in Phase 4.
- **Movement calibration** — base movement points, the daylight→points curve, the column-overhead
  term, the boundary-hop cost, and the Civ4 cost scale relative to the retained `KM_PER_PLOT`
  reporting.
- **Selling mechanics** — one-shot dump on arrival vs. a few days of selling (the *proceeds*
  destination is decided — decision 14, the draftees' households — but how the surplus enters the
  market over one or several clears, and how the per-household split is weighted, are open).
- **Provisioning split** — decision 13 fixes *both* sources (ruler buy + half the household
  share); the *ratio* between them and the absolute per-head provision are calibration.
- **Fog granularity** — province-only first vs. per-plot within explored provinces (the
  render-only vs gameplay question is decided — decision 12).
- **Rally-and-re-form on dissolution** (decision 15) — a collapse is game over, and outstanding
  explorers **rally on the abandoned city province and try to re-found the colony** there. Open
  mechanics: the departing main band (the `SettlerCaravan` dissolution produces) vs. the rallying
  explorers — do the explorers **merge with** the migrant band at the old site, or re-form
  independently while it migrates elsewhere? How several in-flight explorers **converge and
  merge** into one re-founding band (leader precedence, hoard/larder pooling), and the **readiness
  test** to re-found vs. keep waiting for stragglers. Reuses `SettlerCaravan` +
  `GameSession.newSettlement(band,…)`; the new piece is the *rally* (redirect explorers to the
  abandoned site) and the *merge*.
- **Non-food cargo** — a later cut sells the gathered `Cargo` once a raw-goods market or the
  trade caravan (`docs/caravan-trade.md`) exists.
- **Buildable-unit surfacing** (decision 16) — wiring the muster into a Civ4/C2C build queue is a
  later cut; deferred behind the trigger-driven muster.
