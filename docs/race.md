# Design note: Race (per-person ancestry)

**Status:** proposed (design only — the `Race` type and its plumbing are not yet
implemented; some groundwork is already in tree, see *Groundwork already laid*).
**Date:** 2026-06-18
**Depends on:** the per-session services owned by `eos.settlement.GameSession`
(the `NameRegistry` given-name tables + `DynastyPool`, the `LiturgicalCalendar`,
the lazy `TechTree`) and the per-colony services it mints (`eos.mortality.Demography`
+ its `eos.mortality.LifeTable`, and the colony's `NameRegistry`); the person model
(`eos.name.Person`, its `SkillTracker` and `Gender`); the household stack
(`eos.agent.Household` / `AbstractHousehold` and its `Laborer` / `Noble` / `Ruler`
implementors); the peasant pool (`eos.agent.Retinue`); and the tech effect overlay
(`eos.tech.TechEffects`, `tech-effects.json`).
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

So the colony gains a single **founding race** (its ruler's race) that selects the
calendar and the tech effect overlay, while individuals — including immigrants and
spouses of other races — vary freely.

## The `Race` enum

A small `eos.race.Race` enum, each value carrying the metadata the services need:

```java
public enum Race {
    HUMAN("human", LifeTable.WEST_LEVEL_3),
    HARIMARI("harimari", LifeTable.WEST_LEVEL_3); // own life table later

    private final String id;          // resource-file slug
    private final LifeTable lifeTable; // mortality schedule
    // ...
}
```

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
  stream and byte-identical output). Skill/gender means stay colony-level for v1; a
  race may shift them later.
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
  epithets** ("of the White Stripe", "of the Jade Claw", …) as the single **rarest
  tier** (the grand houses). `male-harimari.json` / `female-harimari.json` are
  placeholder copies of the human given names until distinctive ones are authored.
  Nothing loads these yet — the loader is still hard-wired to the human files pending
  this note's plumbing.
- **Nobles draw rare dynasties.** A noble founding a *new* dynasty now draws its
  surname from the rarest tier (`Noble.drawsRareDynasty()` →
  `NameRegistry.nextRarestDynastyName()`), so once the Harimari pool is loaded a
  Harimari noble carries a clan-name. (Caveat: this fires only for nobles that found a
  fresh dynasty — *ennobled* nobles keep the commoner surname they rose with, and
  successors continue their dynasty's surname. Giving ennoblement a rare-clan rename is
  a separate, optional change.)

## Suggested phasing

1. **Plumbing, all human.** Add the `Race` enum, `Person.race`, the race-keyed maps
   in `Demography` / `NameRegistry` / `GameSession`, and `Settlement.foundingRace` /
   `raceMix`; everything defaults to `HUMAN`. Zero behaviour change; the suite stays
   green and output byte-identical.
2. **Make it vary.** Wire the race-mix roll into pool seeding and founding; per-person
   life table and names actually differ; founding race selects the calendar and tech
   overlay.
3. **Author the Harimari.** Their `feasts-harimari.json`, distinctive given names, and
   `tech-effects-harimari.json` (naval techs inert), plus a demo scenario and a test
   asserting a mixed-race colony founds, names, ages, and researches correctly.

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
