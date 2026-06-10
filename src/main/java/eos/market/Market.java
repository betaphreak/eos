package eos.market;

import eos.economy.Economy;
import lombok.Getter;

/**
 * Parent class of all markets
 *
 * @author zhihongx
 *
 */
public abstract class Market {

	/**
	 *  name of the good to be traded in the market
	 */
	@Getter
	protected String good;

	/**
	 * the economy this market belongs to
	 */
	protected final Economy economy;

	/**
	 * Create a new market trading good
	 *
	 * @param good
	 *            name of good to be traded in the market
	 * @param economy
	 *            the economy this market belongs to
	 */
	public Market(String good, Economy economy) {
		this.good = good;
		this.economy = economy;
	}

	/**
	 * Clear the market. Called by Economy.newDay() in each step.
	 */
	public abstract void clear();
}
