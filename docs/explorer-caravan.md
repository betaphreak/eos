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
| 1 | **Scope** | Engine lifecycle — the **default for every `City` settlement** (headless + hosted); a `Village` (single urban plot) musters none. Deterministic, on a per-colony salted RNG. No opt-in flag — the tier decides (Phase 7). |
| 2 | **Character** | A **food-import + scouting + renewal** expedition: it imports wild food, reveals the map, and sends young unmarried adults out to earn a nest egg and **come home to marry** — feeding the births/renewal loop `food-balance.md` says the colony needs. Not primarily a headcount cut. |
| 3 | **Trigger** | **Seasonal — winter** (revised 2026-07-14). A colony musters foraging levies in **winter** (the lean season, by hemisphere), each aiming home by **mid-autumn** (a *soft* aim — worst case back by winter, and **not guaranteed**: a slow or lost band arrives late or not at all); on return the levy rejoins the settlement and works until the next winter. (The originally-chosen pool-larder signal was empirically **silent** — the pool drains by promotion/aging, it doesn't starve — so it was replaced by the seasonal rule.) |
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
- **Phase 2 — the `ExplorerCaravan` round trip. — DONE (2a + 2b).** *2a:* the `MarchFollowing`
  interface (a band marches over a `Retinue` *or* a lean `DraftBand`). *2b:* `ExplorerCaravan.muster`
  drafts the levy (flags them) + picks the ablest as leader over a `DraftBand` (references + a
  carried larder), the OUTBOUND→RETURNING→DONE lifecycle over the existing march, the caravan
  **feeds** its levy from its larder (net-positive forage cap, `forageCapFraction() > 1`), and on
  return **deposits its surplus food into the colony's granary** (`Granary.importStock` — which
  feeds the starving pool via relief draws *and* releases into the necessity market in scarcity)
  and **undrafts** its people. `ExplorerForagingTest` drives the whole trip. **Deferred to a
  follow-up:** the **paid cash-out** of the food/cargo to the draftees' *households* (decision 14)
  and the **re-entry into the wedding market** funding a marriage (decision 19) — Phase 2 lands
  the *food* loop (granary import), not yet the *money*/marriage loop; and **home-colony ticking +
  the per-colony excursion RNG** lands with the trigger (Phase 4), the test driving `tick()`
  directly for now.
