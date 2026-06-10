package eos.agent.firm;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.economy.Economy;
import eos.good.Labor;
import lombok.Getter;

/**
 * Parent class of all firms.
 * 
 * @author zhihongx
 * 
 */
public abstract class Firm extends Agent {

	/**
	 *  labor owned by the firm
	 */
	protected Labor labor;

	/**
	 *  max output the firm could produce with the current capital and labor
	 */
	@Getter
	protected double capacity;

	/**
	 *  output in the last step
	 */
	@Getter
	protected double output;

	/**
	 *  total wage budget in the last step
	 */
	protected double wageBudget;

	/**
	 *  wage (per worker) in the last step
	 */
	@Getter
	protected double wage;

	/**
	 *  total loan in the last step
	 */
	@Getter
	protected double loan;

	/**
	 *  revenue in the last step
	 */
	@Getter
	protected double revenue;

	/**
	 *  profit in the last step
	 */
	@Getter
	protected double profit;

	/**
	 *  marginal profit in the last step
	 */
	@Getter
	protected double marginalProfit;

	/**
	 *  cost of capital in the last step
	 */
	@Getter
	protected double capitalCost;

	/**
	 *  total cost in the last step
	 */
	@Getter
	protected double totalCost;

	/**
	 * Create a new firm.
	 * 
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            intial savings account balance
	 * @param bank
	 *            the bank at which this firm holds its accounts
	 * @param economy
	 *            the economy this firm belongs to
	 */
	public Firm(double initCheckingBal, double initSavingsBal, Bank bank,
			Economy economy) {
		super(bank, economy);

		// open a checking account and a savings account
		bank.openAcct(getID(), initCheckingBal, initSavingsBal);
		labor = new Labor(0);
	}

	/**
	 * Return amount of labor owned by the firm
	 * 
	 * @return amount of labor owned by the firm
	 */
	public double getLabor() {
		return labor.getQuantity();
	}

	/**
	 * Return total labor cost in the last step
	 * 
	 * @return total labor cost
	 */
	public double getLaborCost() {
		return wageBudget;
	}

}
