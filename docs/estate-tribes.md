# Design note: the Tribes (the tribal estates)

**Status:** Design (not built) — the **tribal** spoke of the estate system. The shared machinery
lives in the spine, [`docs/estate-system.md`](estate-system.md); this doc holds only what is specific
to the tribal register: sub-tribes as estates, and the militarism sub-scale.
**Date:** 2026-07-21 (split out of `estate-system.md`)
**Depends on:** the spine; `docs/country-rank-import.md` §3.2 (`TRIBAL` doubles as the neutral
"no flavor" register fallback for unmapped governments).
**Related:** [`estate-nobles.md`](estate-nobles.md) · [`estate-burghers.md`](estate-burghers.md) ·
[`estate-church.md`](estate-church.md).

---

## 1. The estates — sub-tribes

A tribal society replaces the three "civilized" estates (Nobles/Burghers/Church) with its own:
`estate_nomadic_tribes` (horde/nomad) · `estate_monstrous_tribes` (monster races) ·
`estate_cossacks` (frontier-military) — and, in the militarized Command, the **clan estates**
(`estate_wolf_command` · `boar` · `lion` · `dragon` · `elephant` · `tiger_command`), an army-caste
society's internal estates.

**Tribal estates *are* sub-tribes** — formal L−1 blocs, not mere kin-factions. That makes the tribal
register's internal politics literally federal: its estates are constituent peoples, which is why
both the centralization rail (§4) and the militarism pole (§2) act on them so physically — they can
*secede* or be *assimilated* in a way a guild cannot.

## 2. Sub-scale — `militarism`

The war-band/horde register's axis is how the army relates to the state.

| scale | form |
|---|---|
| **+100** | fully **militarized** — the state is **run by the army** (a war-horde / stratocracy); the nominal ruler is a powerless figurehead. At this pole **all the vassal sub-tribes have been disbanded and fully assimilated** into one host — no tribal estates left, only the army |
| **0** | **no national army** — the tribe fields no standing host |
| **−100** | **militant pacifism** — nobody is allowed to fight |

So a horde at `+100` is the classic army-run war-machine (the Great Khan's host); at `−100` a
pacifist people. Note the two tribal-cohesion ends pull opposite ways on the sub-tribe estates:
**under `−25` centralization the sub-tribes break free**, while **at `+100` militarism they are
assimilated away** into a single host — decentralization scatters the tribes, total militarization
*absorbs* them. This fits the TRIBAL register's flavor (hordes, warbands, packs) — its sub-scale is
martial, where the monarchy's is dynastic and the republic's constitutional.

## 3. Power base & privileges

| | |
|---|---|
| influence base | **levy / manpower** (paramilitary hosts) |
| privilege currency | **host & levy rights** |
| the crown's counter | **disband hosts** |

Below `0` centralization the sub-tribes hold armies *emergently* — the centre is simply too weak to
revoke (spine §5, estate armies). The sub-tribes' hosts exist in parallel to the nation's own, and at
`+100` militarism the distinction disappears: there is only the one host.

## 4. The centralization rail

A tribal register pushed **under `−25`** sees its constituent **tribes break free** — secede into
independent tribes, the tribal analog of a monarchy disbanding into fiefs (spine §4, the shared
rail).

## 5. Disaster & rulership

The tribal archetype disaster is the **war-band mutiny**; winning it installs the **tribal** register
as a war-horde. The reverse edge exists too: `any → tribal` is the collapse/fragmentation reversion,
and a settling tribe consolidates out via `tribal → monarchy | republic | theocracy` (spine §8.4).

## 6. Degenerate corner

**Fully-centralized tribal** (`+100` centralization) borders on monarchy — a unified war-horde under
one khan is a king in all but name, and tends to reform aristocratic (spine §8.3).

## 7. The tribes vs the Medieval Era — the civilizing crisis

The tribes estate is **era-bound: it stands against the Medieval package** — the first
fully-specified instance of the universal era-binding law (every estate has an unlock tech and an
opposition horizon; spine §3). It opposes **Vassalage**
and **Feudalism** (the C2C techs), and **does not allow scientific buildings in its holdings** — a
**per-holding** block wherever the estate holds land, and unique to the tribes: no other estate or
faction blocks building. (So the tribes' land *placement* is strategic, and land moved out of tribal
hands becomes buildable.)

**Entering the Medieval era fires their last stand.** As soon as the settlement completes
**`TECH_MEDIEVAL_LIFESTYLE`**, the tribes' disaster fires — the war-band mutiny with a **regressive
aim**: **undo all Medieval techs** and **destroy every building on the line branching from
Vassalage/Feudalism**. The Medieval era therefore *opens with a crisis*: suppress the mutiny and push
on, or lose the era to it. And losing is real: if the mutiny wins, the **Medieval techs are gone**
(re-researched from scratch, not dormant) and the Vassalage/Feudalism-line **buildings are razed —
as terms of the peace deal** that ends the internal war.

**Completing Feudalism flips them.** The removal path is **assimilation, not extermination**: when
the settlement finishes researching **Feudalism**, the tribes estate **flips** — out of the tribal
estate and into **noble factions that have pledged fealty to the ruler**. Feudalization turns chiefs
into lords — the historical origin of a noble class, produced by the tech tree. As factions they then
live under the normal coalescence rule (a majority sharing a goal re-forms them into a Nobles estate,
[`estate-nobles.md`](estate-nobles.md)).

**A tribal-register *nation* that researches Feudalism flips into a despotism.** The khan settles as
a strongman: the nation lands in the plutocratic register's **despotism band**
([`estate-burghers.md`](estate-burghers.md) §2), from which the **despot-exit ladder**
([`estate-burghers.md`](estate-burghers.md) §3) carries it onward — crowning himself king above `25`
centralization, and beyond. So the §8.4 `tribal → monarchy` settling arc resolves into two steps
*through the despot*: `tribal → despotism → (ladder) → monarchy/theocracy`. This is the **tech
flip** — the transition graph's sixth edge (spine §8.4).
