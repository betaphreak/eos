package eos.market;

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
 */
@Builder(toBuilder = true)
public record WeddingConfig(int capacity, double baseCost, double skillExponent) {

	/** Canonical defaults: 4 weddings/weekend, a gently quadratic bride-price. */
	public static final WeddingConfig DEFAULT = new WeddingConfig(4, 2.0, 2.0);

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
}
