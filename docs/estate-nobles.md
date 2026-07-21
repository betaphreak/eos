# Design note: the Nobles (the aristocratic estate)

**Status:** Design (not built) — the **aristocratic** spoke of the estate system. The shared
machinery (estates/factions, centralization & the `−25` rail, influence/loyalty/coups, the task
mechanic, the transition graph) lives in the spine, [`docs/estate-system.md`](estate-system.md);
this doc holds only what is specific to the Nobles.
**Date:** 2026-07-21 (split out of `estate-system.md`)
**Depends on:** the spine; `agent/ruler/Ruler`, `agent/noble/Noble`, `simulation/SocialMobility`
(ennoblement/demotion); the imported `monarch`/`heir`/`queen` blocks
(`docs/country-rank-import.md` §8).
**Related:** [`estate-burghers.md`](estate-burghers.md) · [`estate-church.md`](estate-church.md) ·
[`estate-tribes.md`](estate-tribes.md); `docs/country-rank-import.md` §6 — the aristocratic title
column is the one register the engine's `Rank.java` already carries.

---

## 1. The estate

The **Crown's** register — `estate_nobles` in the import catalog (spine §3). **Greater nobles** form
the **Aristocratic estate** (L−1); **lesser nobles** are **factions** (L−2 loose blocs). Where the
Crown rules, the government is a **monarchy** (spine §2); where it does not, the Nobles are the
estate pushing to coup back into power.

## 2. Sub-scale — `legitimacy`

The **most stable and easiest to manage** register (the baseline — republics must manage tradition,
theocracies balance magic/science). Legitimacy is the **reigning person's** stat, not the polity's
(spine §5's real-`Person` leaders): a **succession resets it** toward a baseline set by the heir's
quality and claim, and an **interregnum is the event-state of having no ruler** — read as `0`, but
not a point a living reign *drifts through*. The imported `monarch`/`heir` blocks
(`adm`/`dip`/`mil`, `claim`) seed a start ruler's initial legitimacy.

| scale | form |
|---|---|
| **+100** | max **absolutism** — a fully centralized crown; raises the **territory cap** the nation can hold |
| **0** | a **fresh or contested reign** — where successions land (a ruler-less interregnum also reads `0`) |
| **−100** | **civil war** — the nation **breaks into fiefdoms** (fragmentation: the estates/fiefs secede) |

The scale wires straight into **rank**: `+100` absolutism lifts the territory cap (room to grow up
the ladder), and `−100` civil war *is* dynamic rank's **−1 fragment** (rank doc §5) — a kingdom
shattering into its component counties/baronies.

**Resolution edges** (spine §8.4): a fresh/contested reign (`0`) consolidates into a monarchy as
legitimacy is built, *or* breaks into **Civil War** on a disputed succession; a civil war
**fragments** the realm (rank −1) *or* a new dynasty restores the monarchy.

## 3. Power base & privileges

| | |
|---|---|
| influence base | **land share** (plots / holdings) |
| privilege currency | **land grants** |
| the crown's counter | **confiscation** |
| army privilege | the **levy right** — noble levies in parallel to the national army |

Land is the historical anchor of the whole control ledger (spine §4): **ennoblement already moves
land** between blocs — promoting a laborer shifts that person's plots from the commoner pool into the
aristocratic estate — so `SocialMobility` is *already* a centralization lever. Revoking the levy
right is the aristocratic **disarmament crisis**, and the noble levies are the classic combatants of
the succession war (spine §5, estate armies).

## 4. The centralization rail

A monarchy pushed **under `−25`** (too federated) becomes **disbandable** — dissolved into its
fiefs, the fragment / civil-war direction (spine §4, the shared rail).

## 5. Disaster & rulership

The Nobles' archetype disaster is the **feudal / succession civil war**; winning it installs the
**aristocratic** register (a monarchy). Content authoring — the register archetype + per-estate
overrides — is an open question (spine §9).

## 6. Council seats

Via the cascade (spine §5), the Nobles naturally feed the **Military** seat (the marshal — when they
hold the largest private army) and the **Growth** seat (the steward).

## 7. Engine mapping

See spine §6 for the full graft table — the Crown is the colony's `Ruler`, the Aristocratic estate
the `Noble`s raised by ennoblement, greater-vs-lesser the estate/faction split on the noble tier, and
crownland-vs-privileges the ruler-vs-`Noble` split of the colony's plots.
