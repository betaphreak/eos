# Design note: CITY (a permanent settlement) and LEAGUE (a bloc of cities)

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-18
**Supersedes:** the earlier `docs/city-rank.md` (which modelled CITY as a federation
of village-quarters — the wrong rung; that federation is a LEAGUE, below).
**Depends on:** the rank ladder (`eos.agent.Rank`, `RankLadder`, `Estate`,
`RankFactory` — see `docs/rank-ladder.md`), the founding ascent
`CARAVAN → HOLDING → VILLAGE` and the `Property` abstraction
(`docs/village-founding.md`), `GameSession`'s multi-colony support (the
`HanseaticEconomy` worked example), `SlotTable`'s special sites, the ruler's
taxation machinery (`Ruler.collectTaxes`), and the open-economy inflow/immigration
machinery (`SimulationHarness.enableExternalInflow`, `SimulationConfig.immigrationThreshold`).

## Motivation — the correction

The locked-in 16-rung taxonomy (`Rank`, sourced from the prototype JSON) settles a
question the first `city-rank.md` had to defer. The ranks **alternate** singular and
plural (`Rank.isPlural()`): even levels are single consolidated entities, odd levels
are collectives of the rank below. That places:

- **`VILLAGE`** (level 3, plural) — "an interconnected network of holdings", led by a
  **Ruler**: today's collapse-prone colony;
- **`CITY`** (level 4, **singular**) — "a complex urban center": **one** settlement
  that has urbanized into a **permanent** place, led by a **Mayor**;
- **`LEAGUE`** (level 5, plural) — "a cooperative administrative bloc" of cities, led
  by a **Legate**.

So a CITY is **not** several settlements under one head — that was the first sketch's
error. A CITY is a single settlement grown permanent; the *several-settlements*
polity is the **LEAGUE** one rung up. This also fixes the worked example: the two
adjacent Hanseatic colonies are not "quarters of one city" — they are two cities in a
**LEAGUE** (the Hansa was, literally, a league of cities). This note redoes the design
along that split, and realizes the **`VILLAGE → CITY`** gameplay promotion that
`docs/village-founding.md` left as a reserved future rung.

## The ladder in context

```
CARAVAN  →  HOLDING  →  VILLAGE        →  CITY            →  LEAGUE
(band)      (lands)     (network of       (one urban         (bloc of
                         holdings,          center, the        cities,
                         collapse-prone)    settlement made    federated)
                                            permanent)
 leader      holder      Ruler              Mayor              Legate
```

