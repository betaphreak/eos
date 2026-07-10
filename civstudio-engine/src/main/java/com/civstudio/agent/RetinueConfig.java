package com.civstudio.agent;

import com.civstudio.good.RationSize;

import lombok.Builder;

/**
 * Tunable parameters of the peasant {@link Retinue}: how deep a food larder the pool
 * keeps, how lean a ration its reserve eats, and how large a price-sensitive money
 * budget it spends restocking that larder. These three together decide whether a
 * colony's standing reserve can <b>sustain itself on the market</b> once its opening
 * larder is gone — the difference between a colony that holds its population and one
 * that starves its reserve into collapse (see {@code docs/peasant-pool.md}).
 *
 * @param bufferDays
 *            days of food the pool keeps in its larder per peasant — both the size of
 *            the seed larder (so a colony starts well-provisioned) and the daily refill
 *            target (so the pool restocks to stay full). A deep buffer rides out supply
 *            shocks but, if too deep, masks the pool's market demand until it empties as
 *            a sudden shock; a shallow buffer makes the pool buy steadily from day one.
 * @param reliefBudgetPerPeasant
 *            relief food the pool buys per peasant per step, as a money (copper) budget
 *            so its demand is price-sensitive (quantity = budget/price). Must comfortably
 *            cover the {@code reliefRation} at the prevailing necessity price for the
 *            reserve to feed itself off the market; sized too tight, the reserve starves
 *            the moment it must buy rather than eat from its larder.
 * @param reliefRation
 *            the daily ration each pooled peasant eats — poor relief, leaner than a
 *            working laborer's, so the extra mouths don't starve the workforce out of
 *            the food market
 */
@Builder(toBuilder = true)
public record RetinueConfig(int bufferDays, double reliefBudgetPerPeasant,
		RationSize reliefRation) {

	// the default per-peasant larder depth and relief budget, referenced by DEFAULT
	// (named constants so the @value javadoc below resolves them)
	private static final int DEFAULT_BUFFER_DAYS = 150;
	private static final double DEFAULT_RELIEF_BUDGET = 0.6;

	/**
	 * Canonical defaults: a deep {@value #DEFAULT_BUFFER_DAYS}-day larder, a relief
	 * budget of {@value #DEFAULT_RELIEF_BUDGET} copper/peasant/step, and the {@link
	 * RationSize#SIMPLE} relief ration. (These reproduce the historical hard-coded
	 * values; the calibrated values for a self-sustaining colony are applied per run.)
	 */
	public static final RetinueConfig DEFAULT = new RetinueConfig(
			DEFAULT_BUFFER_DAYS, DEFAULT_RELIEF_BUDGET, RationSize.SIMPLE);
}
