package eos.simulation;

/**
 * Run-level configuration for a simulation: step counts, population sizes,
 * market price bounds, and the initial state of each agent type. Immutable;
 * {@link #DEFAULT} holds the canonical values shared by the bundled
 * simulations. Build a modified instance to script a different run without
 * editing source.
 *
 * @param stepSize    number of steps between two printer outputs
 * @param numStep     number of steps to run
 * @param numLaborers number of laborers
 * @param numEFirms   number of enjoyment firms
 * @param numNFirms   number of necessity firms
 * @param ePrice      initial price bounds for the enjoyment market
 * @param nPrice      initial price bounds for the necessity market
 * @param eFirm       initial state of each enjoyment firm
 * @param nFirm       initial state of each necessity firm
 * @param cFirm       initial state of the capital firm
 * @param laborer     initial state of each laborer
 */
public record SimulationConfig(
		int stepSize,
		int numStep,
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
	public record PriceRange(double min, double max) {
	}

	/** Initial state of a consumer-good firm. */
	public record FirmInit(
			double checking,
			double savings,
			double output,
			double wageBudget,
			int capital) {
	}

	/** Initial state of the capital firm. */
	public record CFirmInit(
			double wageBudget,
			double checking,
			double savings) {
	}

	/** Initial state of a laborer. */
	public record LaborerInit(
			double e,
			double checking,
			double savings,
			double savingsRate) {
	}

	/** The original hard-coded run configuration. */
	public static final SimulationConfig DEFAULT = new SimulationConfig(
			50,                                    // stepSize
			10000,                                 // numStep
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
