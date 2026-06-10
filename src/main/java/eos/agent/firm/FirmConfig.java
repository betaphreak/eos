package eos.agent.firm;

import lombok.Builder;

/**
 * Tunable model parameters for a {@link ConsumerGoodFirm}. Immutable; share one
 * instance across a homogeneous population or build per-firm instances for a
 * heterogeneous one.
 *
 * @param A              technology coefficient in the production function
 * @param beta           sensitivity of output to labor (power on L in the
 *                       production function)
 * @param phi            sensitivity of output to marginal profit
 * @param lambda         sensitivity of wage to money flow gap
 * @param eUtilThreshold minimal capacity utilization to allow capital expansion
 * @param rUtilThreshold minimal capacity utilization to allow capital
 *                       replacement
 * @param avgProfitWin   time window within which average profit is computed
 */
@Builder(toBuilder = true)
public record FirmConfig(
		double A,
		double beta,
		double phi,
		double lambda,
		double eUtilThreshold,
		double rUtilThreshold,
		int avgProfitWin) {

	/** The original hard-coded parameter values. */
	public static final FirmConfig DEFAULT =
			new FirmConfig(2, 0.5, 0.5, 0.2, 0.9, 0.75, 1000);
}
