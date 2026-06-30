# Design note: BARONY vs LEAGUE — reconciling the two multi-settlement designs

**Status:** decided (design only — resolves a conflict between two existing notes)
**Date:** 2026-06-30
**Resolves:** the contradiction between `docs/rank-ladder.md` (the "Realizing BARONY"
section) and `docs/city-and-league.md` over what a province holding several
settlements becomes, and in what order the rungs above `VILLAGE` are realized.
**Depends on:** the rank ladder (`com.civstudio.agent.Rank`, `RankLadder`, `Estate`,
`RankFactory` — `docs/rank-ladder.md`), the `Property` abstraction
(`docs/village-founding.md`), and `GameSession`'s multi-colony support
(`TwinSettlementEconomy`).
**Supersedes:** the "Realizing BARONY" section of `docs/rank-ladder.md` (rewritten to
point here).

## The conflict

Two notes independently designed "what happens above a `VILLAGE`", and they reached
**different rungs for the same scenario**.

- **`docs/rank-ladder.md` → BARONY.** Its "Realizing BARONY" section maps a province
  holding several settlements (`TwinSettlementEconomy` — Upper and Lower sharing
  Dhenijansar's one `ProvincePlotPool`) straight onto **`BARONY`** (level 6), reached
  by promoting the senior `Ruler` from `VILLAGE` (3) directly to `BARONY`, the
  `RankLadder`'s skip-unrealized-rungs mechanism leaping past `CITY` (4) and
  `LEAGUE` (5). The justification: the province is "one territory governed as a unit"
  (shared plot pool, no independence), so it is *one consolidated fief*, not a
  federation.

- **`docs/city-and-league.md` → LEAGUE.** The dedicated city/league note maps the
  *same* two-adjacent-settlements scenario onto **two `CITY`s that federate into a
  `LEAGUE`** (level 5): each settlement urbanizes `VILLAGE → CITY` and the senior is
  promoted to a `Legate` over the bloc. The historical Hansa was, literally, a league
  of cities.

Both target `TwinSettlementEconomy`, both claim it; they disagree on the **rung** and,
worse, on the **ordering** — `rank-ladder.md` skips `CITY`/`LEAGUE` entirely to reach
`BARONY`, while `city-and-league.md` realizes `CITY` then `LEAGUE` as the natural next
two rungs.

## The decision

**`CITY → LEAGUE → BARONY`, in that order, with `TwinSettlementEconomy` realizing the
`LEAGUE` path.** The `rank-ladder.md` BARONY-first proposal was wrong on two counts —
the ordering and the scenario mapping — and is superseded by this note.

Three things are settled:

1. **The realized-rung order follows the enum's alternation — no skip past realized
   rungs.** `Rank` alternates singular (even) and plural (odd): going up two rungs is
   "gather peers into a collective, then consolidate that collective into one larger
   entity" (the `Rank` class note). So above `VILLAGE` (3, plural) the rungs realize in
   declaration order:

   ```
   VILLAGE(3, plural)  →  CITY(4, singular)  →  LEAGUE(5, plural)  →  BARONY(6, singular)
    Ruler                  Mayor                 Legate                Baron
    network of holdings    one urban center      bloc of cities        one consolidated fief
   ```

   The `RankLadder`'s skip mechanism stays — it is still correct for *genuinely*
   unrealized rungs (e.g. the `CARAVAN` rung between `HOUSEHOLD` and `HOLDING` that
   ennoblement already skips). It is **not** a licence to leapfrog rungs that *will* be
   realized. Once `CITY` and `LEAGUE` exist there is nothing to skip between `VILLAGE`
   and `BARONY`, so the `VILLAGE → BARONY` jump simply never arises.

2. **A shared-province multi-settlement is a `LEAGUE`, not a `BARONY`.** Sharing one
   `ProvincePlotPool` is a **geographic** fact — the settlements occupy one province's
   land — and is **politically neutral**. Independent cities can sit in one province
   (a `LEAGUE`) exactly as an absorbed fief can (a `BARONY`); the shared pool does not
   force consolidation. `rank-ladder.md`'s inference ("shared plot pool ⇒ one
   consolidated fief") conflated geography with governance. `TwinSettlementEconomy`'s
   two colonies run **independent economies** (each its own ruler, peasant pool, firms,
   banks) — that is the textbook **federation of sovereign cities**, i.e. a `LEAGUE`.

3. **`LEAGUE` and `BARONY` are different political relationships and never compete for
   the same scenario.** They differ on **both** of the axes that could separate them,
   and the two axes move together:

   | | members | formation | governance | the model |
   |---|---|---|---|---|
   | **LEAGUE** (5, plural) | stay **sovereign** cities — each keeps its Mayor, gold bank, economy | **bottom-up** — cities *choose* to federate | the **Legate taxes** members but does not govern their internals | a voluntary bloc (the Hansa) |
   | **BARONY** (6, singular) | **absorbed** as sub-holdings under one lord | **top-down** — a fief *consolidated*, granted, or won | the **Baron governs** the settlements directly | one consolidated fief |

   A `LEAGUE` is bottom-up *and* leaves members autonomous; a `BARONY` is top-down
   *and* absorbs them. So the question is never "is this scenario a LEAGUE or a
   BARONY?" — the political relationship decides, and `TwinSettlement`'s independent
   colonies are a `LEAGUE`.

## How `BARONY` is reached: `LEAGUE → BARONY` consolidation

A `BARONY` is **not** promoted into from a `VILLAGE`. It is reached by **consolidating
a `LEAGUE`** — the single-rung step the enum's alternation already describes: the
plural `LEAGUE` (5, the collective of cities) consolidates into the singular `BARONY`
(6, one fief). Concretely, a federation stops being a voluntary bloc of sovereign
cities and becomes one lord's fief:

- The `Legate` reforms one rung up into a **`Baron`** (the standard single-rung
  `RankLadder.promote`, same gold bank, balances carried 1:1 — the same shape as
  `Ruler → Mayor` and `Mayor → Legate`).
- The member cities **lose their autonomy**: each member's `Mayor` is demoted, and the
  settlement becomes a sub-holding the `Baron` governs directly (the `Property` the
  Legate merely *held and taxed* becomes one the Baron *governs*). This is the "both
  axes flip together" of the decision above — formation has become top-down and the
  members have lost sovereignty.

So `BARONY` rides on the **same `Property` machinery** `city-and-league.md` introduces
for `LEAGUE` (member settlements as held `Property`s); the difference is governance
(the Baron governs the members directly, where the Legate only taxed sovereign
Mayors), realized by demoting the members' Mayors as part of the consolidation.

This keeps the ladder's "no multi-rung `promote`/`demote`" invariant
(`docs/rank-ladder.md`, *Decided since*): every step is a single rung with real content
on it, and `BARONY` is reached one rung at a time, after `LEAGUE` exists.

## Consequences for the implementation path

- **`BARONY` is deferred behind `LEAGUE`.** Because a `BARONY` is consolidated *from* a
  `LEAGUE`, there is nothing to build for `BARONY` until `LEAGUE` is realized. The
  near-term path is exactly `city-and-league.md`'s phased plan — `CITY` (the `Mayor`
  type + `VILLAGE → CITY` reform + permanence), then `LEAGUE` (the `Legate` type +
  federation) — with `BARONY` a later increment that slots on top.
