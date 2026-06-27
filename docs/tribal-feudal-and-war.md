# Design note: tribal/feudal tracks and war-driven (CB) rank change

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-27
**Provenance:** distilled from the earlier `docs/Tribal MIL Workflow.svg` (a Lucidchart
sketch of this same progression problem). Two of its ideas are not yet in the rank
notes and are worth keeping; the rest of the sketch (its rung *ordering* and a
multiplayer meta-layer) is **superseded** by the current docs, which take precedence
wherever they conflict.
**Depends on:** the `com.civstudio.agent.Rank` enum and its already-present
`CasusBelli` / `Relation` / `TitleMode` / `TitleSet` vocabulary; the rank ladder
(`RankLadder`, `Estate`, `RankFactory`, `transferPropertyTo` — `docs/rank-ladder.md`);
the `Property` annexation primitive and member-city model (`docs/city-and-league.md`);
the tech tree's `SOCIAL_GATE` effect (`docs/tech-tree.md`); and the orthogonal
**Race** / **SocialClass** overlays (`docs/race.md`, `docs/social-class.md`).

## Motivation — what the SVG had that the docs don't

The SVG was an earlier attempt at the rank/progression ladder. Most of it is now
covered better by `rank-ladder.md` and `city-and-league.md` (and, where it disagrees,
*they* win — see *Deferred to the existing docs*, below). But two ideas in it are
genuinely new and structurally compatible, so this note captures them:

1. **A tribal-vs-feudal split** drawn as two parallel chains of rungs, switched by
   *adopting feudalism* — distinct adventurer/tribal content alongside the settled
   feudal content at the same level of organization.
2. **Rank *loss* modelled as the outcome of war under a casus belli** — every
   downgrade in the sketch is a "CB" decision (Disband Retinue CB, Skirmish CB,
   Pillage CB, Conquest CB, Raze County CB, Disband League) resolved by war, with
   outcomes "downgrade", "reparations", "cede contested territory", and "vassalized as
   a new estate for the winner".

The second idea is already **half-built**: `Rank` carries a `CasusBelli` per
`Relation` (`EQUAL` / `LOWER` / `HIGHER`), today inert ("nothing consumes them yet").
This note is mostly about wiring that dormant vocabulary to the existing
promote/demote engine, plus introducing the tribal/feudal overlay.

## Idea 1 — tribal vs feudal: a `Mode` overlay, not a second ladder

The SVG drew two parallel rung-chains:

- a **tribal / adventurer** track — `Adventurers → Adventurer Camp → Adventurer
  Village → Adventurer Factions → Adventurer Estate`, with features *Tribal Migration*
  ("a camp that moves every day"), *Tribal Capital* ("an unaligned tribal village that
  does not understand feudalism"), and *Tribal Factions*;
- a **feudal** track — *Government* ("a proper, feudal one-province minor nation"),
  *Feudal Capital*, *Federation* ("a league of adventurers and other non-feudal
  entities"), *Feudal Nation* / *Feudal Holding* / *Feudal Factions*;

switched by *adopt feudalism* (an `Era: Feudalism` node), with toggles like "disable
federations, use feudal alliances instead" and "replaces tribal factions with feudal
factions".

**The consistent realization is not a second ladder but a per-realm `Mode` overlay on
the single `Rank` ladder** — the same orthogonal-axis pattern as `Race` and
`SocialClass`. A realm is `TRIBAL` or `FEUDAL`; the `Mode` selects *which flavor of a
rung's content* surfaces (feature set, building/role names — "Adventurer Camp" vs.
"Government", "Tribal Factions" vs. "Feudal Factions") while `Rank.level()` and
`promote`/`demote` stay **identical**. Adopting feudalism is a `Mode` flip, gated by
the tech tree's `SOCIAL_GATE` (already the mechanism `tech-tree.md` uses to gate the
`HOLDING → VILLAGE → CITY` ascent). This keeps the rank docs' core rule — *one ladder,
rank = command scope* — intact, and adds tribal/feudal as a styling/content axis the
way Race already overlays names, mortality and the calendar.

`Mode` is **orthogonal to `TitleMode`** (the administrative / military / diplomatic
*register* each rung already carries). They interact — a tribal realm leans on the
military register (Captain/Commander, the rebel-flavored titles) — but they are
different axes: `TitleMode` is *which voice a title speaks in*, `Mode` is *whether the
realm understands feudalism at all*.

Mapping the SVG's rungs onto the canonical ladder (`Rank.java`):

| SVG rung | Canonical `Rank` | Mode |
| --- | --- | --- |
| Retinue / Adventurers | `CARAVAN` (the reserved warband/following rung; today the `Retinue` pool + `BuilderFirm` peasant labor) | the tribal track is what *realizes* `CARAVAN` as a playable rung |
| Adventurer Camp / Village | `VILLAGE` | TRIBAL |
| Government / Feudal Capital | `HOLDING` / `VILLAGE` | FEUDAL |
| Federation (of adventurers) vs. feudal alliances | `LEAGUE` | the same rung in two modes |

## Idea 2 — war-driven rank change: wiring the dormant `CasusBelli`

Every downgrade in the SVG is a war resolved under a casus belli, with outcomes
"yes, downgrade", "no, reparations", "no, cede contested territory", and — the
load-bearing one — "yes, **gets vassalized as a new estate for the winner**". This is
exactly the demotion-trigger seam `rank-ladder.md` left open: it wired only the
*insolvency* trigger and explicitly deferred "attainder, losing one's last holding,
**conquest**".

