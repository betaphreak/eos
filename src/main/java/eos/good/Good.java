package eos.good;

/**
 * Parent class of all goods.
 * 
 * @author zhihongx
 * 
 */
public abstract class Good {

	/**
	 *  quantity of the good
	 */
	protected double quantity;

	/**
	 *  name of the good
	 */
	private String className;

	/**
	 * Create <tt>quantity</tt> amount of a new good
	 * 
	 * @param quantity
	 */
	public Good(double quantity) {
		this.quantity = quantity;
	}

	/**
	 * Return quantity of the good
	 * 
	 * @return quantity of the good
	 */
	public double getQuantity() {
		return quantity;
	}

	/**
	 * Increase the quantity of the good by <tt>amount</tt>
	 * 
	 * @param amount
	 */
	public void increase(double amount) {
		if (amount < 0)
			throw new RuntimeException("Amount must be >= 0");
		quantity += amount;
	}

	/**
	 * Decrease the quantity of the good by <tt>amount</tt>. If quantity is less
	 * than <tt>amount</tt>, quantity is reduced to zero.
	 * 
	 * @param amount
	 * @return actual amount by which the quantity is reduced
	 */
	public double decrease(double amount) {
		if (amount < 0)
			throw new RuntimeException("Amount must be >= 0");
		double ret = quantity > amount ? amount : quantity;
		quantity -= ret;
		return ret;
	}

	/**
	 * Return the name of the good
	 * 
	 * @return the name of the good
	 */
	public final String getName() {
		if (className != null)
			return className;
		else {
			className = this.getClass().getSimpleName();
			return className;
		}
	}
}
