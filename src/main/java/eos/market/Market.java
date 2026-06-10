package eos.market;

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
	 * Create a new market trading good
	 * 
	 * @param good
	 *            name of good to be traded in the market
	 */
	public Market(String good) {
		this.good = good;
	}

	/**
	 * Clear the market. Called by Economy.step() in each step.
	 */
	public abstract void clear();
}
