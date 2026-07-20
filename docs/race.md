# Design note: Race (per-person ancestry)

**Status:** Implemented (Phases 1–3). The `Race` type and its plumbing, the
machinery that makes ancestry *vary*, and the authored Harimari content (names,
calendar, tech overlay) plus a demo scenario and tests are all in tree. Remaining
items are the *Open questions / future* below. See *Suggested phasing*.
**Date:** 2026-06-18
**Depends on:** the per-session services owned by `settlement.com.civstudio.GameSession`
(the `NameRegistry` given-name tables + `DynastyPool`, the `LiturgicalCalendar`,
the lazy `TechTree`) and the per-colony services it mints (`mortality.com.civstudio.Demography`
+ its `mortality.com.civstudio.LifeTable`, and the colony's `NameRegistry`); the person model
(`name.com.civstudio.Person`, its `SkillTracker` and `Gender`); the household stack
(`agent.com.civstudio.Household` / `AbstractHousehold` and its `Laborer` / `Noble` / `Ruler`
implementors); the peasant pool (`agent.com.civstudio.Retinue`); and the tech effect overlay
(`tech.com.civstudio.TechEffects`, `tech-effects.json`).
**Related:** `docs/tech-tree.md` (per-race tech *effects*), `docs/calendar.md`
(per-race feasts), `docs/social-class.md` and `docs/rank-ladder.md` (orthogonal axes
— race is ancestry, not standing or command).

## Motivation

The colony is mono-cultural: every person is drawn from one set of human name
tables, dies on one `LifeTable` (`WEST_LEVEL_3`), keeps one liturgical calendar, and
researches one tech graph. To support a fantasy setting (the first non-human race is
the **Harimari**, the tiger-folk of *Anbennar*) we want ancestry to be a first-class
attribute that varies *who* a person is — their mortality, their names — and *how*
their colony works — its holidays and the technologies it favours.

The guiding constraint: **default `Race.HUMAN` must reproduce today's behaviour
byte-for-byte.** Race is additive; a pure-human colony draws no new randomness and
loads no new resources.

## The core decision: Race lives on `Person`, not `Settlement`

A `Race` is carried by each **`Person`**, so one colony can hold residents of several
races at once (a mixed settlement). This is clean for the attributes that are already
per-person, but it forces a resolution rule for the two mechanics that are inherently
**colony-wide** and have no per-person form:

| System | Owner today | Keyed by | Why |
| --- | --- | --- | --- |
| Mortality (`LifeTable`) | `Demography` (per colony) | **person** | each person ages and dies on its own race's schedule |
| Names (given + dynasty) | `NameRegistry` (per colony) | **person** | each person is drawn from its race's name tables |
| Skills / gender | `Demography` | **person** | already a per-person draw; race may later shift the means |
| Calendar (`LiturgicalCalendar`) | per session | **colony founding race** | `Firm.operatesOn(DayType)` rests *every* firm on a feast — there is one colony-wide rest calendar, not one per worker |
| Tech (effects overlay) | `TechTree` / `ResearchState` | **colony founding race** | a colony has a single `ResearchState` with one focus and colony-wide sector multipliers — there is no per-person research |
| Economy (`Era.Economy`) | `Settlement` (per colony) | **colony founding race** | prices, agent starting balances, tax rates and the peasant pool are colony-wide; a session may seat several races, so they cannot ride the run config |

So the colony gains a single **founding race** (its ruler's race) that selects the
calendar, the tech effect overlay and the economy, while individuals — including
immigrants and spouses of other races — vary freely.

### The economy is wired per race but not yet authored per race

A colony carries its own `Era.Economy`, resolved at founding from the race of the
province it stands in (`WorldMap.raceOf` → `Era.economy(race)`) and adjustable via
`SimulationHarness.tuneEconomy`. Every founding path goes through it, hosted seats
included — `TimelineTest.eachSeatFoundsOnItsOwnRacesEconomy` pins that.

**But `Era.economy(Race)` returns the human column for every race today.** Only
`(MEDIEVAL, HUMAN)` is authored, so a dwarven seat and a human seat in one Timeline
currently run identical numbers. This is a missing *content column*, not missing
plumbing: authoring a non-human economy is the entire remaining step, and it is
meant to arrive **as content rather than as constants** — see
[`docs/studio-control-plane-plan.md`](studio-control-plane-plan.md) workstream A.
Until then, per-race economic variation is latent, and any claim that races "run
different economics" is about capability, not observed behaviour.

## The `Race` enum

A small `race.com.civstudio.Race` enum, each value carrying the metadata the services need:

```java
public enum Race {
    //     id          life table            minInitAge  youngAdult[min,max]
    HUMAN   ("human",   LifeTable.WEST_LEVEL_3, 15,        16, 25),
    HARIMARI("harimari",LifeTable.WEST_LEVEL_3,  9,         9, 16); // own life table later

    private final String id;          // resource-file slug
    private final LifeTable lifeTable; // mortality schedule
    private final int minInitAgeYears;                 // founding working-age floor
    private final int youngAdultMinYears, youngAdultMaxYears; // immigrant recruit range
    // ...
}
```

Besides the mortality schedule, the enum carries the **age demographics** that vary
by race: the founding-age floor (`minInitAgeYears`) the founding-age draw is
truncated below, and the inclusive `[min, max]` age range of a young-adult immigrant
recruit. The faster-maturing Harimari start at 9 and recruit 9–16, versus the human
15 and 16–25. `Demography.sampleInitialAgeDays(meanYears, race)` and
`sampleYoungAdultAgeDays(race)` read these from the person's race; the age *spread*
(`INIT_AGE_STDDEV_YEARS`) stays shared. (Skill and gender means remain colony-level —
see *Open questions*.)

`id()` drives the resource-file convention `/{male,female,dynasty}-<id>.json`,
`/feasts-<id>.json`, `/tech-effects-<id>.json`. **Every per-race resource falls back
to the human/base file when its race-specific file is absent**, so a new race can
ship with only the files that actually differ (the Harimari, for now, reuse the human
given names — see *Groundwork*).

## Per-person plumbing

- **`Person`** gains a `Race race` field. Identity stays name-based (race is
  metadata alongside `skills` and `gender`); `Person.withSkills` is the model for how
  it is attached after a name-only draw.
- **`Demography`** holds a `Race → LifeTable` map instead of one table, and its
  old-age check takes the dying head's race. It gains `sampleRace(raceMix)` — rolled
  on the demographic RNG exactly like `sampleGender`, and **only when the colony's mix
  is non-degenerate** (a single-race colony draws nothing, preserving the human RNG
  stream and byte-identical output). Its **age draws are race-keyed** too —
  `sampleInitialAgeDays(meanYears, race)` and `sampleYoungAdultAgeDays(race)` read the
  founding floor and young-adult range from the person's `Race`. Skill/gender means
  stay colony-level for v1; a race may shift them later.
- **`NameRegistry` / `DynastyPool`** become race-keyed: the registry holds a
  `Race → NameTable` for given names and a `Race → DynastySlice` for surnames, and the
  draw methods (`nextHead`, `nextDynastyName`, `nextRarestDynastyName`, …) take the
  person's race. `GameSession` owns a `Race → DynastyPool` and deals each colony a
  disjoint slice **per race**, so the surname-uniqueness invariant becomes per-race.
  The pure-human path is unchanged.

## Per-colony plumbing

- **`Settlement`** gains a `Race foundingRace` (default `HUMAN`) used *only* to pick
  the calendar and tech overlay, plus a `Map<Race,Double> raceMix` (default
  `{HUMAN: 1.0}`) driving the per-person roll.
- **`GameSession`** caches `Race → LiturgicalCalendar` and exposes
  `getTechTree(Race)` (race → effect overlay). `newSettlement(…, foundingRace,
  raceMix)` defaults both to human, so existing scenarios are untouched.
- The founding ruler and any founding nobles take `foundingRace`; the peasant pool
  rolls each member's race against `raceMix`; **heirs and wedding spouses keep their
  own line's race** (succession and marriage do not re-roll ancestry).

## Race assignment

A colony carries a **race-mix weight map** (e.g. `{HUMAN: 0.8, HARIMARI: 0.2}`).
Every *generated* person — pool seeding, founding draws, immigration — rolls its race
against those weights on the demographic RNG, the same way gender is rolled today.
The default mix is `{HUMAN: 1.0}`, a degenerate distribution that **skips the roll
entirely**, which is what keeps human-only runs reproducible. Inherited and married-in
people are not rolled; their race comes from their dynasty / origin.

## Tech variation per race

Per the scoping decisions:

- **"This race skips tech X" means present-but-inert**, not removed from the graph.
  The shared `techs.json` graph is identical for all races (so prerequisite routing
  is unchanged); only the **effects overlay differs**: a race's
  `tech-effects-<id>.json` simply gives the skipped techs no (or weaker) effect. This
  needs no change to `Tech`, `TechTree`, or the graph queries — only
  `GameSession.getTechTree(race)` choosing the overlay.
- **Race-unique techs are deferred.** v1 only varies the *effects* of existing shared
  techs; brand-new race-only nodes (which would need a per-race graph merge) come
  later. Example: the Harimari give naval techs little benefit and lean their
  bonuses elsewhere, expressed purely through their overlay.

## Reproducibility

- The race roll consumes RNG, so it is **gated on a non-degenerate mix** — a
  single-race colony never draws it, and every current scenario is single-race human.
- The roll rides the **demographic** RNG stream (with gender/skill), never the
  economic one, consistent with how all birth-time draws are kept off the economic
  stream.

## Groundwork already laid

Two pieces are already in tree ahead of the plumbing:

- **Harimari name resources.** `src/main/resources/dynasty-harimari.json` holds 278
  surnames on the standard 0–99 rarity scale: 212 real South-Asian surnames carved out
  of the human dynasty table (rarity bands conserved), plus the **66 *Anbennar* clan
  epithets** ("of the White Stripe", "of the Jade Claw", …) as the **rarest tier** (the
  grand houses). `male-harimari.json` / `female-harimari.json` now carry **distinctive
  South-Asian/Sanskrit given names** (Phase 3 replaced the placeholder human copies).
  All three are loaded per race by `GameSession` (with human fallback). *(Note: the
  rarest tier by NameTable **weight** is not the clan-epithet tier — the loader's
  zero-width-band clamp lifts that 66-name tier's weight, so `nextRarestDynastyName`
  does not specifically return an epithet. The epithets are still drawn by the ordinary
  weighted `nextDynastyName`. Making nobles reliably draw clan epithets is a future
  tweak to the rarity model.)*
- **Nobles draw rare dynasties.** A noble founding a *new* dynasty now draws its
  surname from the rarest tier (`Noble.drawsRareDynasty()` →
  `NameRegistry.nextRarestDynastyName()`), so once the Harimari pool is loaded a
  Harimari noble carries a clan-name. (Caveat: this fires only for nobles that found a
  fresh dynasty — *ennobled* nobles keep the commoner surname they rose with, and
  successors continue their dynasty's surname. Giving ennoblement a rare-clan rename is
  a separate, optional change.)

## Suggested phasing

1. **Plumbing, all human.** *(implemented)* Added the `race.com.civstudio.Race` enum (`id()` +
   `lifeTable()`), `Person.race` (default `HUMAN`, threaded through `withSkills` and
   the household/peasant/wedding/caravan person-creators), the race-keyed maps in
   `Demography` (`Race → LifeTable`; `diesOfOldAge(ageDays, race)`; `sampleRace(raceMix)`
   gated on a non-degenerate mix so a human-only colony draws no RNG), `NameRegistry`
   (per-race given-name tables + per-race `DynastyDraw` surname state, race-param draw
   overloads defaulting to `HUMAN`, plus `registerRace`), and `GameSession` (per-race
   lazy name-table / `DynastyPool` / calendar caches with human fallback,
   `getTechTree(Race)` / `getLiturgicalCalendar(Race)`, and a `newSettlement(…,
   foundingRace, raceMix)` overload), and `Settlement.foundingRace` / `raceMix`
   (defaults `HUMAN` / `{HUMAN: 1.0}`). Everything defaults to `HUMAN`; the suite stays
   green and the human path consumes RNG in the same order (byte-identical). The
   per-line race of heirs/spouses still resolves to the colony's founding race — Phase 2
   threads a succeeding line's own ancestry through succession/marriage.
2. **Make it vary.** *(implemented)* The race-mix roll is wired into pool seeding
   (`Retinue.newPeasant`) and immigration (the equity-funded `Laborer`), reproducibly
   on the demographic RNG (iterated in fixed `Race.values()` order so a multi-race
   draw is deterministic regardless of map order); per-person names are drawn from the
   roller's race's tables and the old-age check reads its race's life table. **Heirs
   and wedding spouses keep their own line's race** — succession threads
   `predecessor.getHead().race()` through the `Laborer`/`Noble`/`Ruler` constructors
   (via a new `Race` parameter on the `AbstractHousehold` drawing constructor), and the
   wedding spouse keeps the candidate's race; only *generated* people (pool, immigrants)
   roll, and founders take the colony's founding race. The founding-race calendar/tech
   seam (`GameSession.getLiturgicalCalendar(Race)` / `getTechTree(Race)`) is in place but
   still resolves to the shared human resources until Phase 3 authors per-race files.
   Covered by `race.com.civstudio.RaceTest` (the roll's reproducibility & coverage, the degenerate
   mix drawing no RNG, per-race naming with the Harimari clan epithets, the calendar/tech
   seam). The full mixed-race economy smoke test ships with the Harimari content in
   Phase 3.
3. **Author the Harimari.** *(implemented)* The per-race resource loading is wired:
   `GameSession.getLiturgicalCalendar(Race)` loads `/feasts-<id>.json` when present
   (else the human calendar) and `getTechTree(Race)` loads the shared graph under
   `/tech-effects-<id>.json` when present (else the shared empty overlay); the harness
   wires a colony's research to `getTechTree(foundingRace)`. The Harimari content
   ships: distinctive South-Asian/Sanskrit given names (`male-harimari.json` /
   `female-harimari.json`), a thematic `feasts-harimari.json` (festivals of the hunt,
   the stripe, the ancestors — echoing the clan epithets), and a
   `tech-effects-harimari.json` that leans bonuses toward export/scholarship
   (TECH_HUMANISM, TECH_PRINTING_PRESS) and food (TECH_THEOLOGY) and gives the naval
   techs nothing (present-but-inert by omission). `HarimariEconomy` is the demo
   scenario — a Harimari-founded, ~70/30 mixed colony — and `MixedRaceColonyTest`
   asserts such a colony founds, names people of both races, ages them on their race's
   floor, keeps the Harimari calendar, and researches on the Harimari overlay. (The
   Harimari given-name pool is small, and the dynasty pool is 278; a Harimari-majority
   colony must be sized so promotions don't exhaust it — the demo/test use a 200-peasant
   pool. A larger Harimari colony would need a larger surname table.)

## Open questions / future

- **Per-race skill / gender means** (e.g. a race that is on average abler, or skews a
  skill) — deliberately out of v1 scope; the hook is `Demography`'s gendered means.
- **Race-unique techs** — needs a per-race graph merge (a base `techs.json` plus race
  additions), deferred above.
- **Ennoblement clan-names** — whether an ennobled commoner *adopts* a rare race
  dynasty on elevation, rather than keeping its commoner surname.
- **Cross-race interaction** — caravan trade and the wedding market already move people
  between colonies; with mixed races they will carry ancestry across, which the
  per-person model handles, but any race-specific social rules are future work.
