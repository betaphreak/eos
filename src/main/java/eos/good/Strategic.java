package eos.good;

/**
 * Strategic good
 *
 * @author zhihongx
 *
 */
public class Strategic extends ConsumerGood {

	/**
	 * Create quantity of strategic
	 *
	 * @param quantity
	 */
	public Strategic(double quantity) {
		super(quantity, ResourceType.STRATEGIC);
	}

}
