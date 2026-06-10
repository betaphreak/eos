package eos.agent.laborer;

import lombok.Builder;

/**
 * Tunable model parameters for a {@link Laborer}. Immutable; share one instance
 * across a homogeneous population or build per-laborer instances for a
 * heterogeneous one.
 *
 * @param targetNStock             target necessity stock
 * @param baseSavingsToIncomeRatio base savings to wage ratio
 * @param eatAmt                   quantity of necessity consumed in each step
 * @param epsilon                  sensitivity of target savings to real interest
 *                                 rate
 * @param upsilon                  max percentage change in consumption allowed in
 *                                 each step
 */
@Builder(toBuilder = true)
public record LaborerConfig(
		double targetNStock,
		double baseSavingsToIncomeRatio,
		double eatAmt,
		double epsilon,
		double upsilon) {

	/** The original hard-coded parameter values. */
	public static final LaborerConfig DEFAULT =
			new LaborerConfig(26, 10, 1.0, 0.1, 0.04);
}