The mechanism is already present and inert: `Rank.casusBelli(Relation)` returns a
`CasusBelli` per `Relation`. **A resolved war is just a new trigger for the existing
`RankLadder.promote`/`demote`** — the way insolvency is — with the `Relation`
selecting the outcome shape:

- **`vsLower`** (CARAVAN *Press Gang*, HOLDING *Eviction*, VILLAGE *Communal
  Annexation*, CITY *Urban Expansion*) — the winner subjugates the loser. The loser is
  **demoted** a rung, or **absorbed as the winner's holding** — "vassalized as a new
  estate for the winner" is precisely the `Property` acquisition (`transferPropertyTo`)
  that `city-and-league.md` uses for annexation: the conquered settlement becomes a
  member `Property` of the victor.
- **`vsHigher`** (the rebellion pretexts: HOUSEHOLD *Desertion*, HOLDING *Rural
  Secession*, VILLAGE *Peasant Revolt*, CITY *League Defection*, LEAGUE *Burghers'
  Rebellion*) — a successful revolt **demotes the higher authority** (it loses the
  rebelling member) and frees the rebel. The SVG's "Disband League" / "Disband Retinue
  CB" diamonds are this: the subordinate breaks away, and the superior drops a rung
  once it loses its last member — the **standard last-holding demotion check** from
  `city-and-league.md`.
- **`vsEqual`** (CARAVAN *Breach of Contract*, HOLDING *Land Dispute*, CITY
  *Commercial Monopoly*) — a peer war over assets/territory. The loser **cedes
  `Property` or pays reparations** ("no, reparations") with no rank change, unless it
  loses its seat and demotes.

Every outcome reuses machinery the ladder already has — `Estate` carried across a
reform, holdings moved via `transferPropertyTo`, the agent swap deferred to end of
step — so war adds **no new transition machinery**; it adds a *consumer* for the
`CasusBelli` vocabulary and a *trigger* for promote/demote.

## Architecture mapping

- **`Mode` enum** (`TRIBAL`, `FEUDAL`) on the realm, orthogonal to
  `Race` / `SocialClass` / `TitleMode`. A content selector only: `Rank.level()`,
  `promoted()`/`demoted()`, and the ladder engine are untouched. A colony's starting
  `Mode` follows its founding Race/Era (defer the rule); adopting feudalism is a
  one-way, tech-gated flip for now.
- **`CasusBelli` stays exactly where it is** (`Rank.java`) and finally gains a
  consumer: a future war/diplomacy resolver looks up
  `aggressor.rank().casusBelli(relation)` on a decided war and applies a rank-ladder
  outcome to the loser. `Rank` / `CasusBelli` / `Relation` need **no change** — they
  were built for this.
- **Outcomes are the existing primitives**: `demote` (Estate reform down a rung),
  *vassalize* (`transferPropertyTo` — the loser settlement becomes the winner's member
  `Property`, the city-and-league annexation move), *reparations* (a one-off money
  transfer, no rank change).
- **`RankLadder` on `GameSession`** (already the decided home) is a hard requirement
  here: a conquest spans two colonies, the same cross-colony reason league formation
  needs it.
- **Selection stays with the caller** (the war resolver decides who fights and who
  loses), mirroring how ennoblement and insolvency selection stay out of the ladder.

## Deferred to the existing docs (where the SVG conflicts, they win)

- The SVG's top band ordered **Village before Holding**; the canonical ladder is
  `HOLDING` (level 2) → `VILLAGE` (level 3). Canonical wins.
- The SVG folded in a **multiplayer meta-layer** (Spectator / Player / Co-Op / PvP,
  matchmaking, "like a guild in WoW"). That is a session/UI concern, **out of scope**
  for the rank model and not captured here.
- The SVG used game-stage naming (Skirmished / Pillaged / Razed / Deposed); the model
  keeps the command-scope `Rank` taxonomy and its typed `CasusBelli`/`TitleSet` data.

## Accepted limitations (out of scope for this cut)

1. **No war/diplomacy engine exists.** This note specifies only *how a war's outcome
   attaches to the rank ladder*; the `CasusBelli` vocabulary stays inert until that
   engine lands. Nothing here changes a running simulation.
2. **`Mode` is a content overlay only.** Genuine tribal-vs-feudal *economic*
   differences (a migrating camp vs. a fixed capital, tribal vs. feudal factions) are
   future work — the same way a `CITY`'s "permanence" is, for now, just the open-inflow
   flip rather than a richer urban model.
3. **Mode-switch is one-way and tech-gated.** Reversibility (a feudal realm collapsing
   back to tribal) is deferred, like the other demotion triggers.
4. **Vassalization vs. demotion of a conquered sovereign is unmodelled at the edges** —
   whether a conquered settlement keeps its own Mayor/Ruler as a subordinate member or
   is reformed down a rung overlaps the city-and-league member-city question.

## Open questions deferred to later

- Is `Mode` a property of the realm, of its founding `Race`, or of an `Era`/tech state
  — and may it differ per colony within one `GameSession`?
- Does a successful `vsHigher` rebellion **promote** the rebel (independence at its
  current rung) or only **demote** the overlord (which the SVG's "disband" framing
  suggests)? Likely the overlord drops and the rebel keeps its rung, now free.
- How vassalization-as-`Property` composes with a conquered settlement that still runs
  its own sovereign — a member-with-a-Mayor vs. a demoted subordinate.
- Whether "reparations" needs any modelling now beyond a one-off money transfer.
