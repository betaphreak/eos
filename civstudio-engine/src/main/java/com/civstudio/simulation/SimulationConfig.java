package com.civstudio.simulation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.civstudio.agent.firm.ScienceConfig;
import com.civstudio.agent.laborer.FertilityConfig;
import com.civstudio.settlement.GameSession;
import com.civstudio.era.Era;
import com.civstudio.race.Race;
import lombok.Builder;

/**
 * Run-level configuration for a simulation: the calendar (each step is one
 * in-game day), the colony's site and founding shape, and the research/fertility
 * knobs. The <b>economy</b> — prices, agent starting balances, tax rates, the
 * peasant pool — is <em>not</em> here: it belongs to the colony, which resolves it
 * from the race of its province ({@code Settlement#getEconomy()}) and lets a
 * scenario adjust it via {@code SimulationHarness.tuneEconomy}. Immutable;
 * {@link #DEFAULT} holds the canonical values
 * shared by the bundled simulations. Build a modified instance to script a
 * different run without editing source.
 *
 * @param settlementName the settlement's name (a display label, exposed via
 *                     {@code Settlement.getName()})
 * @param startDate    in-game date of step 0; each step advances one day
 * @param durationYears number of in-game years the simulation runs
 * @param numEFirms    number of enjoyment firms
 * @param numNFirms    number of necessity firms
 * @param meanInitAgeYears mean initial age (years) of founding household heads,
 *                     the center of the normal distribution their ages are
 *                     drawn from
 * @param targetNStock target necessity stock every laborer tries to accumulate
 *                     (in real units); a colony-wide constant rather than a
 *                     per-laborer preference, so it is set on the {@code Settlement}
 * @param meanSkillMale mean of the <b>male</b> skill distribution at colony start;
 *                     a colony-start property (set on the {@code Settlement}) that
 *                     centers the skill spread of its male people, hence their labor
 *                     productivity
 * @param meanSkillFemale mean of the <b>female</b> skill distribution at colony
 *                     start (currently lower than the male mean); see {@code
 *                     Settlement.getMeanSkill(Gender)}
 * @param latitude     the colony's geographic latitude in decimal degrees (north
 *                     positive), a colony-start property used for daylight
 *                     calculations (see {@code Settlement.getSunrise})
 * @param longitude    the colony's geographic longitude in decimal degrees (east
 *                     positive)
 * @param researchInitialFraction the fraction of the warm-start focus's cost a fresh
 *                     colony begins with (default {@code 0.9} — 90% complete). The
 *                     warm-start <em>focus</em> and the pre-known baseline are derived
 *                     from the {@link GameSession}'s {@link Era}
 *                     (a colony knows up to the era below it and founds 90% through that
 *                     era's entry tech), not configured here
 * @param researchCostScale multiplier on each tech's authored research cost (the
 *                     pacing knob); &lt;1 makes research faster, &gt;1 slower. The
 *                     research-point yield itself is set by the {@link
 *                     ScienceConfig science firm's} production curve
 * @param fertility    household fertility parameters (when a married laborer
 *                     household bears a child), applied to the {@code Settlement}.
 *                     Births are on by default (see {@link FertilityConfig#DEFAULT})
 * @param foundingLaborersPerNFirm number of founding laborers per founding
 *                     <b>necessity</b> firm — sizes the founding food sector to the
 *                     labor force (round(promotionRatio*retinueSize)) so food
 *                     production matches demand from day 0 instead of ramping from a
 *                     single seed firm (see {@code docs/food-balance.md}). Applied by
 *                     {@code SimulationHarness.foundStandardColony}, clamped to what
 *                     the colony's (province-capped) plots can seat; {@code 0} keeps
 *                     the fixed {@code numNFirms}. The granular-founding sim
 *                     (SmallOpen) bypasses it
 * @param expeditionTaxRate the fraction of a returning explorer expedition's haul
 *                     proceeds the crown keeps as tax (the rest seeds the returnees'
 *                     new households); part of the renewal loop's reward-on-return
 *                     (see {@code docs/explorer-caravan.md} commit 2). A calibration
 *                     placeholder
 * @param expeditionNobleShare the fraction of a returning expedition's <b>taxed-net</b>
 *                     proceeds awarded to its ablest returnee, who is ennobled into a
 *                     silver-banking noble; the remainder is split among the other
 *                     returnees, each founding a copper-banking laborer household. A
 *                     calibration placeholder
 * @param foundAtCamp whether the colony founds low (as a foraging {@code CAMP}) and
 *                     climbs the tier ladder rather than founding at its site ceiling
 *                     with the full ruler economy (docs/settlement-tier-ladder-plan.md D4)
 * @param homePlots whether landed laborer households each work a <b>home plot</b> for
 *                     subsistence food (dropped straight into their larder, non-market —
 *                     the plot-working economy of {@code docs/plot-working-plan.md} P1).
 *                     Default {@code false} (the existing pure-market economy); a
 *                     province-founded scenario opts in
 */
