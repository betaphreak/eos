package eos.agent.firm;

import eos.bank.Bank;
import eos.economy.Economy;
import eos.good.Good;
import eos.good.Necessity;

/**
 * Necessity Firm
 * 
 * @author zhihongx
 * 
 */
public class NFirm extends ConsumerGoodFirm {

	/**
	 * Create a new necessity firm
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
	 * @param bank
	 *            the bank at which this firm holds its accounts
	 * @param economy
	 *            the economy this firm belongs to
	 */
	public NFirm(double initCheckingBal, double initSavingsBal,
			double initOutput, double initWageBudget, int initCapital,
			CFirm[] capitalProducers, FirmConfig config, Bank bank,
			Economy economy) {
		super("Necessity", initCheckingBal, initSavingsBal, initOutput,
				initWageBudget, initCapital, capitalProducers, config, bank,
				economy);
		product = new Necessity(0);
	}

	/**
	 * Return good with name <tt>good</tt>.
	 */
	public Good getGood(String good) {
		if (good.equals("Necessity"))
			return product;
		else
			return null;
	}
}
