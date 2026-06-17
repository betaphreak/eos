# Design note: founding a village (CARAVAN → HOLDING → VILLAGE)

**Status:** proposed (design only — not yet implemented)
**Date:** 2026-06-17
**Depends on:** the rank ladder (`eos.agent.Rank`, `RankLadder`, `Estate`,
`RankFactory` — see `docs/rank-ladder.md`), the peasant pool
(`docs/peasant-pool.md`), `GameSession`'s multi-colony support, `SlotTable`'s
special sites, and the holder machinery a `Noble` already carries (`firms`/`banks`
lists, `getDistributableProfit`/`payDividend`, `transferPropertyTo`).

## Motivation

Today a settlement springs into being fully formed at **compile time**: the long,
order-sensitive `SimulationHarness` bootstrap (markets → banks → firms → pool →
promote laborers → ruler → slot sizing) plus `GameSession.newSettlement`. There is
no way for a settlement to come into being **at runtime**, and the rank ladder's
top promotion (`HOLDING → VILLAGE`) is unrealized precisely because founding lives
outside it.

This note designs the missing transition the long way round: a **wandering
Caravan** whose leader rises `CARAVAN → HOLDING → VILLAGE`, the settlement
accreting around it as it climbs. Crucially, founding is modelled as **two ordinary
single-rung promotes**, not one rung-skipping leap — so the ladder never needs a
"promote more than one rung" generalization (the `docs/rank-ladder.md` open question
is answered: *give the middle rung content* instead). The content of that middle
rung is the founder's **holdings** — a village hall and the three banks — which also
lets us give every colony's banks a principled owner from day 0 (see *Scope*).

## The model (decided behaviour)

Founding is a **staged ascent** up the ladder, each step a real single-rung reform:

### `CARAVAN` — the wandering band

A leader household (the **Captain**) at `CARAVAN` rank commands a **following** — a
detached `Retinue` — plus a carried hoard and larder; a marketless **decaying
asset** that must settle (or trade) before it starves. The Caravan entity, and the
*downward* journey that produces one (a failing settlement declining back into a
band), are defined in **[`docs/caravan.md`](caravan.md)**; this note uses the Caravan
only as the band that *settles*. The leader's hoard is the founding capital that
capitalizes the new colony's labor force.

### `CARAVAN → HOLDING` — the leader becomes a landed holder

On the day the band settles, the leader reforms **one rung up** and establishes its
initial **holdings**:

- a **village hall** — the seat that makes it a holder with a claim to the spot
  (civic only — see below);
- the **three banks** (copper, silver, gold), chartered and **owned by the
  founder**;
- the **seed firms** (1E + 1N + 1C + builder), held by the founder for now.

This is a **real, dwell-able phase**, not a day-0 instant. The holder lives at
`HOLDING` for a stretch, accreting people (promoting `Member`s out of its retinue
into laborer households) and firms, until the holding crosses a **chartering
threshold** and becomes a village. Because a founder lingering at `HOLDING` would
otherwise be governed by `NobleConfig` — whose ~5%/step consumption would bleed the
war-chest — the holder gets its **own founder/holder config** (lean consumption,
focused on building the settlement, not rentier luxury).

What charters the village (`HOLDING → VILLAGE`) is a readiness test, candidates
(calibration, not structure): enough promoted laborer households to staff a viable
settlement, the seed firms operating and markets clearing, treasury still solvent,
or simply elapsed time. Default proposal: **operational readiness** (a minimum
laborer count with the necessity market clearing), tunable.

### `HOLDING → VILLAGE` — the holder becomes sovereign

When the threshold is met the holder reforms one more rung into the colony's
**`Ruler`** (re-banking in gold), and the nascent assets **distribute into the
social order**:

- the **seed firms** are granted to nobles — raised by the existing ennoblement
  top-up from the freshly promoted laborers (`topUpAristocracy` / the `HOLDING`
  factory);
- the **copper and silver banks are spun off** as public institutions the crown
  **taxes** (the existing `bankProfitTaxRate` model — commoners bank copper, nobles
  silver, the ruler taxes their spread/FX profit);
