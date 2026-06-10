package eos.agent.firm;

import eos.bank.Bank;
import eos.bank.Bank.Account;
import eos.economy.Economy;
import eos.good.Capital;
import eos.good.Good;
import eos.market.CapitalMarket;
import eos.market.ConsumerGoodMarket;
import eos.market.LaborMarket;
import eos.util.Averager;

/**
 * A consumer good firm implements an enjoyment firm or a necessity firm.
 * 
 * @author zhihongx
 * 
 */
public abstract class ConsumerGoodFirm extends Firm {

	// time window within which average profit is computed
	private static final int AVG_PROFIT_WIN = 1000;

	/*******************************************************
	 * These must be initialized by the subclass
	 * ------------------------------------------------------
	 */
	
	/**
	 * technology coefficient in the production function
	 */
	protected double A;

	/**
	 * sensitivity of output to labor (power on L in the production function
	 */
	protected double beta;

	/**
	 * sensitivity of output to marginal profit
	 */
	protected double phi;

	/**
	 * sensitivity of wage to money flow gap
	 */
	protected double lambda;

	/**
	 * minimal capacity utilization to allow capital expansion
	 */
	protected double eUtilThreshold;

	/**
	 * minimal capacity utilization to allow capital replacement
	 */
	protected double rUtilThreshold;

	/**
	 * product the firm is producing/selling (enjoyment or necessity)
	 */
	protected Good product;
	/*******************************************************/

	/**
	 * capital owned by the firm
	 */
	private final Capital capital;

	/**
	 * product market
	 */
	private final ConsumerGoodMarket pMkt;

	/**
	 * capital market
	 */
	private final CapitalMarket cMkt;

	/**
	 * labor market
	 */
	private final LaborMarket lMkt;

	/**
	 * quantity of capital
	 */
	private double capitalQty;

	/**
	 * present value of capital
	 */
	private double capitalVal;

	/**
	 * used to calculate average profit
	 */
	private Averager pfAvger;