- **`CITY` is the correct and lightest first step.** It is the immediate next realized
  rung, and the lightest reform on the ladder: `Mayor extends Ruler`, same gold bank,
  balances 1:1, all within one colony. It does **not** require the
  `RankLadder → GameSession` move (that is forced by `LEAGUE`, whose `Legate` spans
  colonies — `city-and-league.md` Phase 1).
- **Type hierarchy.** `Mayor extends Ruler` (CITY) and `Legate extends Mayor` (LEAGUE)
  are settled by `city-and-league.md`. A future **`Baron`** most naturally
  `extends Legate` (it *is* a consolidated Legate), adding the "govern the members
  directly" layer on top of the Legate's "tax sovereign members" — but the `Baron`
  type is deferred, so this is a proposal, not a commitment.
- **`TwinSettlementEconomy` worked example (corrected).** Upper and Lower each urbanize
  `VILLAGE → CITY`; the senior federates the two into a `LEAGUE` (the
  `city-and-league.md` Hanseatic example). Only *later*, if consolidation is modelled,
  would that `LEAGUE` of Dhenijansar consolidate into a **`BARONY` of Dhenijansar** —
  the senior `Legate` reforming into a `Baron` and the other city's `Mayor` demoted to
  a governed sub-holding. The earlier "senior `Ruler` → Baron of Dhenijansar, skipping
  CITY/LEAGUE" reading is withdrawn.

## What does **not** change

- The `Rank` enum (all 16 rungs, the adjacency walk, `isPlural`, titles, casus belli)
  is untouched — `BARONY` (6) keeps its place; this note only fixes *when* and *how* it
  is realized.
- The `RankLadder` skip-unrealized-rungs mechanism stays as-is; it is correct, and the
  decision only forbids *relying* on it to leapfrog rungs that are slated to be
  realized.
- Phases 1–4 of `rank-ladder.md` (the `HOUSEHOLD`/`HOLDING` rungs, ennoblement,
  demotion, the insolvency trigger) are unaffected.

## Open questions deferred to later

- Whether a `LEAGUE → BARONY` consolidation is **player/scenario-driven** (like
  `CITY → LEAGUE` federation) or fires on an **organic trigger** (a dominance/legitimacy
  threshold), and whether it is reversible (`BARONY → LEAGUE` demotion when the fief
  fragments back into autonomous cities).
- Whether demoting the member cities' `Mayor`s on consolidation is a true rank-ladder
  `demote` (`CITY → VILLAGE`) of each member, or a lighter "subordinate in place" that
  leaves the settlement's economy running under the Baron — i.e. how much sovereignty a
  sub-holding city actually loses.
- `BARONY`'s relationship to the still-unrealized `CITY`/`LEAGUE`-and-above diplomacy
  vocabulary (`CasusBelli`/`Relation`), and whether consolidation is itself a
  war/diplomacy outcome (`docs/tribal-feudal-and-war.md`).
