package com.civstudio.agent.firm;

import java.util.Set;

import com.civstudio.tech.Sector;
import com.civstudio.bank.Bank;
import com.civstudio.bank.Account;
import com.civstudio.settlement.Settlement;
import com.civstudio.good.Good;
import com.civstudio.market.CapitalMarket;
import com.civstudio.market.LaborMarket;
import com.civstudio.skill.Skill;

/**
 * Capital firm
 * 
 * @author zhihongx
 * 
 */
public class CFirm extends Firm {

	/**
	 * life of capital (max number of time steps capital may be used
	 */
	public static final int CAPITAL_LIFE = 30;

	/**
	 * initial capital price; for now capital price is fixed at this level
	 */

	public static final double INIT_CAPITAL_PRICE = 1.2;

	/**
	 * technology coefficient in production function
	 */
	private double A;

	/**
	 * sensitivity of output to labor
	 */
	private double beta;

	/**
	 * capital market
	 */
	private final CapitalMarket cMkt;

	/**
	 * labor market
	 */
	private final LaborMarket lMkt;

	/**
	 * capital price (fixed for now)
	 */
	private double price;

	/**
	 * Create a new capital firm
	 * 
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 * @param initWageBudget
	 *            initial wage budget
	 * @param bank
	 *            the bank at which this firm holds its accounts
	 * @param colony
	 *            the colony this firm belongs to
	 */
	public CFirm(double initCheckingBal, double initSavingsBal,
			double initWageBudget, Bank bank, Settlement colony) {
		super(initCheckingBal, initSavingsBal, bank, colony);
		setName("Capital Firm");

		// we assume infinite capacity here
		// so we give A a very large value.
		A = 20000;

		beta = 0.5;
		this.price = INIT_CAPITAL_PRICE;
		wageBudget = initWageBudget;
		cMkt = (CapitalMarket) colony.getMarket("Capital");
		lMkt = (LaborMarket) colony.getMarket("Labor");
		lMkt.addEmployer(this, labor, initWageBudget);
	}

	/**
	 * Called by Settlement.newDay() in each step
	 */
	public void act() {
		Bank bank = getBank();

		// Capital firms are not supposed to have loans in this
		// design. But if for some reason a firm has a positive
		// loan, pay back that loan.
		loan = -bank.getSavings(getID());
		if (loan > 0)
			bank.deposit(getID(), loan);

		capacity = convertToProduct(labor.getQuantity());
		wage = labor.getQuantity() > 0 ? wageBudget / labor.getQuantity() : 0;

		Account acct = bank.getAcct(getID());
		revenue = acct.priIC;
		output = revenue / price;
		wageBudget = revenue - loan; // set new wage budget

		// post capital sell offer
		cMkt.addSellOffer(this, price, (int) capacity);

		// post wage budget to labor market
		lMkt.addEmployer(this, labor, wageBudget);

		acct.priIC = 0;
		labor.decrease(labor.getQuantity()); // clear unused labor
	}

	/**
	 * Return output given <tt>labor</tt> amount of labor
	 * 
	 * @param labor
	 *            amount of labor
	 * @return output given <tt>labor</tt> amount of labor
	 */
	public double convertToProduct(double labor) {
		return A * getColony().getTechMultiplier(sector()) * Math.pow(labor, beta);
	}

	/** A capital firm produces in the {@link Sector#CAPITAL} sector. */
	@Override
	public Sector sector() {
		return Sector.CAPITAL;
	}

	/**
	 * Return a reference to <tt>good</tt> owned by the firm.
	 */
	public Good getGood(String good) {
		if (good.equals("Labor"))
			return labor;
		return null;
	}

	/** Producing capital goods (machines/tools) trains {@link Skill#CRAFTING}. */
	@Override
	public Set<Skill> laborSkills() {
		return Set.of(Skill.CRAFTING);
	}
}
