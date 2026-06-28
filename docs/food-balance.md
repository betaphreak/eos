# Investigation note: the food-balance collapse

**Status:** investigation findings (2026-06-28). No code changed — this records *why*
ruler colonies collapse at ~6–10 years, established by instrumenting the default
colony's necessity economy over time. Companion to `docs/births.md` (which found
births cannot fix collapse because it precedes the 15-year maturation window) and the
`colony-collapse-accepted` memory.

## Method

A temporary probe traced the default closed colony (Dhenijansar, seed 7654321) at
60-day resolution — laborer count, pool size, necessity firm count, necessity price,
supply offered, volume traded, smoothed unmet demand, necessity output, firm stock,
and total laborer larders — and swept founding food capacity, pool size, founder age,
mean skill, and births on/off. The probe was deleted after; findings below.

## The collapse is two compounding failure modes

### A. Founding under-provisioning → a violent early crunch (months ~6–18)

The default colony founds with **one necessity firm** for **~900 mouths** (405
laborers + a 495 standing pool reserve). The deep larders (laborers open with ~5900
units total; the pool with a 150-day larder) mask the production deficit for ~8
months, after which:

- the **pool reserve starves out almost entirely** (495 → 0 by day ~240) — it defers
  to the working laborers when food is scarce (a price-sensitive relief budget), so the
  reserve is sacrificed first and is gone within 8 months;
- then **laborers themselves starve** as larders deplete and the necessity price
  spirals (2 → 5 → 14 → 44): the workforce crashes 405 → ~150 by year 1.3.

Dynamic firm provisioning *does* grow the necessity sector, but reactively and far too
slowly to cover the founding population before the larders run out.

**This mode is straightforwardly fixable.** Founding with ~12 necessity firms (sized to
the population) **eliminates the early crunch**: the workforce then holds **stable at
~126 for ~3 years** with low food prices, instead of crashing in months.

### B. Replacement ratchet + a food-price spiral → the residual decline (years ~3–8)

Even with adequate founding food, the colony still collapses by ~year 7–8:

- once the **pool reserve drains** (~year 3 with adequate food; the reserve drains in
  only ~2 years once **marriages** pull spouses out of it and **births** add child
  mouths), laborer deaths go **unreplaced** — the documented replacement-only ratchet;
- necessity **output sits at bare parity with consumption** (~200–250/day for ~300
  people), so any growth in mouths tips the balance and the **price spirals** (→ 44–72);
- the workforce then declines monotonically to the dissolution floor.

Critically, **the late-stage price spiral occurs while necessity *output* is high**
(e.g. price 44 with output 500+, but supply *offered* to the market near zero). That
points to a **monetary / market-clearing** component — laborers cannot *afford* food
that is being produced (inflation / wage collapse / the inelastic `minN` minimum-buy
floor bidding price up; possibly firms throttling supply offered) — i.e. starvation
amid physical plenty, not only a physical shortage.

## Why the obvious levers don't work

- **Births make it worse, not better** — they add consuming mouths (and, via marriage,
  drain the pool of spouses) years before any child matures at 15. Higher birth rates
  collapse *sooner* (see `docs/births.md`).
