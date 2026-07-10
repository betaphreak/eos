package com.civstudio.market;

import com.civstudio.settlement.Settlement;
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
	 * display name, defaulted from the traded good (e.g. "Enjoyment Market")
	 */
	@Getter
	protected final String name;

	/**
	 * the colony this market belongs to
	 */
	protected final Settlement colony;

	/**
	 * Create a new market trading good
	 *
	 * @param good
	 *            name of good to be traded in the market
	 * @param colony
	 *            the colony this market belongs to
	 */
	public Market(String good, Settlement colony) {
		this.good = good;
		this.name = good + " Market";
		this.colony = colony;
	}

	/**
	 * Clear the market. Called by Settlement.newDay() in each step.
	 */
	public abstract void clear();
}