- the **gold bank remains the crown's own holding** (matching today's "the Ruler is
  the owner of its gold bank and its sole client") — and is therefore **not taxed**,
  so the same profit slice is never both owned and taxed;
- the **hall and the gold bank stay crown**.

So the narrative is clean and uses machinery that already exists
(`transferPropertyTo`, `addFirm`, ennoblement, `payDividend`): **`HOLDING` = the
founder holds everything nascent; `VILLAGE` = the founder becomes sovereign and
hands the assets down to the emerging classes, keeping the seat and the gold.**

## Architecture mapping

### The `Property` abstraction (firms are already holdings; banks join them)

**Firms are already holdings.** A `Noble` owns a `List<Firm>`, draws a dividend from
each (`dividendRate · max(0, firm.getProfit())`), and `transferPropertyTo` already
moves them. The asymmetry is that **banks are not** — they are ownerless harness
infrastructure. So "banks as holdings" brings banks *up* to the status firms already
have; it does not change firms.

The two are unified behind a `Property` interface — something owned by a household
that anchors rank, can be transferred, and may yield distributable profit:

```
interface Property {
    double distributableProfit();   // Firm: max(0, getProfit());  Bank: getDistributableProfit()
    void disburse(double amount);   // Firm: getBank().withdraw(getID(), amt);  Bank: payDividend(amt)
}
```

`Firm` and `Bank` implement `disburse` differently (a firm moves cash from its
account, a bank skims equity), and the interface hides exactly that difference, so
the owner's dividend loop collapses from two near-identical loops over two lists to
one polymorphic loop over a single `List<Property>` (the owner-side `credit(...,
SECIC)` stays put).

**Decisions taken** (see the design discussion):

- **The hall is a non-income `Property`** — one interface, no `IncomeHolding` split:
  it returns `distributableProfit() == 0` and a no-op `disburse()`, yields no
  dividend, but still counts as something owned. So "lose your last holding →
  demote" is a single `holdings` check.
- **Holdings stay concrete on `Noble` for the byte-identical cut.** The first step
  just collapses `Noble`'s `firms`/`banks` into one `List<Property>` — pure
  restructuring of the same arithmetic, so it is **byte-identical**. A shared
  `Holder` role (so the `Ruler` can own holdings too) is introduced later, when the
  `Ruler` actually needs it (with banks-as-holdings).
- **`Property` is not `Estate`.** The rank-ladder `Estate` is the household's *liquid*
  identity carried across a reform (members + balances); `Property`s are the
  *productive assets* owned. On a reform the ladder carries the `Estate`; the
  holdings are transferred separately (`transferPropertyTo`). Complementary concepts.

### Banks as holdings — applied to *all* founding

This is the **behavioural** half (not byte-identical), a refactor of the current
model. Today the tiered banks are conjured lazily by the harness
(`getCopperBank`/`getSilverBank`/`getGoldBank`) and owned by no one; their
FX-fee/spread profit just accrues as equity, and "the Ruler owns the gold bank" is an
unstated given. Under this design **every colony is founded through the same
bank-as-holding wiring**:

- The founder charters the three banks as holdings during the `HOLDING` phase.
- On chartering, copper + silver become **taxed public institutions**; the **gold
  bank stays crown-owned**.
- `Ruler.collectTaxes` is adjusted to **exclude the crown's own gold bank** from the
  tax base (no double-dip). **The gold bank's equity *is* the treasury** — the
  crown's retained gold-bank profit is treated as crown money directly, rather than
  being drawn out as a dividend. Fewer moving parts, at the cost of the gold bank's
  profit and the treasury blurring together (an accepted simplification).

So "the Ruler owns the gold bank" becomes an **output of founding** rather than an
assumption. **Caveat:** this touches the calibrated standard runs (the tax base
changes, the gold bank is no longer taxed), so it must be re-validated against the
test invariants and the sweeps like any other coupling — it is *not* expected to be
byte-identical.

A scenario that should start **already established** (the current sims, and the
sweeps) founds "pre-chartered": the same foundry runs, but the `HOLDING` phase is
**zero-length** (the founder is promoted straight through to `Ruler` at `t=0`). The
dwell-able phase is what the *wandering* feature exercises; the shared piece is the
bank-as-holding wiring, which every colony gets.

