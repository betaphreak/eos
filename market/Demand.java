package eos.market;

/**
 * An interface for a demand function object
 * 
 * @author zhihongx
 * 
 */
public interface Demand {

	/**
	 * Return quantity demanded given <tt>price</tt>
	 * 
	 * @param price
	 * @return quantity demanded given <tt>price</tt>
	 */
	public double getDemand(double price);
}
