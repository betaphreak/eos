package com.civstudio.market;

import lombok.Builder;

/**
 * Tunable parameters of the {@link WeddingMarket}: how many weddings can be
 * solemnized per weekend and how the bride-price scales with the spouse's skill.
 * <p>
 * The cost is deliberately <b>non-linear</b> in the spouse's skill — an abler
 * spouse costs disproportionately more — so the social hierarchy sorts itself out
 * by wealth: rich nobles can afford the ablest peasants while a laborer can only
 * afford a modest match (and the ruler, who weds first, takes the very best). The
 * curve is {@code baseCost · (1 + overallLevel)^skillExponent}.
 *
 * @param capacity
 *            maximum number of weddings per weekend (the church can only marry so
 *            many couples in a day)
 * @param baseCost
 *            bride-price of a wholly unskilled spouse (overall level 0), in copper
 * @param skillExponent
 *            exponent of the skill term ({@code > 1} makes the price super-linear
 *            in skill)
 * @param immigrantCostFactor
 *            multiple of {@link #baseCost} the colony pays (in gold, destroyed) to
 *            recruit one immigrant into the peasant pool on a weekend whose wedding
 *            demand went unmet (see {@link #immigrantCost()}); ties the recruitment
 *            cost to the bride-price scale
 */
@Builder(toBuilder = true)
public record WeddingConfig(int capacity, double baseCost, double skillExponent,
		double immigrantCostFactor) {

	/**
	 * Canonical defaults: 4 weddings/weekend, a gently quadratic bride-price, and an
	 * immigrant recruitment cost of {@code 100 × baseCost} (200 copper ≈ 0.17 gold).
	 */
	public static final WeddingConfig DEFAULT = new WeddingConfig(4, 2.0, 2.0, 100.0);

	/**
	 * The bride-price of a spouse with the given overall skill level, in copper.
	 * Non-linear in skill (see the class doc): {@code baseCost · (1 +
	 * overallLevel)^skillExponent}.
	 *
	 * @param spouseOverallLevel
	 *            the spouse's overall skill level (the mean of its twelve skills)
	 * @return the bride-price in copper
	 */
	public double costFor(int spouseOverallLevel) {
		return baseCost * Math.pow(1 + spouseOverallLevel, skillExponent);
	}

	/**
	 * The cost, in copper, of recruiting one immigrant into the peasant pool —
	 * {@link #immigrantCostFactor} × {@link #baseCost}. Paid (and destroyed) from
	 * the ruler's gold treasury when a weekend's wedding demand goes unmet.
	 *
	 * @return the immigrant recruitment cost in copper
	 */
	public double immigrantCost() {
		return immigrantCostFactor * baseCost;
	}
}
