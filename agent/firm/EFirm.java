package eos.agent.firm;

import eos.good.Enjoyment;
import eos.good.Good;

/**
 * Enjoyment Firm
 * 
 * @author zhihongx
 * 
 */
public class EFirm extends ConsumerGoodFirm {

	/**
	 * Create a new enjoyment firm
	 * 
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 * @param initOutput
	 *            initial output
	 * @param initWageBudget
	 *            initial wage budget
	 * @param initCapital
	 *            initial amount of capital
	 * @param capitalProducers
	 *            array of capital good producers
	 */
	public EFirm(double initCheckingBal, double initSavingsBal,
			double initOutput, double initWageBudget, int initCapital,
			CFirm[] capitalProducers) {
		super("Enjoyment", initCheckingBal, initSavingsBal, initOutput,
				initWageBudget, initCapital, capitalProducers);
		A = 2; // technology coefficient in production function
		beta = 0.5; // sensitivity of output to labor
		phi = 0.5; // sensitivity of output to marginal profit
		lambda = 0.2; // sensitivity of wage to money flow gap
		product = new Enjoyment(0);
		
		// minimal capacity utilization to allow capital expansion
		eUtilThreshold = 0.9; 
		// minimal capacity utilization to allow capital replacement
		rUtilThreshold = 0.75;
	}

	/**
	 * Return good with name <tt>good</tt>.
	 */
	public Good getGood(String good) {
		if (good.equals("Enjoyment"))
			return product;
		else
			return null;
	}
}
