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
 *                       totalBudget/N — scales with the economy as population
 *                       and nominal demand grow; 0 keeps the legacy rule
 * @param eUtilThreshold minimal capacity utilization to allow capital expansion
 * @param rUtilThreshold minimal capacity utilization to allow capital
 *                       replacement
 * @param avgProfitWin   time window within which average profit is computed
 * @param warmupDays     number of initial steps (days) the economy is given to
 *                       reach steady state before the aggressive
 *                       capital-purchase nudges below are allowed to fire; until
 *                       then the rolling average profit they key off of is not
 *                       yet meaningful
 * @param capitalNudgeProfitFactor multiple of average (absolute) profit beyond
 *                       which a firm is nudged to buy one extra machine on a
 *                       profit spike, or to buy one fewer on a deep loss; only
 *                       after {@code warmupDays}
 * @param capitalNudgeUtilThreshold lowered capacity-utilization bar (vs. the
 *                       normal {@code eUtilThreshold}) at which the profit-spike
 *                       nudge is allowed to add capital
 */
@Builder(toBuilder = true)
public record FirmConfig(
		double A,
		double beta,
		double phi,
		double lambda,
		double laborShare,
		double eUtilThreshold,
		double rUtilThreshold,
		int avgProfitWin,
		int warmupDays,
		double capitalNudgeProfitFactor,
		double capitalNudgeUtilThreshold) {

	/** The original hard-coded parameter values (legacy wage-budget rule). */
	public static final FirmConfig DEFAULT =
			new FirmConfig(2, 0.5, 0.5, 0.2, 0, 0.9, 0.75, 1000, 2000, 5, 0.8);
}