- **Mean skill is not a usable surplus lever — it triggers a DEFLATIONARY SPIRAL.**
  Food output scales with `productivityOf(skill)` (skill 5 → 0.25; skill 12 → 1.73, as
  the curve goes cubic above 10), so raising skill *should* create surplus. Instead, a
  high-skill colony overproduces massively against **ration-capped demand** (people eat
  a fixed ~0.5/day no matter how much is made), so the necessity (and enjoyment) price
  **crashes toward zero**; laborer larders balloon (food is nearly free) while laborer
  wealth goes **deeply negative** (the labor-share wage budget collapses with crashed
  firm revenue, so wages crater and households borrow to the limit). The colony then
  declines roughly twice as fast as the skill-5 baseline. (An earlier note here claimed
  skill 10+ "collapses within months" — that was a *config-specific* artifact of the
  food-rich + large-reserve probe, where the laborer founding endowment, which scales
  with the head's skill-sum, overwhelmed the ruler's fixed 50-gold treasury at
  founding; in the clean default config skill 12 declines via the deflation spiral but
  does not collapse in months. Both point at calibration gaps, below.)
  - **Why overproduction doesn't self-correct (firm closure doesn't fire).** The
    dynamic provisioning's *close* rule dissolves a firm only when its **capacity
    utilization** (`output / machine-capacity`) falls below 0.55 — an "idle machines"
    signal. Under deflation the firms run **flat-out at near-100% utilization** (busy
    machines) even as the market is glutted and the price has crashed, so the
    "overbuilt" signal never trips and no firm is closed (CLAUDE.md confirms the close
    rule never fires in practice). High utilization is in fact the *charter* signal, so
    the loop is wired to add firms when machines are busy, never to read a price/glut
    collapse. **The supply-control loop measures machine busyness, not profitability or
    oversupply** — a price/profit- or inventory-based close signal is needed for it to
    react to deflation. This is a genuine model gap, independent of births.
- **More food firms / bigger reserve / younger founders / external inflow** each only
  shift the timing; none reaches the ~15-year survival the births payoff needs (swept
  in `docs/births.md` Phase 3).

## The core blocker

To let births renew the population, a colony must **survive ~15 years** (the child
working-age floor). Survival is capped at **~7–8 years** by the reserve draining plus
the food-price spiral, and the obvious surplus lever (skill) destabilizes the economy.
Births are necessary but **insufficient**; the prerequisite is a food-economy /
survival fix.

## Recommended directions (each a calibration effort)

1. **Fix founding provisioning (clearest win).** Found with necessity (and capital)
   firms sized to the founding population rather than the single seed firm, so food
   production matches demand from day 0. Demonstrated to remove failure mode A
   (stable ~126 for 3 years). Lowest-risk, highest-confidence change.
2. **Diagnose the late-stage food-price spiral.** Establish why the necessity price
   reaches 44–72 while output is high and supply offered collapses — `minN` inelastic
   demand, wage/CPI dynamics, or firms throttling market supply. This is likely the
   true residual collapse driver and may be a market-microstructure/monetary issue, not
   a physical food shortage. (Investigate `ConsumerGoodMarket.clear()` selling/▒supply
   and the laborer affordability path.)
3. **Make the supply-control loop price/profit-aware (prerequisite for any TFP gain) —
   DONE.** `Ruler.reviewSector`'s close rule now fires on an **unprofitable glut**
   (negative smoothed sector profit with no unmet-demand pressure) as well as on idle
   capacity, so an over-supplied sector that has crashed its own price contracts instead
   of running flat-out forever. The provisioning hysteresis was also shortened from a
   1-year to a **3-month** window (`MIN_FIRM_LIFETIME_DAYS` / `REENTRY_COOLDOWN_DAYS` =
   90) so the rule reacts within a quarter. Verified: a high-skill (deflationary) colony
   now contracts its necessity sector (firm count rises then falls) and the crashed price
   lifts off the floor (`GlutCloseTest`). *Caveat:* this is a control-loop fix, not a
   cure — it reacts on a lag, so it does not by itself save a high-skill colony whose
   wage economy the deflation has already damaged; it is the prerequisite that stops
   output-raising levers (skill, TFP) from backfiring into an uncorrected deflation.
4. **Create a real per-worker food surplus** (after 3) — e.g. a higher food-sector TFP
   (`NECESSITY_TECH_FACTOR` / a food tech multiplier) or lower per-capita consumption
   (ration sizes), tuned against the price/wage calibration so it does not destabilize.
5. **Extend survival to the maturation window** — slow the reserve drain (bigger
   reserve with adequate food), widen the founding-age spread (so the cohort does not
   age out together), or soften mortality — so the first home-grown generation (15y) can
   come online. Only then can the births mechanism (`docs/births.md`) deliver renewal.

Fixes 1–5 together are the path from "collapses at ~7 years" to "survives long enough
for births to sustain it." None is a single knob; this is the food-economy calibration
program that the long-accepted colony collapse has been deferring.
