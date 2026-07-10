package com.civstudio.agent.firm;

import lombok.Builder;

/**
 * Tunable model parameters for a {@link StrategicFirm}. Immutable.
 *
 * @param A              technology coefficient in the export production function
 *                       {@code A · L^beta}
 * @param beta           sensitivity of export output to labor (the power on L);
 *                       &lt; 1 gives diminishing returns to labor
 * @param exportPrice    external price earned per unit of the strategic good
 *                       exported out of the economy (the money flows into bank
 *                       equity)
 * @param wageBudget     fixed per-step wage budget the firm bids for labor; held
 *                       constant (rather than tied to revenue) so the firm wins a
 *                       steady amount of labor and exports steadily — the bank's
 *                       equity then grows by the export earnings net of this
 *                       budget each step. The same value seeds the firm's opening
 *                       checking balance so it can pay its first workers.
 */
@Builder(toBuilder = true)
public record StrategicFirmConfig(
		double A,
		double beta,
		double exportPrice,
		double wageBudget) {

	/** Canonical parameter values. */
	public static final StrategicFirmConfig DEFAULT =
			new StrategicFirmConfig(2, 0.5, 20.0, 40);
}
