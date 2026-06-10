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
	 * @param config
	 *            tunable model parameters
	 */
	public EFirm(double initCheckingBal, double initSavingsBal,
			double initOutput, double initWageBudget, int initCapital,
			CFirm[] capitalProducers, FirmConfig config) {
		super("Enjoyment", initCheckingBal, initSavingsBal, initOutput,
				initWageBudget, initCapital, capitalProducers, config);
		product = new Enjoyment(0);
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