@Builder(toBuilder = true)
public record SimulationConfig(
		String settlementName,
		LocalDate startDate,
		int durationYears,
		int numEFirms,
		int numNFirms,
		double meanInitAgeYears,
		double targetNStock,
		double meanSkillMale,
		double meanSkillFemale,
		double latitude,
		double longitude,
		double researchInitialFraction,
		double researchCostScale,
		FertilityConfig fertility,
		int foundingLaborersPerNFirm,
		double expeditionTaxRate,
		double expeditionNobleShare,
		boolean foundAtCamp,
		boolean homePlots) {

	/** Inclusive bounds for a market's initial price. */
	@Builder(toBuilder = true)
	public record PriceRange(double min, double max) {
	}

	/** Initial state of a consumer-good firm. */
	@Builder(toBuilder = true)
	public record FirmInit(
			double checking,
			double savings,
			double output,
			double wageBudget,
			int capital) {
	}

	/** Initial state of the capital firm. */
	@Builder(toBuilder = true)
	public record CFirmInit(
			double wageBudget,
			double checking,
			double savings) {
	}

	/** Initial state of a laborer. */
	@Builder(toBuilder = true)
	public record LaborerInit(
			double e,
			double checking,
			double savings,
			double savingsRate) {
	}

	/** The in-game date the run ends (exclusive; one day past the last step). */
	public LocalDate endDate() {
		return startDate.plusYears(durationYears);
	}

	/** Number of daily steps to run (== days from {@code startDate} to {@code endDate}). */
	public int numStep() {
		return (int) ChronoUnit.DAYS.between(startDate, endDate());
	}

	/** The in-game date at simulation step {@code step} (step 0 == {@code startDate}). */
	public LocalDate dateAt(int step) {
		return startDate.plusDays(step);
	}

	/**
	 * The canonical run configuration: {@link Era#MEDIEVAL} as the <b>human</b> race founds it.
	 * <p>
	 * Equivalent to {@link #defaultFor(Race) defaultFor(Race.HUMAN)}. Kept as a constant because most
	 * scenarios, the server and the calibration tools all start from it.
	 */
	public static final SimulationConfig DEFAULT = defaultFor(Era.MEDIEVAL, Race.HUMAN);

	/**
	 * The canonical Medieval configuration <b>as {@code race} founds it</b>.
	 *
	 * @param race the founding race
	 * @return the base configuration to build on
	 */
	public static SimulationConfig defaultFor(Race race) {
		return defaultFor(Era.MEDIEVAL, race);
	}

	/**
	 * The canonical configuration for an (era, race) cell — the <b>base</b> a scenario builds on.
	 * <p>
	 * Since phase 3 the economy proper no longer lives here: a colony carries its own {@link
	 * Era.Economy} (see {@code Settlement#getEconomy()}), resolved from the race of the province it
	 * stands in, and a scenario tweaks it through {@code SimulationHarness.tuneEconomy}. What this
	 * factory still reads off the cell is {@code targetNStock}, which is consumed at settlement
	 * construction — before any harness exists — and so cannot ride the colony.
	 *
	 * @param era  the founding era; must be calibrated (see {@link Era#economy(Race)})
	 * @param race the founding race; falls back to the human column until its own is authored
	 * @return the base configuration for that cell
	 * @throws IllegalArgumentException if the era has no economy — better than founding a colony on
	 *         silently-null tuning
	 */
	public static SimulationConfig defaultFor(Era era, Race race) {
		Era.Economy econ = era.economy(race);
		if (econ == null)
			throw new IllegalArgumentException("era " + era + " has no economic tuning"
					+ " (only calibrated eras can found a colony)");
		return new SimulationConfig(
				"Dhenijansar",                         // settlementName
				LocalDate.of(1444, 12, 11),            // startDate
				25,                                    // durationYears
				1,                                     // numEFirms (founding count; the
				                                       //   ruler's dynamic provisioning
				                                       //   grows the sector from here)
				1,                                     // numNFirms (founding count)
				35,                                    // meanInitAgeYears
				econ.targetNStock(),
				7,                                     // meanSkillMale
				5,                                     // meanSkillFemale
				51.5074,                               // latitude (London)
				-0.1278,                               // longitude (London)
				0.9,                                   // researchInitialFraction (90%)
				1.0,                                   // researchCostScale
				FertilityConfig.DEFAULT,               // fertility (births on by default)
				30,                                    // foundingLaborersPerNFirm (size the
				                                       //   founding food sector to demand)
				0.2,                                   // expeditionTaxRate (crown keeps 20% of
				                                       //   a returning haul; calibration)
				0.3,                                   // expeditionNobleShare (the ablest
				                                       //   returnee's ennoblement share)
				false,                                 // foundAtCamp (found at maxTier with the
				                                       //   ruler economy; geographic colonies
				                                       //   opt in to found low and climb —
				                                       //   docs/settlement-tier-ladder-plan.md D4)
				false);                                // homePlots (pure-market economy by default;
				                                       //   a province-founded scenario opts into
				                                       //   subsistence home plots — plot-working-plan.md P1)
	}
}
