package eos.agent.firm;

import java.util.Collections;
import java.util.Set;

import eos.agent.Agent;
import eos.agent.Property;
import eos.bank.Bank;
import eos.calendar.DayType;
import eos.settlement.Settlement;
import eos.good.Labor;
import eos.skill.Skill;
import lombok.Getter;

/**
 * Parent class of all firms. A firm is a {@link Property}: its owner (a noble)
 * draws a dividend from its positive profit each step.
 *
 * @author zhihongx
 *
 */
public abstract class Firm extends Agent implements Property {

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
	 * The productive {@link eos.tech.Sector} this firm's output belongs to, used to
	 * scale its production by the colony's per-sector tech multiplier (see
	 * {@link eos.settlement.Settlement#getTechMultiplier} and the firms' effective-A
	 * production). Defaults to {@code null} — no sector, so no tech scaling — which is
	 * correct for the labor-only {@link BuilderFirm}; the production firms override it.
	 *
	 * @return this firm's sector, or {@code null} if it has none
	 */
	public eos.tech.Sector sector() {
		return null;
	}

	/**
	 * Whether this firm operates — hires labor and so produces — on the given
	 * kind of day. By default a firm runs only on {@link DayType#WORKDAY
	 * workdays}; the weekly day of rest (Sunday) and feast days are days off.
	 * Subclasses widen this: an {@link EFirm enjoyment firm} also runs on the
	 * {@link DayType#WEEKEND weekend}, and the noble-staffed {@link StrategicFirm
	 * export firm} also runs on {@link DayType#HOLIDAY feast days} (its nobles
	 * keep working when the commoners rest). On a day it does not operate, the
	 * firm posts no wage demand to the labor market (see {@link
	 * eos.market.LaborMarket#addEmployer}), so it hires no one, pays no wages and
	 * produces nothing that step.
	 *
	 * @param day
	 *            the kind of day
	 * @return true if the firm operates on that kind of day
	 */
	public boolean operatesOn(DayType day) {
		return day == DayType.WORKDAY;
	}

	/**
	 * Return total labor cost in the last step
	 *
	 * @return total labor cost
	 */
	public double getLaborCost() {
		return wageBudget;
	}

	// --- Property: the firm is an asset its owner draws a dividend from ---

	/**
	 * {@inheritDoc} A firm's distributable profit is its last step's profit floored
	 * at 0 — and 0 once it is dissolved (dead), so a defunct firm pays no dividend
	 * and its (closed) account is never touched.
	 */
	@Override
	public double distributableProfit() {
		return isAlive() ? Math.max(0, profit) : 0;
	}

	/** {@inheritDoc} A firm pays a dividend out of its own checking account. */
	@Override
	public void disburse(double amount) {
		getBank().withdraw(getID(), amount);
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
