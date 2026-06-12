package eos.agent.firm;

import eos.bank.Bank;
import eos.settlement.Settlement;
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
	 * @param bank
	 *            the bank at which this firm holds its accounts
	 * @param colony
	 *            the colony this firm belongs to
	 */
	public EFirm(double initCheckingBal, double initSavingsBal,
			double initOutput, double initWageBudget, int initCapital,
			CFirm[] capitalProducers, FirmConfig config, Bank bank,
			Settlement colony) {
		super("Enjoyment", initCheckingBal, initSavingsBal, initOutput,
				initWageBudget, initCapital, capitalProducers, config, bank,
				colony);
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