### The foundry — founding as a runtime operation

The harness's founding sequence is extracted into a reentrant **foundry** —
something like `foundHolding(Caravan band, GeoLocation where)` and
`charterVillage(holder)` — that, between them, do what the harness does today but
callable mid-run: `session.newSettlement(where, …)`, build the E/N/labor/capital/
wedding markets, charter the banks as the founder's holdings, found the seed firms
and claim their slots at `MIN_SIZE`, seed the new colony's `Retinue` from the
band's surviving `Member`s, and promote the ablest into laborer households
(reusing `foundLaborersFromRetinue` / `promoteToLaborer` wholesale). Multi-colony
support already exists (Hanseatic), so a founded village is just colony *N+1* in the
session.

### The Caravan entity

The `Caravan` (a detached `Retinue` + a `CARAVAN`-rank Captain + a position + a
carried hoard) is defined in **[`docs/caravan.md`](caravan.md)**. For founding, the
relevant facts: its pool **re-binds to the new settlement** at founding, and the
founder/holder config governs the Captain through the `HOLDING` phase.

### The village hall — a civic seat (only)

The hall is the **first special site**. The size table lines up: `maxSpecialSites`
unlocks at sizes `{0,4,10,19,31,57}`, so a village founded at `MIN_SIZE` (3) has
**exactly one** special site — the hall — occupying reserved out-of-band capacity
that does not crowd the 15 effective slots the seed firms need. The slot machinery
it sits on already exists (see [`settlement-slots.md`](settlement-slots.md)): the
`SlotOccupant` interface (`Agent` implements it) and occupiable special sites
(`claimSpecialSite`/`getSpecialSites`) were built as pre-work; the hall will be the
**first non-firm occupant** of a special site (and likely the first non-`Agent`
`SlotOccupant`, which is where the land-funding bridge will need to generalize).

The hall is **civic only**: it marks the holding and the rank and has **no economic
function yet** (no taxation/governance gating). Its one mechanical consequence is as
the **seat of rank**: losing the hall is losing one's last holding, which feeds the
rank ladder's planned **demotion trigger** (`VILLAGE → HOLDING` on loss of the
seat — the converse direction of the insolvency demotion already built).

### Geography, wandering, determinism

"Wandering" needs a map and movement, which do not exist (settlements are isolated
points). This is why the feature **rides on caravan trade** (the near-term roadmap
item that first forces inter-settlement geography and travel): the Caravan reuses
that position/movement machinery rather than inventing it. Once the band picks a
spot, its `lat/long` flows into `newSettlement` and the solar/latitude system gives
the new village its climate for free.

