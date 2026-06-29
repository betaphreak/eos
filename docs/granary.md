# The granary and the consolidated food economy

**Status:** part built, part proposed (2026-06-29). The **ever-normal granary** of §3 is
**implemented** (Phase 1); the **consolidated model** of §4 — making the granary the
colony's single strategic food store that the ruler, nobles, and peasant pool all *draw
from* rather than each buying its own reserve on the market — is a **design proposal**, no
code yet. The wider renewal loop the granary is the keystone of (§5) is partly built,
partly proposed.

This doc supersedes the former `food-economy-redesign.md`. Companion to
[`docs/food-balance.md`](food-balance.md) (the diagnosis that motivates all of this),
[`docs/births.md`](births.md) (the births mechanism), and
[`docs/peasant-pool.md`](peasant-pool.md) (the labor model).

## 1. Why a granary: the coupled production↔renewal trap

A ruler colony collapses at ~10–12 years. `docs/food-balance.md` measured eight separate
levers and proved the collapse is a **coupled production↔renewal trap**, not a tuning gap:

| Lever | Result | Why it failed |
|---|---|---|
| Founding-age pool children | ~9→11.5 y (shipped) | staggers the cohort — delays only |
| Softer mortality (0.5→0.15) | flat | decisive deaths are starvation, not old age |
| Demand-driven promotion | worse | extra workers are net food *consumers* in the crunch |
| Higher mean skill (7→8) | worse | ration-capped demand absorbs the output; bigger endowment drains the ruler |
| Household fission (built) | dormant | no child survives to maturity in a living parent household |
| Child ration / protection | no help | children mature too late vs. parent lifespan |
| minN price-cap | flat | the spike is **budget-driven**, not floor-driven; the deficit is real |
| Asymmetric price band | chaotic | distorting price discovery destabilizes the coupled loop |

A colony is stable **iff both** hold at the operating population:

1. **Production balance** — food output ≥ consumption, *and* the surplus does not crash
   the price/wage economy.
2. **Renewal balance** — new households form at ≥ the death rate.

They are coupled (output needs workers; workers need food; renewal needs a food buffer;
children need food to mature; the two loops share the *same scarce food*), which is why
no single knob works — adding people without first creating a stored surplus just
accelerates the deficit. The fix must establish a **food surplus that is stored rather
than dumped** (so output can exceed consumption without deflating wages) and then **spend
that surplus on renewal**. The mechanism that makes a stored surplus possible is the
granary.

## 2. The keystone idea

An **ever-normal granary**: a ruler-run food buffer that trades real stock against the
necessity market within a band around the market's reference price
(`ConsumerGoodMarket.getInitialPrice()`):

- when supply would push the price **below a floor** (a glut), the granary **buys** the
  surplus and stocks it — the price cannot crash, so firm revenue and the labor-share
  wage budget survive;
- when scarcity would push the price **above a ceiling**, the granary **sells** from
  stock — capping the spike at its source and putting more food on the market when it is
  scarcest.

It acts on **quantity** (real stock in and out), which is why it serves **both** price
regimes a symmetric price band cannot — the asymmetric-band and minN-cap levers both
failed precisely because they distorted *price discovery* instead of moving *quantity*.
Its accumulated stock is the colony's strategic food reserve — the buffer renewal (§5)
spends on keeping the next generation alive.

## 3. The granary as built (Phase 1)

`com.civstudio.agent.Granary` is a bare, copper-banking `Agent` holding a `Necessity`
reserve. Each step:

- **Decide from last step's price.** Read `nMkt.getLastMktPrice()` against
  `getInitialPrice()`; `floor = floorFactor·ref`, `ceil = ceilFactor·ref`.
- **Glut → buy wall.** If the price is at or below the floor and stock is under target,
  post a price-contingent buy: `price -> price <= floor ? cap : 0`, with `cap =
  min(perStepTradeCap, target − stock)`. The market's binary search sums this into total
  demand, so the clearing price cannot fall through the floor while there is room in the
  reserve.
- **Scarcity → sell.** If the price is at or above the ceiling and the granary holds
  stock, post a plain `addSellOffer(this, min(perStepTradeCap, stock))` — adding real
  supply that pushes the price back down.
- **Target scales with population.** `target = targetDays · dailyConsumption`, where
  `dailyConsumption` is summed live over the workforce (`Household.isWorkforce()` members
  × `RationSize.FINE`), so the reserve tracks the colony it must feed.