- **Phase 3 — Civ4/C2C movement. — DONE (2026-07-14).** The km-corridor spend is replaced by a
  **daylight-scaled move-point budget spent at Civ4 per-plot costs**. `MarchConfig` gains
  `baseMovePoints` / `referenceDaylightHours` / `columnOverheadPerThousand` / `minDailyMovePoints`;
  `March.compute` emits `MarchDay.movePoints = max(minDailyMovePoints, base·usableFraction −
  columnOverhead)`, keeping `netMarchKm` for **reporting only** (`kmPerPlot` no longer touches the
  movement decision). `MarchingCaravan.tick` spends `day.movePoints()` and `computeLeg` charges
  `corridor.totalCost()` (the existing per-plot terrain/feature/hills/Tobler-slope ladder in
  `ProvincePlotPool.moveCost`, undiluted). Two owner rules on top:
  - **Crossing a province edge ends the turn (owner decision, 2026-07-14).** A band spends its
    move-points traversing the current province's corridor to the exit border; **the moment it
    crosses into the next province the day's movement ends** (Civ4's move-to-a-new-region rule),
    so it advances **at most one province boundary per day**. There is no separate boundary-hop
    cost and no inter-province "wilderness" plane to model — the crossing itself is the cost (the
    rest of the day). Leftover budget still funds that day's forage/gather.
  - **River ford stays a full-day halt** (Civ4's ford-ends-movement) — a corridor's river plots
    each cost a halt day; **bridges will mitigate this later** (a future improvement on the plot),
    so the penalty is deliberately kept harsh for now.
  - **Min-one-move:** the daily budget is **floored to one plot/day** (`minDailyMovePoints`) so a
    marching band **never has a zero-progress day** — polar winter or a huge column creeps on its
    larder rather than freezing (`netMarchKm` still zeroes for reporting).
  Verified: `MarchTest` (+3 cases — budget scales with daylight, shrinks with band size, never
  falls to zero) + the existing caravan integration suite green. **Future factors (dormant hooks):**
  literal per-plot min-one-move stepping (entering a single arbitrarily-expensive plot in one day)
  waits for the Phase-5 per-plot corridor position — Phase 3 spends at province-leg granularity
  with fractional carry.

- **Trails & routes — the pioneering / explored-map mechanic (owner design, 2026-07-14).** The
  **Explorer** is the only caravan that may enter **route-less (unimproved) plots**; as it moves it
  leaves a **`ROUTE_TRAIL`** on each plot it crosses. **Every other caravan requires a plot to carry
  at least a trail** — the map must be pioneered first, and "has a trail" == "explored" ties directly
  into the Phase-6 fog of war. The C2C route ladder is now **imported** (`CIV4RouteInfos.xml` →
  `geo.RouteType` via `geo/export/RouteExporter` → `/routes.json`, loaded by `TerrainRegistry`):
  `TRAIL(v1,mv100) < PATH(80) < ROAD(60) < PAVED_ROAD(40, needs stone) < RAILROAD(40/16) < …` up to
  `JUMPLANE`, plus the sea `TUNNEL` (later tiers dormant beyond the Renaissance tech cap). In Civ4 a
  route **overrides** the terrain move cost, so a plot's cost becomes `route.costFactor()` =
  `iMovement/100` (trail 1.0 = one flat plot, road 0.6, paved 0.4 …) and corridors hug the better
  roads. **Built dormant (2026-07-14):** `Plot.routeType` (a per-session mutable field, excluded
  from the canonical `.plot-cache` — `StoredPlot` serializes only generation-time terrain/feature/
  bonus, so a post-construction field never leaks in) + `Plot.layRoute`; a `MarchingCaravan.laysTrail()`
  hook (default false, `ExplorerCaravan` → true) stamps `ROUTE_TRAIL` on the bare plots of the day's
  corridor as the Explorer marches (never downgrading a better route). Recorded but (pre-cost-override)
  inert (`ExplorerTrailTest`).
  - **Route cost override — DONE (2026-07-14).** `ProvincePlotPool.moveCost` now caps the entered
    plot's flat cost at any route on it: `flat = route == null ? flatCost : min(flatCost,
    route.costFactor())`, then `× slopeFactor`. So a route overrides the **flat terrain + hill**
    cost (Civ4-style — a road negates the terrain type; `min` so a route never slows an
    already-cheap plot), while the **height-difference (Tobler slope) cost still applies** — a road
    up a steep grade is cheaper than unroaded rough ground but dearer than the same road on the flat
    (owner decision). Because `moveCost` is now route-aware and corridors are cached per-session,
    `ProvincePlotPool.invalidateCorridorCache()` is called when a trail is laid (from
    `MarchingCaravan.layTrail`) so stale corridors re-search. The A* heuristic stays admissible with
    trails (costFactor 1.0 ≥ the flat min); a future sub-1.0 road tier will need the heuristic lower
    bound dropped. Universal (helps every band); with no routes at start nothing changes, so the
    suite stays green (296/296, `PlotCorridorTest`). **Still not built:** the **trail-gated routing**
    for non-explorers (below).
  - **Trail-gated routing for non-explorers (owner rule, confirmed 2026-07-14): they require a
    trail to move — "otherwise they can't leave the settlement."** A hard passability gate: a
    non-Explorer caravan may only route through plots that carry ≥ a trail. **Consequence:** at game
    start no trails exist, so every non-explorer caravan is **stranded in its settlement until an
    Explorer pioneers a trail out** — which is exactly the intended emergent flow, but it
    **supersedes** today's freely-routing caravans (the six-caravan demo, `SettlerCaravan`/`Worker`/
    `Military`, and the caravan test scenarios all move without pre-existing trails). So activating
    the gate universally re-baselines those. Sequencing (see below): build the gated A* + a
    `requiresTrail()` band property, and turn it on as part of the **Phase-6** explorer-pioneered
    world that replaces the demo — not before, or the current scenarios strand. **Not built yet.**
  - **Trails are per-session state, not canonical map data (owner constraint, 2026-07-14).** A plot's
    `RouteType` is **mutable per-session sim state** — the demo session (seed `7654321`) carries its
    own trails — exactly like the plots' `buildings()`/districts and camp `occupant`, and **unlike**
    the seed-independent terrain/feature/bonus field. So it must **not** leak into the canonical,
    session-independent plot cache (`.plot-cache` / `PlotService`'s gzip blob — "a property of the
    map, not of a run"); it rides the **session render snapshot** (the `render/` package) instead,
    overlaid on the canonical terrain grid in the browser — the same feed districts use (see
    `docs/district-buildout.md` D3, fact 5). **Rendering trails therefore needs session support on
    the server**: a per-plot route field on the session snapshot (a `RouteType` overlay), served per
    session, then drawn with the Civ4 route art. Engine-side, `Plot.routeType` sits with the other
    per-session mutable plot state and is excluded from the canonical serialization.
  - **Routing splits into seed-independent (Explorer) and per-session (everyone else) (owner
    constraint, 2026-07-14).** Today's corridor A* (`ProvincePlotPool.corridor` + its
    `corridorCache`) is **seed-independent** — a property of the map, safe to cache globally and
    share the disk blob. That path stays exactly as-is for the **Explorer**, which routes freely over
    any ground (trails don't constrain it). But **trail-gated routing for the other caravans is
    per-session**: it may only traverse plots the *session* has already trailed, so it **cannot use
    the shared seed-independent corridor cache** — it needs a **separate per-session A*** (keyed to,
    and invalidated by, that session's laid trails), distinct from the map-level one. Net: two
    routing modes — the existing seed-independent one (Explorer / current behaviour) and a new
    per-session trail-gated one (non-explorers).
  - **Persistence — a `.session-cache/<seed>/` (owner proposal, 2026-07-14).** The natural sibling to
    the canonical `.plot-cache/v<GEN_VERSION>/`: a **per-session, seed-keyed** cache holding a
    session's mutable plot overlays (trails now; could unify built buildings/districts and camps).
    Aligns with the Phase-C "durable sessions" direction (`docs/client-server.md`) where the
    **command log** is the replayable source of truth — `.session-cache/` either stores that log or
    **materializes** the derived per-plot overlays for fast serving without a replay. Caveats: the
    truly-unique key is the **session** (a `SessionSpec` + log), not the seed — `<seed>` suffices for
    the one-session-per-seed demo but may become `<sessionId>`; and on the server it needs a
    **persistent volume** (like the plot-cache AzureFile share).
  - **Spectator UI — a "Timelines" browser (owner design, 2026-07-14).** Sessions are called
    **"Timelines"** in the UI. By default (no URL-parameter bypass) the player is presented the
    **existing Timelines to spectate** — the `lobby.html` served at `/` lists the running
    `SessionHost` sessions; a URL parameter deep-links straight into one (bypassing the browser).
    Fits the many-sessions-per-JVM model.
  - **Session ownership — first player claims admin (owner design, 2026-07-14).** A Timeline has an
    owner/admin. If it has **no owner, the first player to join becomes its admin** (claim-on-first-
    join) — a **per-Timeline** admin, distinct from the global server-admin allow-list
    (`ROLE_ADMIN` / `civstudio.auth.admins`, the `web/admin.html` console). The per-session admin
    governs that Timeline (pause/rate/commands via its `CommandLog`).
  - **Route art (owner decision, 2026-07-14): use the Civ4 route art — not procedural ribbons.**
    C2C ships the in-world roads as **3D `.nif` segment meshes** under
    `UnpackedArt/art/terrain/routes/<style>/` (path / roman roads / railroads / modern roads /
    modrailroads / warpandjumplanes / bridge / dock, ~536 nifs) — and, crucially, **with co-located
    `.dds` textures** (51 of them in the tree), the very thing the deferred D4b building sprites
    *lacked*, so the nifbake path is far more viable here. The pieces are **tile-adjacency variants**
    (`RoadA00`, `RoadB01`, … — 70 per style), but a 2D map only needs a small connection set
    (straight / curve / T / cross / end), not all 70. **Plan:** bake the Civ4 route nifs to WebP via
    the `tools/nifbake` path (as the terrain art already is), pick a style per tier
    (path→TRAIL/PATH, roman roads→ROAD, modern roads→PAVED_ROAD/HIGHWAY, railroads→RAILROAD, …), and
    stamp the connection pieces along the plot corridor. The build-button `.dds` icons stay the UI
    chips (`path.dds`, `BuildRoad.dds`, …).
- **Phase 4 — the `ExplorerProvisioner` trigger. — DONE + MEASURED.** A colony step-action
  (`ExplorerProvisioner`, off by default via `SimulationHarness.setExplorerProvisioning`) musters
  one levy at a time under food pressure — drafting the pool's least-skilled adults
  (`Retinue.draftableAdults`), provisioning half from the granary + half the draftees' pool share
  (decision 13), with a cooldown + concurrent cap. The colony owns and drives its excursions
  (`Settlement.addExcursion` / `tickExcursions` at the end of `newDay`) on a **per-colony
  `EXCURSION` RNG** (`RngSeed.Stream.EXCURSION`), so it works in a single-colony `run()`. Off by
  default → the engine suite is unchanged. **Measured** (default Dhenijansar, seed 7654321, 25 y):
  see `docs/food-balance.md`. The **pool-larder signal is empirically silent** (the pool *drains*,
  it doesn't *starve*), so the trigger became **seasonal — muster every winter** (decision 3): the
  levies leave over the lean season and return by autumn. That lifts the collapse horizon **~1452-12
  → ~1454-08 (+1.7 y)** — the gain mostly the seasonal **mouth-removal**, not the ~43 units imported.
  A modest, positive lever, in the range `food-balance.md` finds for this class; its renewal half
  (decision 19) is the piece that could matter more, still unbuilt.
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

- **Phase 7 (capstone) — default for City settlements. — DONE.** The mechanic is no longer
  opt-in: `SimulationHarness.installExplorerProvisioning` installs the provisioner for **every
  `City` colony** (with a pool), headless and hosted alike — the opt-in flag
  (`setExplorerProvisioning`) is **removed**, the settlement **tier** decides. A **`Village`** (a
  single urban plot) musters none. The default colony **Dhenijansar (4411) is a City**, so the
  standard scenarios now muster winter levies; the collapse shifts ~+1.7 y but still fits every
  smoke test's horizon, so **no test re-baselining was needed** — the only obsolete test (the
  off-by-default assertion) was replaced by a City-defaults / Village-founds-none pair
  (`ExplorerProvisioningTest`). Full reactor green (290 engine + 35 server).

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
- **Rally-and-re-form on dissolution** (decision 15) — **RESOLVED**. On collapse the dissolution
  still produces the crown's migrant `SettlerCaravan` (the old ruler leads), but it **rallies at
  the abandoned site** rather than wandering off; the outstanding explorers **converge on that
  site and merge into it** — one band, **leader by precedence** (the old ruler's heir if any,
  else the ablest explorer leader), with hoards, larders, cargo and followings **pooled**. It
  **re-founds in place once a minimum viable band has gathered** (a `MIN_SETTLERS`-style floor,
  which `SettlerCaravan` already uses); explorers still out when it settles **rejoin the
  re-founded colony** on arrival. Reuses `SettlerCaravan` + `GameSession.newSettlement(band,…)`;
  the new pieces are the **rally** (hold the crown band at the site + redirect explorers there),
  the **merge** (pool bands into one), and the **min-viable readiness** gate. The old dynasty
  rules again. (A late phase — after the round trip and trigger land.)
- **Non-food cargo** — a later cut sells the gathered `Cargo` once a raw-goods market or the
  trade caravan (`docs/caravan-trade.md`) exists.
- **Buildable-unit surfacing** (decision 16) — wiring the muster into a Civ4/C2C build queue is a
  later cut; deferred behind the trigger-driven muster.
