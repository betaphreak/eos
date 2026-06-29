package com.civstudio.agent;

import lombok.Builder;

/**
 * Tunable parameters of the ruler's <b>ever-normal {@link Granary}</b> — the food
 * buffer that stabilizes the necessity price by trading real stock against the market
 * within a band around the market's reference price (see {@code
 * docs/granary.md}). The granary buys a glut at the floor (so the
 * price cannot crash and starve firm revenue) and sells from stock into scarcity at
 * the ceiling (so the death-spiral spike is capped and more food reaches the market).
 * It acts on <b>quantity</b>, which is why it serves both price regimes a symmetric
 * price band cannot.
 *
 * <p>All values are placeholders pending calibration (Phase 1 establishes the band
 * that keeps the necessity price in range on the current colony).
 *
 * @param floorFactor
 *            the granary buys when the necessity price sits at or below this fraction
 *            of the market's {@linkplain
 *            com.civstudio.market.ConsumerGoodMarket#getInitialPrice() reference
 *            price} — low enough to leave normal price discovery alone, high enough to
 *            arrest a genuine deflationary glut before it crashes
 * @param ceilFactor
 *            the granary sells from stock when the necessity price sits at or above
 *            this fraction of the reference price — capping a scarcity spike at its
 *            source by putting real food on the market
 * @param targetDays
 *            the strategic reserve the granary aims to hold, in days of the colony's
 *            workforce consumption; once stock reaches this it stops buying (so a
 *            surplus past target is left to the market rather than hoarded), and it is
 *            the reserve later phases draw child relief and fission dowries from
 * @param perStepTradeCap
 *            the most necessity (units) the granary will buy or sell in a single step
 *            — a throttle against cornering the market or oscillating
 */
@Builder(toBuilder = true)
public record GranaryConfig(double floorFactor, double ceilFactor, int targetDays,
		double perStepTradeCap) {

	// the default band and reserve, referenced by DEFAULT (named so the @value javadoc
	// below resolves them). The band is wide enough to leave normal price oscillation
	// undisturbed (the model's own glut signal treats price < 0.3x reference as a crash)
	// while bracketing the extremes Phase 1 must prevent (a crash toward ~0.04x, a spike
	// past ~16x the reference).
	private static final double DEFAULT_FLOOR_FACTOR = 0.6;
	private static final double DEFAULT_CEIL_FACTOR = 2.0;
	private static final int DEFAULT_TARGET_DAYS = 60;
	private static final double DEFAULT_PER_STEP_TRADE_CAP = 100;

	/**
	 * Canonical defaults: buy below {@value #DEFAULT_FLOOR_FACTOR}&times; and sell above
	 * {@value #DEFAULT_CEIL_FACTOR}&times; the reference price, hold a
	 * {@value #DEFAULT_TARGET_DAYS}-day reserve, and trade at most
	 * {@value #DEFAULT_PER_STEP_TRADE_CAP} units a step. Placeholders pending
	 * calibration.
	 */
	public static final GranaryConfig DEFAULT = new GranaryConfig(DEFAULT_FLOOR_FACTOR,
			DEFAULT_CEIL_FACTOR, DEFAULT_TARGET_DAYS, DEFAULT_PER_STEP_TRADE_CAP);
}