**Funding — the granary's cash P&L is the crown's.** Each step it reconciles its copper
account to ~0 against the ruler's gold treasury (mirroring `Retinue.billRuler`): a deficit
(the overdraft a purchase ran up) is covered by the ruler, a surplus (sale proceeds) is
remitted back. Both transfers cross gold↔copper and fire the gold bank's FX fee, and the
ruler borrows if short. So the granary hoards no idle money; the value it skims buying low
and selling high lands in the treasury, where it recirculates through relief and
endowments.

**Parameters** (`GranaryConfig`, placeholders pending the §6 sweep): `floorFactor` (0.6),
`ceilFactor` (2.0), `targetDays` (60), `perStepTradeCap` (100). The granary is founded by
`SimulationHarness.createDefaultGranary` inside `foundStandardColony` (so every standard
colony has one), reported by `GranaryPrinter` (`Granary.csv`), and covered by
`GranaryTest`.

**Measured** (default colony, seed 7654321, Dhenijansar): the granary banked the early
founding food surplus (bought ~11.6k units at prices 0.37–1.3) and released it into the
late scarcity, holding the **operating-window** necessity price in **[0.37, 5.6]** (years
1–10.5: no 0.09 crash, no 40+ spike) and netting the crown a ~40-gold buy-low/sell-high
profit. The terminal 70–82 spikes are confined to the final ~4 months — the collapse
itself (reserve exhausted as the workforce vanishes), which Phase 1 does not fix. Collapse
horizon **essentially unchanged** (1456-05 vs a granary-off baseline of 1456-06). The main
calibration wrinkle is that the granary front-loads its whole reserve in ~6 months (the
per-step cap was smaller than the founding glut, so the floor was not even fully defended);
revisit behind Phase 2's TFP raise, which makes the reserve refillable from genuine surplus
rather than from competing with the founding colony for food.

## 4. The consolidated model (proposed)

### 4.1 The redundancy this resolves

The colony now carries **four overlapping food reserves**, all bidding the *same*
necessity market with different demand curves:

| Holder | Reserve | Demand curve | Behavior in scarcity |
|---|---|---|---|
| **Ruler** | 30-day GOURMET larder | `price → nGap` | **fully inelastic** — restocks at any price |
| **Noble** | 30-day LAVISH larder | `min(nGap, budget/price)` | price-sensitive, capped at the reserve gap |
| **Pool** | `bufferDays` SIMPLE larder | `min(reliefBudget/price, room)` | price-sensitive — **defers** when dear |
| **Granary** | `targetDays` workforce reserve | `price ≤ floor ? cap : 0` | **switches off** above the floor |

The granary as built does not meaningfully *crowd out* the others — it yields entirely in
scarcity (buys nothing above the floor, and *sells*, which helps them restock) and only
buys the surplus in gluts. But the *existence* of four parallel reserves — several of them
inelastic — all bidding at once is the genuine design smell: it is N reserve-builders
competing where one smart counter-cyclical buyer would do.

The resolution is **not** to shrink the granary; it is to make it the colony's **single
strategic store**, and have the other reserve-holders **draw their ration from it** instead
of each buying its own on the market. The market then sees one counter-cyclical buyer (the
granary), not four overlapping ones — strictly *less* market competition, and the ration
hierarchy and currency semantics are preserved at the draw (below).

### 4.2 The seam

`Granary.drawStock(units)` already exists: it removes up to `units` from the reserve and
returns the amount actually drawn (less if the reserve is short). A holder asks the granary
for its ration and eats what it gets; a shortfall behaves exactly as a market shortfall
does today (the elite never starve and fall back to the market; the pool/children starve on
the residual). The only structural plumbing is a `Settlement.setGranary/getGranary`
reference (mirroring `setBuilder/setRuler`), registered by `createDefaultGranary`, so a
holder can reach the colony's granary from its `act()`.

### 4.3 Who draws, who keeps buying

- **Ruler, nobles, pool** → **draw from the granary**, dropping their own larders. They are
  reserve/relief holders; consolidating them is the whole point.
- **Laborers** → **unchanged, keep buying daily on the market.** The workforce's daily
  purchase *is* the price signal the granary exists to stabilize; routing it through the
  granary would nationalize food retail and erase the signal. The line is: the granary
  consolidates **reserves and relief**, not the workforce's market consumption.