Each odd→even step **consolidates** (a network of holdings into one urban center; the
even→odd step **gathers** (cities into a bloc). `VILLAGE → CITY` is consolidation +
permanence; `CITY → LEAGUE` is federation.

## CITY — `VILLAGE → CITY`, the permanent settlement

### What a CITY commands

One settlement — **its own**, now urbanized — led by a **Mayor** (the reformed
Ruler). There is exactly one sovereign, as in a village; the difference is
**permanence** and urban scale, not multiplicity.

### The gameplay: `VILLAGE → CITY`

A village that grows large and prosperous enough **urbanizes**: its `Ruler` is
reformed one rung up into a `Mayor` (the `CITY` rung, previously reserved, becomes
realized). This is the now-valid gameplay transition. The trigger is an **organic
readiness threshold** (the analog of the `HOLDING → VILLAGE` charter test in
`docs/village-founding.md`) — candidates, calibration not structure: a sustained
laborer-population floor, a built-up settlement size (enough occupied
`SlotTable` slots), a prosperity/market-depth signal, or elapsed stable time. Default
proposal: **a sustained population + size threshold**, tunable, and later
player-driven.

### Permanence — the defining new mechanic

A `VILLAGE` is **collapse-prone**: it founds and replaces its labor force from a
finite peasant pool, and with no inflow the reserve drains and the colony spirals to
collapse (the central dynamic of `HomogeneousEconomy`/`HanseaticEconomy`; the tests
assert it). A `CITY` is **permanent — it does not collapse.** The grounding reuses
machinery that already exists: an urban center **attracts immigration**, so on
urbanizing the colony **flips from the closed regime to the open one** —
`SimulationHarness.enableExternalInflow` plus the immigration policy gated on
`SimulationConfig.immigrationThreshold`, exactly what keeps `SmallOpenEconomy` stable
and growing rather than collapsing. The peasant pool stops being a draining
death-clock and the population is continually replenished from outside.

> **Confirm:** "permanent settlement" is read here as *the colony no longer collapses*,
> realized by switching it onto the existing open-economy immigration inflow. If you
> instead meant only the narrative sense (a fixed, non-abandonable place, vs. the
> wandering Caravan), the inflow coupling can be dropped — but then a city can still
> collapse, which seems to contradict "permanent".

### The reform: `Ruler → Mayor`

The lightest reform on the ladder, and **money-conserving with no transfer**: the
`Ruler` keeps its **same gold bank** and its balances carry 1:1 via the rank-ladder
`Estate` — nothing is re-banked or minted. `Mayor extends Ruler`: it does everything
a ruler does for its settlement (taxes its nobles/banks, dynamic firm provisioning,
indulgence, the export work), so `Mayor.act()` is `super.act()` plus the urban
bookkeeping; `rank()` overrides to `Rank.CITY`, `role()` to `"Mayor"`. The promotion
also **claims the city seat** (the Rathaus special site, below) and **enables the
immigration inflow** (permanence).

### Currency

Unchanged from a ruler: the Mayor banks **gold**, the apex tier every
settlement-sovereign uses (copper commoners / silver nobles / gold sovereign). A city
is grander than a village but its money is the same apex coin — there is no fourth
tier. (Inter-settlement money only appears at LEAGUE, below.)

### Demotion

`CITY → VILLAGE` is a clean single-rung reverse (`Mayor → Ruler`, same gold bank), so
the rung is symmetric. But a permanent city is **not expected to demote** in normal
play; the reform exists as the symmetric capability and the seam a future
"sacked/depopulated city" trigger would use (mirroring the deferred demotion triggers
in the other rank docs).

## LEAGUE — a bloc of cities

This is where the federation design from the first `city-rank.md` correctly lives —
re-homed one rung up and re-titled (a **Legate** over member **cities**, not a Mayor
over village-quarters). The **four decisions taken earlier carry over verbatim**, now
applied at LEAGUE:

### What a LEAGUE commands

Several `CITY`-scoped settlements (the **members**), each a permanent city with its
own Mayor, economy and banks. The **Legate** sits above them as a session-level
holder, **keeping the Mayor title in its home city** (a Mayor-superset, just as the
Mayor is a Ruler-superset) while bearing the Legate title over the bloc.

### The locked decisions, re-homed

- **Currency / gold-only flows.** The Legate banks **gold**; every transfer that
  crosses a city boundary is gold. The **league treasury is the Legate's gold bank**
  (its home city's, doing double duty); being the crown holding it is **tax-exempt**.
- **Inter-city toll (FX fee fires).** A gold→gold inter-city transfer fires the gold
  bank's `exchangeFeeRate` on both ends, with no carve-out — federation is **tolled**,
  the gold banks profit from it. (Every price is copper-quoted, so gold crosses the
  copper boundary on each leg.)
- **Federal taxation = each member city's gold-bank profit.** `Legate.collectTaxes()`
  mirrors `Ruler.collectTaxes()` two tiers up: it skims a fraction of every *member*
  city's gold-bank `getDistributableProfit()` into the league treasury (gold→gold, toll
  fires). Its own (home city's) gold bank is the treasury, hence skipped — the existing
  crown-bank exemption. The taxation chain now runs: commoner → noble (taxed by the
  ruler/mayor) → public bank (taxed) → **member city's gold bank (taxed by the
  Legate) → league treasury**.
- **The Legate keeps ruling its home city.** No member is vacated; the Legate's `act()`
  is a Mayor-superset, `rank()` is cleanly `LEAGUE`. Money-conserving with no minting.

### Cities as holdings

The `Property` abstraction from `docs/village-founding.md` (firms → banks →
settlements) extends one more step: a **member `CITY` is a civic, non-income
`Property`** the Legate owns (`distributableProfit() == 0`, no-op `disburse()`). The
Legate holds a `List<Property>` of member cities; "lose your last member → demote" is
the same single check.

### Formation — explicit scenario wiring

A league forms by **explicit scenario wiring** (the earlier decision, now at LEAGUE):
cities choose to federate. Compose single-rung primitives, no multi-rung leap: promote
the senior city's `Mayor → Legate` (it keeps ruling its city, its gold bank becomes
the league treasury, the league seat is claimed there); **annex** each remaining city
(add its `Settlement` to the Legate's members; its Mayor stays, now taxed federally).
No proximity detector or organic threshold yet.

### Demotion

`LEAGUE → CITY` is clean (the Legate has a home city to fall back to): when the bloc
shrinks to one member, the Legate reforms back into a plain Mayor. The trigger
(loss of the league seat / last member) is deferred, like the other demotion triggers.

## Architecture mapping

- **Types.** `Mayor extends Ruler` (CITY); `Legate extends Mayor` (LEAGUE). Each adds
  one layer (Mayor: permanence/urban; Legate: federal taxation + the members list) and
  overrides `rank()`/`role()`/`successor()`. The hierarchy mirrors scope: Ruler→one
  village, Mayor→one permanent city, Legate→a bloc — all banking gold.
- **`Settlement` is a `Property`** (Phase 0 of `village-founding.md`), civic and
  zero-income — a member city is what a Legate holds.
- **`RankLadder` moves to `GameSession`** (the forcing function `docs/rank-ladder.md`
  already named): a Legate commands several colonies, so its reform cannot belong to
  one. Factories resolve their bank tier from the passed scope; a session-level salted
  `Rng` covers any league-level draw.
- **Seats (special sites).** The **Rathaus** is the `CITY` seat (in the city); a
  **league hall / Kontor** is the `LEAGUE` seat (in the senior member). Both are civic
  `SlotOccupant`s with no economic function yet — rank markers whose loss feeds the
  deferred demotion triggers. The `SlotTable` special-site unlocks
  (`{0,4,10,19,31,57}`) give a settlement out-of-band capacity for these without
  crowding its effective firm slots.
- **Permanence wiring.** `VILLAGE → CITY` flips the colony onto
  `enableExternalInflow` + the immigration policy (the open regime), so the formerly
  draining peasant pool is replenished and the city does not collapse.
- **Reforms move no money.** `Ruler→Mayor` and `Mayor→Legate` keep the same gold bank
  and carry balances 1:1; the only inter-settlement money is the federal tax (gold,
  tolled).

## Worked example: Hanseatic (two villages → two cities → a league)

The existing two-colony scenario is the natural test bed and now reads true to
history:

1. Each colony (Lübeck Altstadt, Bad Schwartau) grows `VILLAGE → CITY` on the
   readiness threshold — each Ruler becomes a Mayor, each settlement flips to the
   immigration inflow and becomes **permanent** (no longer collapsing as the present
   `HanseaticEconomy` does).
2. The two cities **federate into a LEAGUE**: the senior city's Mayor is promoted to
   **Legate** (Lübeck the senior seat, as in the real Hansa), keeping its city and its
   gold bank (now the league treasury); Bad Schwartau is annexed as a member, its
   Mayor retained and taxed.
3. The Legate skims Bad Schwartau's gold-bank profit into the league treasury each
   step, the FX toll firing on the federal transfer. The two cities still run
   independent economies; inter-city *factor* mobility (shared markets) remains
   deferred to caravan trade.

## Accepted limitations (out of scope for this cut)

1. **Inter-city factor mobility is deferred to caravan trade.** The only inter-city
   flow is upward gold taxation; labor/goods moving between member cities (the real
   economics of a trade league) needs inter-settlement geography, which rides on the
   caravan-trade roadmap item.
2. **Permanence is a regime flip, not a richer urban model.** A CITY's permanence is
   modelled by switching to the existing open-economy inflow; genuine urban dynamics
   (density, congestion, distinct urban goods/labor) are future work.
3. **Formation policy is minimal.** `VILLAGE → CITY` fires on an organic threshold (to
   be calibrated); `CITY → LEAGUE` only by explicit scenario wiring. No proximity
   auto-federation, no player UI yet.
4. **The seats have no economic function yet.** Rathaus and league hall are rank
   markers only; governance/market gating is future work.
5. **The Mayor/Legate configs are new and uncalibrated** (the urbanization threshold,
   the federal tax rate; consumption/ration inherited from `Ruler`).

## Phased implementation plan

- **Phase 0 — `Settlement` implements `Property` (byte-identical).** Civic,
  zero-dividend; lets a Legate hold a `List<Property>` of cities.
- **Phase 1 — move `RankLadder` to `GameSession` (behaviour-neutral).** Plus the
  session-level Rng salt; existing within-colony reforms stay identical.
- **Phase 2 — `CITY`: the `Mayor` type + `VILLAGE → CITY` reform + permanence.**
  `Mayor extends Ruler`, the registered `CITY` factory (no re-banking), the Rathaus
  seat, and the inflow flip. The organic threshold can land here (off by default) or in
  Phase 3. With no village crossing the threshold, existing runs are unaffected.
- **Phase 3 — the urbanization threshold + a permanent-city test.** A `VILLAGE` driven
  past the threshold urbanizes and then **sustains itself** (the inverse of the
  collapse smoke tests): a colony that would collapse as a village survives as a city.
- **Phase 4 — `LEAGUE`: the `Legate` type + federation wiring.** `Legate extends
  Mayor`, the `LEAGUE` factory, cities-as-holdings, `Legate.collectTaxes`, the league
  seat, and the promote+annex scenario — exercised by a `HanseaticLeague` test (two
  cities federate; the treasury accrues from federal taxation; the FX toll fires;
  succession reseats the home-city Mayor reference and the Legate).
- **Phase 5 — (after caravan trade) inter-city factor mobility**, and the demotion
  triggers (`CITY → VILLAGE` on depopulation, `LEAGUE → CITY` on loss of the seat/last
  member).

## Open questions deferred to later

- The **urbanization threshold** signal (population vs. size vs. prosperity vs. time vs.
  player choice) and its calibration — and whether permanence should be reversible at
  all.
- Whether **permanence** should be the open-inflow flip (the proposal) or a richer
  mechanic, and how it interacts with the collapse-based smoke tests (which assume the
  closed village regime).
- Whether a CITY should gain **distinct urban content** (denser slots, urban-only
  goods/buildings) beyond permanence, or stay a permanent village for now.
- Ordering of federal taxation against a member city whose Mayor is itself being
  succeeded/demoted in the same step (against the end-of-step reform sweep).
