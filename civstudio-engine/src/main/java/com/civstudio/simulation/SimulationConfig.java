package com.civstudio.simulation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.civstudio.agent.firm.ScienceConfig;
import com.civstudio.agent.laborer.FertilityConfig;
import com.civstudio.settlement.GameSession;
import com.civstudio.era.Era;
import lombok.Builder;

/**
 * Run-level configuration for a simulation: the calendar (each step is one
 * in-game day), population sizes, market price bounds, and the initial state of
 * each agent type. Immutable; {@link #DEFAULT} holds the canonical values
 * shared by the bundled simulations. Build a modified instance to script a
 * different run without editing source.
 *
 * @param settlementName the settlement's name (a display label, exposed via
 *                     {@code Settlement.getName()})
 * @param startDate    in-game date of step 0; each step advances one day
 * @param durationYears number of in-game years the simulation runs
 * @param numEFirms    number of enjoyment firms
 * @param numNFirms    number of necessity firms
 * @param ePrice       initial price bounds for the enjoyment market
 * @param nPrice       initial price bounds for the necessity market
 * @param eFirm        initial state of each enjoyment firm
 * @param nFirm        initial state of each necessity firm
 * @param cFirm        initial state of the capital firm
 * @param laborer      initial state of each laborer
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
 * @param externalInflowPerStep money entering the colony from outside each
 *                     step, injected into the bank's equity; 0 leaves the
 *                     colony closed (no inflow, no immigration)
 * @param immigrationThreshold equity level at which the bank funds one new
 *                     immigrant household out of equity (and the household's
 *                     opening checking balance); only fires when
 *                     {@code externalInflowPerStep > 0}
 * @param laborShare   fraction of its revenue each consumer-good firm budgets
 *                     for wages (the labor-share wage-budget rule), so total
 *                     wage spending — and the market wage totalBudget/N —
 *                     scales with the colony as population grows; 0 falls back
 *                     to the legacy cash-flow-gap rule
 * @param bankProfitTaxRate fraction of each bank's distributable profit the
 *                     Ruler skims into its treasury each step (the bank-profit
 *                     tax); 0 disables it (the default, pending calibration)
 * @param nobleIncomeTaxRate fraction of each noble's per-step income the Ruler
 *                     skims into its treasury each step (the noble income tax);
 *                     0 disables it (the default, pending calibration)
 * @param retinueSize number of peasants a colony with a pool is seeded with (the
 *                     full pool — founding cohort plus standing reserve). Default
 *                     {@code 900}; the number of laborer households a pool colony
 *                     founds is {@code round(promotionRatio * retinueSize)}, so
 *                     this and {@code promotionRatio} together set the labor force.
 * @param promotionRatio the fraction of its peasant pool a colony promotes into
 *                     laborer households. On day 0 the ruler promotes the ablest
 *                     {@code round(promotionRatio * retinueSize)} peasants into
 *                     laborer households, and replaces later deaths from the
 *                     remaining reserve until it drains (see {@link
 *                     SimulationHarness#foundLaborersFromRetinue})
 * @param targetNobles the size of the aristocracy the colony maintains by
 *                     ennoblement. No nobles are created at founding; the ruler
 *                     elevates the ablest laborers (highest SOCIAL) into
 *                     silver-banking nobles up to this count over the first weeks,
 *                     working the strategic firm itself meanwhile so it is never
 *                     unstaffed. Default {@code 5}; does not scale with colony size
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
 */
@Builder(toBuilder = true)
public record SimulationConfig(
		String settlementName,
		LocalDate startDate,
		int durationYears,
		int numEFirms,
		int numNFirms,
		PriceRange ePrice,
		PriceRange nPrice,
		FirmInit eFirm,
		FirmInit nFirm,
		CFirmInit cFirm,
		LaborerInit laborer,
		double meanInitAgeYears,
		double targetNStock,
		double meanSkillMale,
		double meanSkillFemale,
		double latitude,
		double longitude,
		double externalInflowPerStep,
		double immigrationThreshold,
		double laborShare,
		double bankProfitTaxRate,
		double nobleIncomeTaxRate,
		int retinueSize,
		double promotionRatio,
		int targetNobles,
		double researchInitialFraction,
		double researchCostScale,
		FertilityConfig fertility,
		int foundingLaborersPerNFirm) {

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
	 * The era whose {@link Era.Economy economic tuning} seeds {@link #DEFAULT}.
	 * The colony starts Medieval, so its economy supplies the era-specific
	 * defaults (prices, taxes, immigration, firm/laborer init, pool/nobles).
	 */
	private static final Era.Economy MEDIEVAL = Era.MEDIEVAL.economy();

	/** The original canonical run configuration. */
	public static final SimulationConfig DEFAULT = new SimulationConfig(
			"Dhenijansar",                         // settlementName
			LocalDate.of(1444, 12, 11),            // startDate
			25,                                    // durationYears
			1,                                     // numEFirms (founding count; the
			                                       //   ruler's dynamic provisioning
			                                       //   grows the sector from here)
			1,                                     // numNFirms (founding count)
			MEDIEVAL.ePrice(),
			MEDIEVAL.nPrice(),
			MEDIEVAL.eFirm(),
			MEDIEVAL.nFirm(),
			MEDIEVAL.cFirm(),
			MEDIEVAL.laborer(),
			35,                                    // meanInitAgeYears
			MEDIEVAL.targetNStock(),
			7,                                     // meanSkillMale
			5,                                     // meanSkillFemale
			51.5074,                               // latitude (London)
			-0.1278,                               // longitude (London)
			MEDIEVAL.externalInflowPerStep(),
			MEDIEVAL.immigrationThreshold(),
			MEDIEVAL.laborShare(),
			MEDIEVAL.bankProfitTaxRate(),
			MEDIEVAL.nobleIncomeTaxRate(),
			MEDIEVAL.retinueSize(),
			MEDIEVAL.promotionRatio(),
			MEDIEVAL.targetNobles(),
			0.9,                                   // researchInitialFraction (90%)
			1.0,                                   // researchCostScale
			FertilityConfig.DEFAULT,               // fertility (births on by default)
			30);                                   // foundingLaborersPerNFirm (size the
			                                       //   founding food sector to demand)
}