	/**
	 * Create a new consumer good firm
	 * 
	 * @param productName
	 *            name of the product
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
	public ConsumerGoodFirm(String productName, double initCheckingBal,
			double initSavingsBal, double initOutput, double initWageBudget,
			int initCapital, CFirm[] capitalProducers) {
		super(initCheckingBal, initSavingsBal);
		capital = new Capital(initCapital, getID(), capitalProducers);
		pMkt = (ConsumerGoodMarket) Economy.getMarket(productName);
		cMkt = (CapitalMarket) Economy.getMarket("Capital");
		lMkt = (LaborMarket) Economy.getMarket("Labor");
		output = initOutput;
		wageBudget = initWageBudget;
		loan = 0;
		capitalCost = 0;
		pfAvger = new Averager(AVG_PROFIT_WIN);

		// post wage to the labor market so that the firm
		// gets employees before the first round begins
		lMkt.addEmployer(this, labor, wageBudget);
	}

	/**
	 * Called by Economy.step() in each step.
	 */
	public void act() {
		double newOutput, newWageBudget, pPrice;

		// get firm finance information
		Account acct = Bank.getAcct(getID());
		revenue = acct.priIC;
		loan = -acct.getBalance(Bank.SAVINGS);
		totalCost = wageBudget + capitalCost - acct.interest;
		profit = revenue - totalCost;

		capacity = convertToProduct(labor.getQuantity(), capital.getQuantity());
		wage = labor.getQuantity() > 0 ? wageBudget / labor.getQuantity() : 0;

		if (labor.getQuantity() > 0) {
			if (Economy.getTimeStep() == 0) {
				// initial step
				newOutput = output;
				newWageBudget = wageBudget;
			} else {
				double moneyFlowGap = acct.getBalance(Bank.CHECKING)
						- totalCost;

				// set new wage budget
				newWageBudget = wageBudget + lambda * moneyFlowGap;
				newWageBudget = Math.max(0, newWageBudget);

				// pay interest on loans (if any)
				if (acct.interest < 0)
					Bank.deposit(getID(), -acct.interest);

				// compute marginal cost
				double MC = wage / beta * Math.pow(A, -1 / beta)
						* Math.pow(output, 1 / beta - 1)
						* Math.pow(capital.getQuantity(), 1 - 1 / beta);

				pPrice = pMkt.getLastMktPrice(); // product price
				marginalProfit = pPrice - MC; // marginal profit

				// set new output
				newOutput = output * (1 + phi * marginalProfit / pPrice);
			}

			// constrain output by capacity
			newOutput = Math.min(capacity, newOutput);
			if (newOutput > 0)
				product.increase(newOutput);
		} else {
			newOutput = output;
			newWageBudget = wageBudget;
		}

		// post sell offer to product market
		if (product.getQuantity() > 0)
			pMkt.addSellOffer(this, product.getQuantity());

		// post wage budget to labor market
		lMkt.addEmployer(this, labor, newWageBudget);

		// pay loan (if any)
		if (loan > 0) {
			Bank.deposit(getID(),
					Math.max(0, Math.min(acct.getBalance(Bank.CHECKING), loan)));
		}

		double oldCapitalVal = capitalVal;
		capitalQty = capital.getQuantity(); // quantity of machines
		capitalVal = capital.getPresentValue(); // total present value of
												// capital
		capitalCost = capital.useCapital(); // cost of capital in this step

		if (Economy.getTimeStep() > 0) {
			int capitalToBuy = 0; // number of machines to purchase
			double capitalPrice = cMkt.getAvgPrice();
			double IR = Bank.getLoanIR(); // interest rate
			double IK = profit / oldCapitalVal; // rate of return on capital
			double utilization = newOutput / capacity; // capacity utilization
			double MR = acct.priIC / capitalQty * (1 - beta); // marginal
																// revenue on
																// capital

			// buy capital if rate of return on capital >= interest rate,
			// capacity utilization >= eUtilThreshold,
			// marginal revenue >= capital price
			if (IK >= IR && utilization >= eUtilThreshold && MR >= capitalPrice)
				capitalToBuy += 1;

			double avgProfit = pfAvger.update(Math.abs(profit));

			/***************************************************************/
			/* The following steps are considered hacks, use with discretion */
			/*
			 * Spur firms to buy capital when profit is really high (more than 5
			 * times the average). In this case, capacity utilization
			 * requirement is lowered 0.8 from 0.9. Turn this on only after step
			 * 2000 when the economy stabilizes
			 */
			if (IK >= IR && utilization > 0.8 && profit > 5 * avgProfit
					&& Economy.getTimeStep() > 2000)
				capitalToBuy += 1;

			/*
			 * Buy less capital when it is making a lot of losses (if the firm
			 * still decides to expand in this case, which does not really make
			 * sense)
			 */
			if (profit < -5 * avgProfit && Economy.getTimeStep() > 2000)
				capitalToBuy -= 1;
			/***************************************************************/

			// number of machines that are written off
			double scrapped = capitalQty - capital.getQuantity();
			if (scrapped > 0) {
				// replace scrapped machines

				double x = capitalQty - scrapped;
				while (output / convertToProduct(labor.getQuantity(), x) > rUtilThreshold
						&& x < capitalQty && acct.priIC / x > capitalPrice)
					x++;
				capitalToBuy += scrapped + x - capitalQty;
			}

			// buy capital if there's none left
			if (capital.getQuantity() < 1) {
				capitalToBuy = Math.max(1, capitalToBuy);
			}

			// post buy offer to capital market
			if (capitalToBuy > 0)
				cMkt.addBuyOffer(capital, capitalToBuy);
		}

		if (newOutput > 0)
			output = newOutput;
		wageBudget = newWageBudget;
		acct.priIC = 0;
		labor.decrease(labor.getQuantity()); // clear unused labor
		loan = -acct.getBalance(Bank.SAVINGS);
	}

	/**
	 * Return output produced by <tt>labor</tt> amount of labor and <tt>K</tt>
	 * amount of capital
	 * 
	 * @param labor
	 *            amount of labor
	 * @param K
	 *            amount of capital
	 * @return output produced by <tt>labor</tt> amount of labor and <tt>K</tt>
	 *         amount of capital
	 */
	public double convertToProduct(double labor, double K) {
		return A * Math.pow(labor, beta) * Math.pow(K, 1 - beta);
	}
}
