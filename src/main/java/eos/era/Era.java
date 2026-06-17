package eos.era;

import eos.simulation.SimulationConfig.CFirmInit;
import eos.simulation.SimulationConfig.FirmInit;
import eos.simulation.SimulationConfig.LaborerInit;
import eos.simulation.SimulationConfig.PriceRange;
import lombok.Builder;

/**
 * The technological eras a colony advances through, mirroring the entries in
 * {@code src/main/resources/eras.json}. Each constant carries its {@code eraId}
 * (the stable numeric key the JSON keys on, equal to the constant's ordinal) and
 * an {@link Economy} bundling the era's economic tuning — the run-level values
 * that were previously hard-wired into {@code SimulationConfig.DEFAULT}.
 * <p>
 * Only {@link #MEDIEVAL} is calibrated today (its {@code Economy} holds the
 * original default values, which {@code SimulationConfig.DEFAULT} now seeds
 * from); every other era's {@link #economy()} is {@code null} pending
 * calibration. Era-advancement during a run — the colony tracking its current
 * era and reading the matching {@code Economy} live each step — is future work;
 * for now {@code Era} is simply the home of these per-era values.
 */
public enum Era {

	PREHISTORIC(0, null),
	ANCIENT(1, null),
	CLASSICAL(2, null),
	MEDIEVAL(3, new Economy(
			new PriceRange(0.1, 5),                // ePrice
			new PriceRange(0.1, 5),                // nPrice
			new FirmInit(100, -1000, 40, 100, 30), // eFirm
			new FirmInit(100, -1000, 50, 100, 30), // nFirm
			new CFirmInit(500, 500, 0),            // cFirm
			new LaborerInit(0, 0, 100, 0.9),       // laborer
			26,                                    // targetNStock
			0,                                     // externalInflowPerStep (closed)
			100,                                   // immigrationThreshold
			0.5,                                   // laborShare
			0.05,                                  // bankProfitTaxRate
			0.02,                                  // nobleIncomeTaxRate
			900,                                   // retinueSize
			0.45,                                  // promotionRatio
			5)),                                   // targetNobles
	RENAISSANCE(4, null),
	INDUSTRIAL(5, null),
	ATOMIC(6, null),
	INFORMATION(7, null),
	NANOTECH(8, null),
	TRANSHUMAN(9, null);

	private final int eraId;
	private final Economy economy;

	Era(int eraId, Economy economy) {
		this.eraId = eraId;
		this.economy = economy;
	}

	/** The stable numeric id this era is keyed on in {@code eras.json}. */
	public int eraId() {
		return eraId;
	}

	/**
	 * The era's economic tuning, or {@code null} if the era is not yet
	 * calibrated (currently only {@link #MEDIEVAL} is).
	 */
	public Economy economy() {
		return economy;
	}

	/**
	 * The era with the given {@code eraId}.
	 *
	 * @throws IllegalArgumentException if no era has that id
	 */
	public static Era fromId(int eraId) {
		for (Era era : values())
			if (era.eraId == eraId)
				return era;
		throw new IllegalArgumentException("no era with eraId " + eraId);
	}

	/**
	 * The economic parameters that vary by era — the run-level economic tuning
	 * previously hard-wired into {@code SimulationConfig.DEFAULT}. Immutable;
	 * reuses {@code SimulationConfig}'s value records for the structured fields.
	 *
	 * @param ePrice               initial price bounds for the enjoyment market
	 * @param nPrice               initial price bounds for the necessity market
	 * @param eFirm                initial state of each enjoyment firm
	 * @param nFirm                initial state of each necessity firm
	 * @param cFirm                initial state of the capital firm
	 * @param laborer              initial state of each laborer
	 * @param targetNStock         target necessity stock every laborer accumulates
	 * @param externalInflowPerStep money entering the colony from outside each step
	 *                             (0 leaves the colony closed)
	 * @param immigrationThreshold equity level at which the bank funds one immigrant
	 * @param laborShare           fraction of revenue each consumer-good firm budgets
	 *                             for wages
	 * @param bankProfitTaxRate    fraction of each bank's distributable profit the
	 *                             Ruler taxes each step
	 * @param nobleIncomeTaxRate   fraction of each noble's per-step income the Ruler
	 *                             taxes each step
	 * @param retinueSize      number of peasants a pooled colony is seeded with
	 * @param promotionRatio       fraction of the pool promoted into laborer households
	 * @param targetNobles         size of the aristocracy maintained by ennoblement
	 */
	@Builder(toBuilder = true)
	public record Economy(
			PriceRange ePrice,
			PriceRange nPrice,
			FirmInit eFirm,
			FirmInit nFirm,
			CFirmInit cFirm,
			LaborerInit laborer,
			double targetNStock,
			double externalInflowPerStep,
			double immigrationThreshold,
			double laborShare,
			double bankProfitTaxRate,
			double nobleIncomeTaxRate,
			int retinueSize,
			double promotionRatio,
			int targetNobles) {
	}
}
