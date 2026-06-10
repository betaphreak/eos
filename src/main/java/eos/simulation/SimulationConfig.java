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
 * @param stepSize     number of steps (days) between two printer outputs
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
 */
@Builder(toBuilder = true)
public record SimulationConfig(
		int stepSize,
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
		LaborerInit laborer) {

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
			50,                                    // stepSize (days between prints)
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
			new LaborerInit(0, 0, 100, 0.9));      // laborer
}
