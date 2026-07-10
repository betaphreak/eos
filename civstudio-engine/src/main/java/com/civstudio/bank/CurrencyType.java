package com.civstudio.bank;

import com.civstudio.settlement.Settlement;

/**
 * The unit of currency a {@link Bank} denominates its accounts in. A colony may
 * run banks in different currencies — e.g. a copper bank for commoners and a
 * silver bank for nobles (see {@code BimetallicEconomy}).
 * <p>
 * The colony has a <b>fixed exchange rate</b> between the three metals, given by
 * each one's value in <b>copper</b> (the base unit, in which prices are quoted):
 * a piece of silver is worth {@value #SILVER_IN_COPPER} copper and a piece of
 * gold {@value #GOLD_IN_COPPER} copper. These are historically plausible
 * 15th-century ratios — gold:silver ≈ 12:1 (the European ratio of the period)
 * and silver:copper ≈ 100:1 (an approximate metal-value ratio).
 * <p>
 * Because the rate is fixed and freely convertible, the currencies are fungible,
 * so all internal accounting stays in copper; the rate is used to convert any
 * amount specified in another currency into copper and to <b>display</b> balances
 * in a bank's own currency. {@link #toCopper(double)} / {@link
 * #fromCopper(double)} / {@link #convert(double, CurrencyType, CurrencyType)} do
 * the conversions (see also {@link Settlement#convert}).
 */
public enum CurrencyType {

	/** Copper — the lowest-value currency and the base unit, used by commoners. */
	COPPER(1),

	/** Silver — worth {@value #SILVER_IN_COPPER} copper, used by nobles. */
	SILVER(100),

	/** Gold — worth {@value #GOLD_IN_COPPER} copper (the highest-value metal). */
	GOLD(1200);

	/** Pieces of copper one piece of silver is worth (silver:copper ≈ 100:1). */
	public static final double SILVER_IN_COPPER = 100;

	/** Pieces of copper one piece of gold is worth (gold:silver ≈ 12:1). */
	public static final double GOLD_IN_COPPER = 1200;

	// this currency's fixed value in copper (the base unit)
	private final double copperValue;

	CurrencyType(double copperValue) {
		this.copperValue = copperValue;
	}

	/**
	 * This currency's fixed value in copper (copper = 1).
	 *
	 * @return the value of one unit of this currency in copper
	 */
	public double copperValue() {
		return copperValue;
	}

	/**
	 * Convert <tt>amount</tt> of this currency into copper.
	 *
	 * @param amount
	 *            an amount in this currency
	 * @return the equivalent amount of copper
	 */
	public double toCopper(double amount) {
		return amount * copperValue;
	}

	/**
	 * Convert <tt>copperAmount</tt> copper into this currency.
	 *
	 * @param copperAmount
	 *            an amount of copper
	 * @return the equivalent amount in this currency
	 */
	public double fromCopper(double copperAmount) {
		return copperAmount / copperValue;
	}

	/**
	 * Convert <tt>amount</tt> from currency <tt>from</tt> into currency
	 * <tt>to</tt> at the fixed rate.
	 *
	 * @param amount
	 *            an amount in currency <tt>from</tt>
	 * @param from
	 *            the source currency
	 * @param to
	 *            the target currency
	 * @return the equivalent amount in currency <tt>to</tt>
	 */
	public static double convert(double amount, CurrencyType from,
			CurrencyType to) {
		return to.fromCopper(from.toCopper(amount));
	}
}
