package eos.agent.laborer;

import eos.good.RationSize;
import lombok.Builder;

/**
 * Tunable model parameters for a {@link Laborer}. Immutable; share one instance
 * across a homogeneous population or build per-laborer instances for a
 * heterogeneous one.
 *
 * @param baseSavingsToIncomeRatio base savings to wage ratio
 * @param eatAmt                   quantity of necessity consumed in each step — a
 *                                 worker eats the {@link RationSize#FINE} ration
 * @param epsilon                  sensitivity of target savings to real interest
 *                                 rate
 * @param upsilon                  max percentage change in consumption allowed in
 *                                 each step
 */
@Builder(toBuilder = true)
public record LaborerConfig(
		double baseSavingsToIncomeRatio,
		double eatAmt,
		double epsilon,
		double upsilon) {

	/** The canonical parameter values (a worker eats the {@link RationSize#FINE} ration). */
	public static final LaborerConfig DEFAULT =
			new LaborerConfig(10, RationSize.FINE.perDay(), 0.1, 0.04);
}
