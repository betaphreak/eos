# Design note: the Church (the theocratic estate & the faith layer)

**Status:** Design (not built) — the **theocratic** spoke of the estate system, and the largest: it
also holds the **faith layer** (religions as runtime polities, cults, heresies, decadence). The
shared machinery lives in the spine, [`docs/estate-system.md`](estate-system.md).
**Date:** 2026-07-21 (split out of `estate-system.md`)
**Depends on:** the spine; the imported per-province `religion` (`provinces.json` — the one part
importable today); the **dynamic religion catalog** (a not-yet-built prerequisite, spine §6);
`docs/tech-tree.md` + the research-point-types plan (the magic↔science scale steers research).
**Related:** [`estate-nobles.md`](estate-nobles.md) · [`estate-burghers.md`](estate-burghers.md) ·
[`estate-tribes.md`](estate-tribes.md); [`estate-burghers.md`](estate-burghers.md) §3 (the
god-emperor cult's origin — the despot-exit ladder).

---

## 1. The estate — the religion rules

The **state religion's church is an estate by default**. Every **other** religion present in the
nation is a **faction**; a minority faith holding **>1 province** is promoted to a **Religious
estate** — for minority faiths the **faction→estate threshold is territorial**.

**The heterodox rule.** A state religion holding **none** of the nation's provinces yields **no
church estate** — only religious factions. One of those factions will eventually **create a new
church estate and convert the nation** to its faith: the church seat is won, not defaulted.

This layer is the one part **importable today**: `provinces.json` carries `religion` per province, so
counting a nation's provinces per religion yields its Religious estates vs mere faiths. The **faith
entities** (§5) are computable the same way — provinces-per-religion + border adjacency yield each
transnational faith-polity, its rank, and its heresy-pressure (deficit) map: the whole religious
geography of 1444, before any runtime exists.

**Theocracies carry extra administrative/commercial estates.** Beyond its ruling Religious estate
(and the theocracy-internal **Mages** / **Artificers**, §2), a theocracy may also hold a
**bureaucratic estate** (the administrative apparatus — candidate `estate_eunuchs_anb`) and/or a
**patrician estate** (a plutocratic merchant elite — `estate_castonath_patricians`). So a theocracy's
roster is not just "the Church": it can run a temple-state bureaucracy alongside a mercantile
patriciate, each an estate with its own influence/loyalty. These two have a **generative origin** as
well as an authored one: when a mortal **god-emperor** dies ([`estate-burghers.md`](estate-burghers.md)
§3, the despot-exit ladder), his old plutocratic machine survives him by institutionalizing as
exactly these — the cult's **bureaucrats** or its **patricians**.

## 2. Sub-scale — `magic↔science`

| scale | ruling estate | form |
|---|---|---|
| **−100** | the **Mages** | **mageocracy** — the common tech trunk **freezes**; magic research advances |
| **0** | the **Religious** faction | a **regular theocracy** — the common trunk advances normally |
| **+100** | the **Artificers** | **technocracy** — research diverts into the **Clockpunk Era** |

The scale is a research-**direction** dial, not a throughput one — both poles are **divergent
specializations** off the common trunk, with **no regression** at either:

- A **magocracy** (the Magisterium) has no use for *societal* research — a century-old magocracy has
  real **advances in magic** while its society sits frozen at, say, `TECH_MEDIEVAL_LIFESTYLE`. The
  mage estate **redirects beakers** away from the national research focus into its own magic track —
  at 100% influence, 100% of the beakers (the split at intermediate values is an open question,
  spine §9).
- A **technocracy** diverts research the same way into the artificers' own special age and tree, the
  **Clockpunk Era**.
- Because **buildings and units ride the common tree** (the C2C catalogs are unlocked by common
  techs), the divergence is *visible*: a magocracy **looks medieval** — its cities, walls and levies
  stuck at the freeze point, its real power arcane and invisible — while a technocracy's power is all
  visible machinery.

*(Tech-tree status: today's tree is the **common part** only; splitting it by race — plus special
eras for dwarves and gnomes — is planned, and the magic / Clockpunk sub-trees are future content. See
`docs/tech-tree.md` and the research-point-types plan.)*

The freeze is not bespoke: it is the **era-binding law** (spine §3) applied to a *ruling* estate — no
nation advances past its ruling estate's opposition horizon, so every era transition requires a power
transition.

**Mages and Artificers are theocracy-internal** — you *cannot* coup a monarchy straight into a
mageocracy. The Religious estate must first make it a theocracy; *then* the scale slides toward a
pole. Monarchy → mageocracy is a **two-step path** (spine §5).

## 3. Power base & privileges

| | |
|---|---|
| influence base | **adherent/province share + holy-order strength** |
| privilege currency | **tithes, temple rights, order charters** |
| the crown's counter | **seize temples, dissolve orders** |
| army privilege | the **holy order** — the church's host in parallel to the national army |

So the church's two levers are **conversion and militarization**; dissolving its orders is the
theocratic **disarmament crisis** (the Templars) (spine §5, estate armies).

## 4. The centralization rail

A theocracy pushed **under `−25`** can be **subverted by its own internal estates**: the **mages**
capturing the scale toward `−100` (mageocracy) or the **artificers** toward `+100` (technocracy) —
the weak centre can no longer hold the *regular* theocracy at `0`, so a pole faction seizes the scale
(spine §4, the shared rail).

## 5. The faith layer — religions are runtime objects

The religion catalog is **not static**: the political layer creates religions at runtime, which makes
a **dynamic religion catalog** (creation, conversion spread, estate spawning) a real system
requirement (spine §6). Two doors in:

- **God-emperor cults** — a despot above `75` centralization founds a **cult of personality as a new
  religion** ([`estate-burghers.md`](estate-burghers.md) §3). The cult spawns its own Religious
  estate and displaces the incumbent church — which turns hostile/reactionary as it loses its place.
- **Heresies** — every faith can carry heresies. A heretic bloc's goal is **not to leave the faith
  but to capture it**: establish itself as the **head of the larger faith** and legitimize the heresy
  into official doctrine — the secede-or-usurp law at faith scale (spine §4). A **failed capture
  schisms**: the heresy secedes into a **new religion** — the second door into the dynamic catalog,
  completing the secede-or-usurp pair.

**A faith spanning nations is itself a polity on the rank recursion — at L+1, permanently
decentralized.** Two kingdom-level nations with the same state religion and a land border make that
religion work like an **L+1 transnational theocracy**; the **Regent Court**, in 400+ provinces, is a
**federation-level theocracy**. But a faith's **centralization is capped at `−75`** — it can never
consolidate like a state (the Regent Court sits around `−90`) — so by the `needed(rank)` deficit rule
(spine §4) a large faith runs a **permanent, size-scaled centralization deficit**, and that deficit
**is what breeds heresies**: the Corinite heresy is the Regent Court's `−90` expressed as ambient
internal conflict. Heresy genesis needs no separate trigger — it is the deficit mechanic applied to
the faith-polity. And at `−75…−90` a faith sits below the estate band: a loose confederation whose
**national churches are its factions**, heresies the insurgent factions among them.

Because the faith is a real polity, its anatomy is ordinary: **its capital is a holy site, and the
head of the faith is its ruler** — a real `Person` on a real seat. Capturing the faith therefore uses
ordinary polity mechanics: the heretics' win is seating their leader on it, and the holy city is the
prize on the map. The head of the faith is also a **playable seat** — the fourth way to inhabit the
system (spine §3): sovereign, estate leader, faction leader, **pontiff** — running a great faith at
`−90` against its own heresies.

**Wealth feeds decadence.** The richer a faith's churches grow (privileges, tithes, holy-order
charters), the higher its decadence — theocratic power is **self-limiting** (indulgence wealth →
Reformation). Decadence **weakens the faith's estates**, **costs conversions at the borders**, and
**gates a "reform the faith" reset** that purges it at a price; together with the deficit above, it
feeds the **reform desire** that heresies ride.

## 6. Disaster & rulership

The church's archetype disaster is the **theocratic uprising**; winning it installs the **theocratic**
register — plus its magic↔science pole where a Mage/Artificer estate leads it (via the two-step path,
§2).

## 7. Council seats

Via the cascade (spine §5), the church feeds the **Religion** seat — and in a heterodox nation (§1)
that seat is contested among religious *faction* leaders, making the appointment **the choice of the
next state religion**. The Mages/Artificers feed the **Science** seat — the chair that steers the
national research focus (§2).
