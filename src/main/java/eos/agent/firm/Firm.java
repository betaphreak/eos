package eos.agent.firm;

import java.util.Collections;
import java.util.Set;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.settlement.Settlement;
import eos.good.Labor;
import eos.skill.Skill;
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
	 * @param colony
	 *            the colony this firm belongs to
	 */
	public Firm(double initCheckingBal, double initSavingsBal, Bank bank,
			Settlement colony) {
		super(bank, colony);

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
	 * The skills a worker trains by performing this firm's labor: each step a
	 * worker is employed here, it gains experience in every skill returned (see
	 * {@link eos.market.LaborMarket#clear()}). Defaults to none — a firm whose
	 * work is not mapped to a skill grants no experience. Concrete firms override
	 * this (e.g. a necessity firm, subsistence agriculture, trains
	 * {@link Skill#PLANTS}).
	 *
	 * @return the skills this firm's labor trains (possibly empty)
	 */
	public Set<Skill> laborSkills() {
		return Collections.emptySet();
	}

	/**
	 * Return total labor cost in the last step
	 *
	 * @return total labor cost
	 */
	public double getLaborCost() {
		return wageBudget;
	}

	/**
	 * A concise, debug-friendly summary: name, id, alive status and the latest
	 * production/finance snapshot.
	 */
	@Override
	public String toString() {
		return String.format(
				"%s #%d [%s output=%.1f capacity=%.1f wage=%.2f revenue=%.2f profit=%.2f loan=%.2f labor=%.1f]",
				getName(), getID(), isAlive() ? "alive" : "dead", output,
				capacity, wage, revenue, profit, loan, labor.getQuantity());
	}

}
