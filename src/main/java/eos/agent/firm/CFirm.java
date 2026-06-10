package eos.agent.firm;

import eos.bank.Bank;
import eos.bank.Bank.Account;
import eos.economy.Economy;
import eos.good.Good;
import eos.market.CapitalMarket;
import eos.market.LaborMarket;

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
	 */
	public CFirm(double initCheckingBal, double initSavingsBal,
			double initWageBudget) {
		super(initCheckingBal, initSavingsBal);

		// we assume infinite capacity here
		// so we give A a very large value.
		A = 20000;

		beta = 0.5;
		this.price = INIT_CAPITAL_PRICE;
		wageBudget = initWageBudget;
		cMkt = (CapitalMarket) Economy.getMarket("Capital");
		lMkt = (LaborMarket) Economy.getMarket("Labor");
		lMkt.addEmployer(this, labor, initWageBudget);
	}

	/**
	 * Called by Economy.step() in each step
	 */
	public void act() {

		// Capital firms are not supposed to have loans in this
		// design. But if for some reason a firm has a positive
		// loan, pay back that loan.
		loan = -Bank.getBalance(getID(), Bank.SAVINGS);
		if (loan > 0)
			Bank.deposit(getID(), loan);

		capacity = convertToProduct(labor.getQuantity());
		wage = labor.getQuantity() > 0 ? wageBudget / labor.getQuantity() : 0;

		Account acct = Bank.getAcct(getID());
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
		return A * Math.pow(labor, beta);
	}

	/**
	 * Return a reference to <tt>good</tt> owned by the firm.
	 */
	public Good getGood(String good) {
		if (good.equals("Labor"))
			return labor;
		return null;
	}
}
