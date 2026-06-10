package eos.agent.firm;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.good.Labor;

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
	protected double capacity;

	/**
	 *  output in the last step
	 */
	protected double output;

	/**
	 *  total wage budget in the last step
	 */
	protected double wageBudget;

	/**
	 *  wage (per worker) in the last step
	 */
	protected double wage;

	/**
	 *  total loan in the last step
	 */
	protected double loan;

	/**
	 *  revenue in the last step
	 */
	protected double revenue;

	/**
	 *  profit in the last step
	 */
	protected double profit;

	/**
	 *  marginal profit in the last step
	 */
	protected double marginalProfit;

	/**
	 *  cost of capital in the last step
	 */
	protected double capitalCost;

	/**
	 *  total cost in the last step
	 */
	protected double totalCost;

	/**
	 * Create a new firm.
	 * 
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            intial savings account balance
	 */
	public Firm(double initCheckingBal, double initSavingsBal) {
		super();
		
		// open a checking account and a savings account 
		Bank.openAcct(getID(), initCheckingBal, initSavingsBal);
		labor = new Labor(0);
	}

	/**
	 * Return output in the last step
	 * 
	 * @return output in the last step
	 */
	public double getOutput() {
		return output;
	}

	/**
	 * Return capacity in the last step
	 * 
	 * @return capacity in the last step
	 */
	public double getCapacity() {
		return capacity;
	}

	/**
	 * Return wage (per worker) in the last step
	 * 
	 * @return wage (per worker) in the last step
	 */
	public double getWage() {
		return wage;
	}

	/**
	 * Return loan in the last step
	 * 
	 * @return loan in the last step
	 */
	public double getLoan() {
		return loan;
	}

	/**
	 * Return revenue in the last step
	 * 
	 * @return revenue
	 */
	public double getRevenue() {
		return revenue;
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
	 * Return profit in the last step
	 * 
	 * @return profit in the last step
	 */
	public double getProfit() {
		return profit;
	}

	/**
	 * Return marginal profit in the last step
	 * 
	 * @return marginal profit in the last step
	 */
	public double getMarginalProfit() {
		return marginalProfit;

	}

	/**
	 * Return total capital cost in the last step
	 * 
	 * @return total capital cost
	 */
	public double getCapitalCost() {
		return capitalCost;
	}

	/**
	 * Return total cost in the last step
	 * 
	 * @return total cost
	 */
	public double getTotalCost() {
		return totalCost;
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