Determinism needs care: every colony's economic `Rng` is derived from `session seed
+ colony index` at `newSettlement`. A colony-less Caravan needs a deterministic
stream too (session-level or pre-allocated), and the founded village must take the
next colony index, so "same seed → identical run" still holds.

## Accepted limitations (explicitly out of scope for this cut)

1. **Geography/movement is deferred to caravan trade.** Until that lands, a Caravan
   founds *in place* at a hardcoded location with no real wandering.
2. **The self-funding wandering economy is unsolved.** A band with no markets must
   stay fed and accumulate founding capital somehow (a carried hoard, foraging,
   trade). The first cut gives the Caravan a starting treasury and a larder, and
   leaves genuine sustenance-while-wandering to the trade feature.
3. **The bank-as-holding refactor disturbs calibrated runs.** Excluding the gold bank
   from the tax base (its equity becomes the treasury directly) is a behavioural
   change needing re-validation; it is not byte-identical. (The `Property`-interface
   *restructuring* — collapsing `Noble`'s two lists — is separate and byte-identical.)
4. **The hall has no economic function yet.** It is a seat and a rank marker only;
   governance/taxation tied to the hall is future work.
5. **The founder/holder config is new and uncalibrated.** Its consumption and the
   chartering threshold are placeholders.

## Phased implementation plan

- **Phase 0 — the `Property` interface (byte-identical).** Introduce `Property`
  (`distributableProfit()` + `disburse()`), have `Firm` and `Bank` implement it, and
  collapse `Noble`'s `firms`/`banks` lists and its two dividend loops into one
  `List<Property>`. Pure restructuring of the same arithmetic — byte-identical — and
  the foundation the next phase and the hall sit on. Holdings stay concrete on
  `Noble` (no shared `Holder` role yet).
- **Phase 1 — banks as holdings, for existing founding. (Implemented.)** The crown's
  own (gold) bank is now exempt from `Ruler.collectTaxes` — it is a crown holding
  whose retained profit *is* the treasury, so taxing it would be the crown skimming
  its own bank into its own account; only the public copper/silver institutions are
  taxed. The decided minimal cut: no `Holder` role on the `Ruler`, no dividend draw —
  the gold bank's equity simply stays the crown's (and blurs into the treasury).
  Behavioural (not byte-identical) — re-validated against the full suite (the
  analytical sweeps run ruler-less colonies, so taxation does not touch them).
  Covered by an added `RulerTaxationTest` assertion (gold bank's `distributedProfit`
  stays 0 while the silver bank is skimmed).
- **Phase 2 — the foundry. (First cut implemented.)** The canonical single-copper-bank
  founding sequence is packaged as `SimulationHarness.foundStandardColony(...)` —
  markets, firms, export sector, ruler + gold treasury, peasant pool, labor force
  promoted from it, external inflow, in one call. The default `HomogeneousEconomy`
  founds through it; it reproduces the hand-written sequence verbatim, so the run
  stays **byte-identical** (verified by a CSV-checksum diff of a full run, all files
  matching, plus the green suite). (At the time of writing, the single-copper-bank
  demos `HeterogeneousEconomy`/`PeasantEconomy` were also migrated through it, before
  they were retired in a simulation-roster cleanup.) The bare `SmallOpenEconomy`
  (two copper banks, no ruler/pool) keeps composing the granular methods this
  orchestrates — its divergent market/bank ordering is why a single rigid foundry
  cannot absorb every founding. This is the seam a runtime founder will build on; the runtime
  founding of a *new* colony mid-run (via `GameSession.newSettlement`) arrives with
  the Caravan in Phase 3.
- **Phase 3 — the Caravan + the dwell-able `HOLDING` phase.** The `Caravan` entity,
  the founder/holder config, `CARAVAN → HOLDING` establishing the hall + banks, the
  chartering threshold, `HOLDING → VILLAGE` distributing assets and seating the
  ruler. Founds *in place* (hardcoded location), no movement. A test: a seeded
  Caravan with enough members and gold founds a viable colony that then sustains
  itself.
- **Phase 4 — geography & wandering (after caravan trade).** Real position,
  movement, a settle decision, and trade-fed sustenance/hoarding for the band.
- **Phase 5 — hall-loss demotion.** Wire `VILLAGE → HOLDING` (and lower) when the
  seat/last holding is lost, completing the symmetric ladder.

## Decided since (see `docs/city-and-league.md`)

- **`VILLAGE` is now a promotion target.** This note's ascent tops out at `VILLAGE`,
  but the rung above it is realized in `docs/city-and-league.md`: `VILLAGE → CITY`
  urbanizes a village into a **permanent** settlement (a `Ruler → Mayor` reform that
  flips the colony onto the open-economy immigration inflow, so it no longer
  collapses), and `CITY → LEAGUE` federates cities into a bloc. So `CITY` (and the
  federation idea an earlier draft mis-assigned to it) is no longer a reserved rung —
  see that note for the corrected taxonomy.

## Open questions deferred to later

- The **chartering threshold**: which readiness signal (population vs. solvency vs.
  operating markets vs. time vs. player choice), and its calibration.
- The **founder/holder config** values (consumption while building, what it buys).
- Whether the standard sims should visibly pass through a zero-length `HOLDING`
  phase or bypass it entirely while still sharing the bank-as-holding wiring.
- The deterministic RNG stream for a colony-less Caravan, and how it composes with
  per-colony streams when the village is founded.