So the granary becomes the sole *reserve* buyer (its buy-wall in gluts now sized to cover
its price-defense buffer **plus** the daily draws it owes the elite and the pool), plus the
seller above the ceiling, plus the internal retailer that serves the draws. It buys for the
elite and the poor *in plenty* and feeds them *in dearth* — the classic granary, now for
the whole non-wage population.

### 4.4 Preserving the ration tiers

The social hierarchy of the table is today expressed by **how much each holder buys**
(GOURMET 2.0 / LAVISH 1.0 / SIMPLE 0.25 per day). In the consolidated model it is expressed
by **how much each draws**: `granary.drawStock(ration.perDay() · memberCount)`. The
`RationSize` tiers survive unchanged — they parameterize the draw instead of the buy. No
tier logic moves; only its call site does.

### 4.5 Preserving the FX / currency semantics

Today a gold-banked ruler pays the gold bank's FX fee on every copper-quoted food purchase;
a silver-banked noble likewise. A naive "draw food for free from the granary" would erase
that money-changer revenue. Preserve it by making a draw a **paid transfer at the
copper-quoted food value**, in the holder's own currency:

```
ruler.getBank().withdraw(rulerID, qty · price);      // gold → copper, FX fee fires
granary.getBank().credit(granaryID, qty · price, …); // copper, no fee
```

So the ruler still converts gold→copper to eat — it just pays the granary instead of the
firms, and the FX fee is unchanged. The granary's resulting profit (it bought at the floor,
sells to the elite at market) reconciles back to the ruler via §3's mechanism, so an
elite holder buying from the crown's own granary is roughly a wash, while a **noble** paying
the granary is a noble→crown food transfer (a mild, realistic seigneurial levy).

**Relief draws are subsidized.** The pool (and, in §5, children) draw at the crown's
expense, not their own — mirroring today's ruler-funded pool relief: the relief draw is
free (or at cost) to the drawer and billed to the ruler. The elite pay (in their own
currency, FX applies); the poor are fed by the crown. The class structure is intact.

### 4.6 The contended reserve, and the price-defense floor

The granary's stock now serves **two** functions that draw the same pool:

- **price defense** — market *sells* above the ceiling (adds supply, caps spikes), and
- **internal supply** — *draws* by the ruler/nobles/pool (and later children), every day.

These compete in scarcity, when both fire at once. A `priceDefenseFloor` knob resolves it:
the granary reserves a slice of stock (`priceDefenseFloor · target`) that only its **sells**
may touch, so a run of daily elite/pool draws cannot exhaust the buffer it needs to cap a
spike. Below that floor, **draws fall back to the market** (the holder buys directly, as it
does today) while the protected slice stays available for price defense. Tuning this knob
trades "keep the elite fed from the store" against "keep enough stock to defend the price"
— a calibration target for §6.

### 4.7 Accounting and reporting

- **`drawStock` must not be mis-attributed as a market sale.** The granary's `act()`
  classifies last cycle's stock change as a market buy or sell from `stock − stockAtLastAct`;
  an internal draw also shrinks stock and would read as a phantom sale. Fix: `drawStock`
  accumulates `reliefDrawnSinceLastAct`, and the granary computes market trade as
  `netMarket = delta + reliefDrawn` (then resets). Draws get their own reported column.
- **Money conservation.** Paid draws move money (holder→granary) and food (granary→holder)
  in opposite directions; subsidized relief draws move only food, with the cost picked up by
  the ruler reconciliation. Every path nets out against the treasury exactly as the pool's
  relief billing does today.

### 4.8 Migration path (each step independently verifiable)

1. **(done)** Granary stabilizes the price (§3).
2. **Pool relief backstop.** Add `Settlement.getGranary`; in `Retinue.feed()`, draw any
   shortfall from the granary before counting starvation (subsidized, ruler-billed). The
   smallest, lowest-risk consolidation. **Verify:** the reserve survives lean spells longer,
   the price is undisturbed, no double-counting in `Granary.csv`.
3. **Child relief.** Feed unfed children from the granary (§5.3) — the same `drawStock` call
   from `Laborer` feeding.
4. **Elite draws.** Drop the ruler's and nobles' own larders; have them draw their
   GOURMET/LAVISH ration from the granary as a **paid** transfer (§4.5). **Verify:** FX
   revenue preserved, elite stay fed, the number of independent necessity bidders drops from
   four to one (+ laborers).
