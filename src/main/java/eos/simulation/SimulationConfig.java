package eos.simulation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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
 * @param numLaborers  number of laborers
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
 * @param meanSkill    mean of the household skill distribution at colony start;
 *                     a colony-start property (set on the {@code Settlement}) that
 *                     centers the skill spread of its founding and successor
 *                     households, hence their labor productivity
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
 * @param peasantReserveSize number of peasants the colony's pool is seeded with
 *                     (the standing reserve the Ruler feeds); 0 creates no pool
 *                     (the default), so the feature is opt-in per simulation
 */
@Builder(toBuilder = true)
public record SimulationConfig(
		String settlementName,
		LocalDate startDate,
		int durationYears,
		int numLaborers,
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
		double meanSkill,
		double latitude,
		double longitude,
		double externalInflowPerStep,
		double immigrationThreshold,
		double laborShare,
		double bankProfitTaxRate,
		double nobleIncomeTaxRate,
		int peasantReserveSize) {

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

	/** The original canonical run configuration. */
	public static final SimulationConfig DEFAULT = new SimulationConfig(
			"Eos",                                 // settlementName
			LocalDate.of(1444, 12, 11),            // startDate
			25,                                    // durationYears
			450,                                   // numLaborers
			10,                                    // numEFirms
			10,                                    // numNFirms
			new PriceRange(0.1, 5),                // ePrice
			new PriceRange(0.1, 5),                // nPrice
			new FirmInit(100, -1000, 40, 100, 30), // eFirm
			new FirmInit(100, -1000, 50, 100, 30), // nFirm
			new CFirmInit(500, 500, 0),            // cFirm
			new LaborerInit(0, 0, 100, 0.9),       // laborer
			35,                                    // meanInitAgeYears
			26,                                    // targetNStock
			5,                                     // meanSkill
			51.5074,                               // latitude (London)
			-0.1278,                               // longitude (London)
			0,                                     // externalInflowPerStep (closed)
			100,                                   // immigrationThreshold
			0.5,                                   // laborShare
			0.05,                                  // bankProfitTaxRate
			0.02,                                  // nobleIncomeTaxRate
			0);                                    // peasantReserveSize (no pool)
}
