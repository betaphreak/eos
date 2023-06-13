package eos.market;

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
	 * Return name of the good traded in the market
	 * 
	 * @return name of the good traded in the market
	 */
	public String getGood() {
		return good;
	}

	/**
	 * Clear the market. Called by Economy.step() in each step.
	 */
	public abstract void clear();
}