5. **Price-defense floor.** Add and calibrate `priceDefenseFloor` (§4.6) so daily draws do
   not starve the granary's spike-defense.

Sequence steps 2–5 **after** Phase 2's TFP raise (§5), so the granary actually holds banked
surplus to distribute rather than competing with the founding colony for scarce food.

## 5. The rest of the renewal loop (the granary is the keystone)

The granary lets a stored surplus exist; renewal spends it. The other components are
calibration of mechanisms already built or designed.

- **5.1 Net-food-positive workers (calibration, now safe).** For the granary to fill, a
  necessity worker must over-produce. Raising necessity TFP (`NFirm.effectiveA` /
  `NECESSITY_TECH_FACTOR`, or the tech tree's per-sector multiplier) was self-defeating
  before because surplus deflated the price; **behind the granary and the glut-aware close
  rule, surplus is absorbed at the floor instead.** Target output ≈ 1.3–1.5× consumption.
- **5.2 Child survival — child relief (new, small).** Children starve first (the household
  feeds head → adults → children), so the next generation is culled before it matures — the
  measured reason fission never fires. Draw an unfed child's ration from the granary (§4.5
  relief draw) rather than letting it starve, billed to the crown. The granary's accumulated
  surplus is exactly the reserve that pays for this.
- **5.3 Home-grown renewal — fission + marriage (built + calibration).** Fission is built
  (`SimulationHarness.formNewHouseholds`, dormant): a colony-born child that reaches working
  age leaves to found its **own** household, turning births into household *count*. Once
  5.1–5.2 let children survive to maturity, this is the valve that fires; make its food dowry
  granary-funded (the parent's larder is depleted exactly when its child matures). Marriage
  throughput gates births — raise `WeddingConfig.capacity` and/or seed pool-promoted laborers
  as couples so enough households pair to sustain a birth rate.
- **5.4 How the loop closes.** Workers over-produce → the granary fills and holds the price
  stable → stable prices and wages let households prosper, marry, and clear the births
  food-buffer → children are born and **survive lean spells on granary relief** → they mature
  and **fission into new households** → the workforce renews from within, independent of the
  finite pool. The pool reverts to its proper role: **founding seed + immigration buffer**,
  not the sole renewal source.

## 6. Success criteria

The redesign works iff a default colony (seed 7654321, Dhenijansar) **does not collapse**
over a long horizon (40+ in-game years):

- **household count** stable or growing (the current monotonic decline is gone);
- **new households/yr** (fission + promotion) ≥ **deaths/yr**, fission dominant once the
  first home-grown generation matures (~year 15);
- **granary stock** oscillates around a positive target (never chronically empty), with the
  price-defense floor (§4.6) intact through spikes;
- **necessity price** stays within the granary band (no crash, no spiral);
- **laborer wealth** stays positive on average (no deflationary debt spiral).

A `CalibrationSweep`-style harness over the granary/TFP/relief/marriage parameters then finds
the stable region (the earlier sweep proved none exists under the replacement-only model —
the point of this redesign is to make one exist).

## 7. Risks and open questions

- **Ruler treasury drain.** The granary, child relief, and fission dowries all spend the
  ruler's fortune. Mitigation: the granary buys low / sells high (roughly self-funding, and
  in Phase 1 it *net-earned* the crown ~40 gold); relief and dowries are paid from granary
  *stock* (food bought cheap), not fresh treasury. Needs measurement under consolidation.
- **Granary instability.** A badly-tuned band could oscillate or corner the market.
  Mitigation: per-step trade caps, a dead-band between floor and ceiling, and the
  `SectorMemory` hysteresis pattern from firm provisioning if needed.
- **TFP deflation if the granary saturates.** If the granary hits target and stops buying,
  surplus again deflates. Mitigation: scale target with population, and spill past-target
  surplus to exports or a spoilage term rather than crashing the price.
- **Consolidation erodes FX revenue / flattens the table.** §4.5 keeps the FX fee by making
  draws paid in the holder's currency, and §4.4 keeps the ration tiers at the draw — but both
  are load-bearing for the currency and social-class models, so the consolidation must be
  validated to preserve them, not just the food flows.
- **Over-renewal.** If fission + births overshoot, population could outgrow food — the
  *healthy* problem (the granary + glut-close + firm provisioning are the negative feedback),
  but worth watching.
