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
 * @param lambda         sensitivity of wage to money flow gap (legacy
 *                       cash-flow-gap wage-budget rule)
 * @param laborShare     if &gt; 0, the firm budgets a fixed share of its
 *                       revenue for wages (newWageBudget = laborShare ·
 *                       revenue) instead of the cash-flow-gap rule, so total
 *                       wage spending — and the uniform market wage
 *                       totalBudget/N — scales with the colony as population
 *                       and nominal demand grow; 0 keeps the legacy rule
 * @param eUtilThreshold minimal capacity utilization to allow capital expansion
 * @param rUtilThreshold minimal capacity utilization to allow capital
 *                       replacement
 */
@Builder(toBuilder = true)
public record FirmConfig(
		double A,
		double beta,
		double phi,
		double lambda,
		double laborShare,
		double eUtilThreshold,
		double rUtilThreshold) {

	/** The original hard-coded parameter values (legacy wage-budget rule). */
	public static final FirmConfig DEFAULT =
			new FirmConfig(2, 0.5, 0.5, 0.2, 0, 0.9, 0.75);
}
