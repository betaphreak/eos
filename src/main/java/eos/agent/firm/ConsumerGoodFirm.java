package eos.agent.firm;

import eos.bank.Bank;
import eos.bank.Account;
import eos.settlement.Settlement;
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

	/**
	 * tunable model parameters
	 */
	protected final FirmConfig config;

	/**
	 * product the firm is producing/selling (enjoyment or necessity).
	 * Must be initialized by the subclass.
	 */
	protected Good product;

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
	 * Window (days) over which revenue is smoothed for the labor-share wage
	 * budget. Long enough to span the rest-day calendar — the weekly day of rest
	 * and the feast clusters — so a run of days on which the firm is closed (and
	 * sells nothing) does not collapse its wage budget to zero and starve it of
	 * labor on the days it reopens. See {@link #act()}.
	 */
	private static final int REVENUE_SMOOTH_WIN = 30;

	/**
	 * smoothed revenue used to size the labor-share wage budget (see
	 * {@code REVENUE_SMOOTH_WIN})
	 */
	private Averager revAvger;

	/**
	 * Trailing-averaged profit and capacity utilization (over
	 * {@code REVENUE_SMOOTH_WIN} days), the firm-level signals the ruler's dynamic
	 * provisioning reads to pick a dissolution victim and gauge whether a sector is
	 * supply-constrained. Smoothed so a single day's snapshot (e.g. a rest day with
	 * no output) does not drive an open/close decision. Utilization is averaged
	 * only over days the firm actually operated, so the rest-day calendar does not
	 * bias it downward.
	 */
	private final Averager profitAvger = new Averager(REVENUE_SMOOTH_WIN);
	private final Averager utilAvger = new Averager(REVENUE_SMOOTH_WIN);
	private double smoothedProfit;
	private double smoothedUtilization;

	/** The colony time step at which this firm was founded (for the dynamic
	 * provisioning's minimum-firm-lifetime hysteresis). */
	private final int foundedStep;

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
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which this firm holds its accounts
	 * @param colony
	 *            the colony this firm belongs to
	 */
	public ConsumerGoodFirm(String productName, double initCheckingBal,
			double initSavingsBal, double initOutput, double initWageBudget,
			int initCapital, CFirm[] capitalProducers, FirmConfig config,
			Bank bank, Settlement colony) {
		super(initCheckingBal, initSavingsBal, bank, colony);
		setName(productName + " Firm");
		this.config = config;
		capital = new Capital(initCapital, getID(), bank, capitalProducers,
				colony.getRng());
		pMkt = (ConsumerGoodMarket) colony.getMarket(productName);
		cMkt = (CapitalMarket) colony.getMarket("Capital");
		lMkt = (LaborMarket) colony.getMarket("Labor");
		output = initOutput;
		wageBudget = initWageBudget;
		loan = 0;
		capitalCost = 0;
		revAvger = new Averager(REVENUE_SMOOTH_WIN);
		foundedStep = colony.getTimeStep();

		// post wage to the labor market so that the firm
		// gets employees before the first round begins
		lMkt.addEmployer(this, labor, wageBudget);
	}

	/**
	 * Called by Settlement.newDay() in each step.
	 */
	public void act() {
		double newOutput, newWageBudget, pPrice;
		Bank bank = getBank();

		// get firm finance information
		Account acct = bank.getAcct(getID());
		revenue = acct.priIC;
		// smooth revenue over the rest-day calendar: the labor-share wage budget
		// is sized off this average, not the single day's revenue, so that the
		// days the firm is closed (and earns nothing) do not zero its budget and
		// leave it unable to hire when it reopens (which would oscillate into a
		// production collapse)
		double avgRevenue = revAvger.update(revenue);
		loan = -acct.getSavings();
		totalCost = wageBudget + capitalCost - acct.interest;
		profit = revenue - totalCost;

		capacity = convertToProduct(labor.getQuantity(), capital.getQuantity());
		wage = labor.getQuantity() > 0 ? wageBudget / labor.getQuantity() : 0;

		// update the trailing firm-level signals the ruler's dynamic provisioning
		// reads (smoothed over REVENUE_SMOOTH_WIN). Profit is fed every step;
		// utilization only on days the firm operated (capacity > 0), so the rest-day
		// calendar's closed days do not drag the average down.
		smoothedProfit = profitAvger.update(profit);
		if (capacity > 0)
			smoothedUtilization =
					utilAvger.update(Math.min(1.0, output / capacity));

		if (labor.getQuantity() > 0) {
			if (getColony().getTimeStep() == 0) {
				// initial step
				newOutput = output;
				newWageBudget = wageBudget;
			} else {
				// set new wage budget
				if (config.laborShare() > 0) {
					// labor-share rule: budget a fixed share of revenue, so
					// total wage spending — and the uniform market wage
					// totalBudget/N — scales with the colony instead of being
					// outrun by a growing labor pool. Uses smoothed revenue so a
					// closed-day run (no sales) does not collapse the budget.
					newWageBudget = config.laborShare() * avgRevenue;
				} else {
					// legacy rule: nudge the budget by the firm's cash-flow gap
					double moneyFlowGap = acct.getChecking() - totalCost;
					newWageBudget = wageBudget + config.lambda() * moneyFlowGap;
				}
				newWageBudget = Math.max(0, newWageBudget);

				// pay interest on loans (if any)
				if (acct.interest < 0)
					bank.deposit(getID(), -acct.interest);

				// compute marginal cost
				double beta = config.beta();
				double MC = wage / beta * Math.pow(config.A(), -1 / beta)
						* Math.pow(output, 1 / beta - 1)
						* Math.pow(capital.getQuantity(), 1 - 1 / beta);

				pPrice = pMkt.getLastMktPrice(); // product price
				marginalProfit = pPrice - MC; // marginal profit

				// set new output
				newOutput = output * (1 + config.phi() * marginalProfit / pPrice);
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
			bank.deposit(getID(),
					Math.max(0, Math.min(acct.getChecking(), loan)));
		}

		double oldCapitalVal = capitalVal;
		capitalQty = capital.getQuantity(); // quantity of machines
		capitalVal = capital.getPresentValue(); // total present value of
												// capital
		capitalCost = capital.useCapital(); // cost of capital in this step

		if (getColony().getTimeStep() > 0) {
			int capitalToBuy = 0; // number of machines to purchase
			double capitalPrice = cMkt.getAvgPrice();
			double IR = bank.getLoanIR(); // interest rate
			double IK = profit / oldCapitalVal; // rate of return on capital
			double utilization = newOutput / capacity; // capacity utilization
			double MR = acct.priIC / capitalQty * (1 - config.beta()); // marginal
																// revenue on
																// capital

			// buy capital if rate of return on capital >= interest rate,
			// capacity utilization >= eUtilThreshold,
			// marginal revenue >= capital price
			if (IK >= IR && utilization >= config.eUtilThreshold() && MR >= capitalPrice)
				capitalToBuy += 1;

			// number of machines that are written off
			double scrapped = capitalQty - capital.getQuantity();
			if (scrapped > 0) {
				// replace scrapped machines

				double x = capitalQty - scrapped;
				while (output / convertToProduct(labor.getQuantity(), x) > config.rUtilThreshold()
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
		loan = -acct.getSavings();
	}

	/**
	 * The firm's profit averaged over {@link #REVENUE_SMOOTH_WIN} days — the signed
	 * trailing profitability the ruler's dynamic provisioning reads (rather than the
	 * noisy single-day {@link #getProfit()}) to judge a sector's health and pick a
	 * dissolution victim.
	 *
	 * @return trailing-averaged profit (may be negative)
	 */
	public double getSmoothedProfit() {
		return smoothedProfit;
	}

	/**
	 * The firm's <b>stock</b>: its unsold inventory of its product (enjoyment or
	 * necessity), carried between steps. For a necessity firm this is the food that,
	 * when its colony is abandoned, travels with the band into its larder rather than
	 * being lost (see {@link eos.agent.Caravan#dissolve}); an enjoyment firm's stock is
	 * simply lost. Reported by {@link eos.io.printer.FirmsPrinter}.
	 *
	 * @return the quantity of product the firm currently holds unsold
	 */
	public double getStock() {
		return product.getQuantity();
	}

	/**
	 * The name of the good this firm produces ({@code "Enjoyment"} or {@code
	 * "Necessity"}) — the consumer sector it belongs to, used to group firms by sector
	 * in the consolidated {@link eos.io.printer.FirmsPrinter}.
	 *
	 * @return the firm's product good name
	 */
	public String getProductName() {
		return product.getName();
	}

	/**
	 * The firm's capacity utilization (output / capacity) averaged over the days it
	 * operated within {@link #REVENUE_SMOOTH_WIN} — near 1 when the firm is
	 * supply-constrained (running flat out, a signal the sector needs another firm),
	 * low when it carries idle capacity (a signal the sector is overbuilt).
	 *
	 * @return trailing-averaged utilization in {@code [0, 1]}
	 */
	public double getSmoothedUtilization() {
		return smoothedUtilization;
	}

	/**
	 * Mark this firm <b>dissolved</b> (no longer alive): it stops drawing a dividend
	 * for its owner and drops out of the firm reports immediately, before the colony
	 * frees its slot and settles its account at the end of the step. Called by the
	 * dynamic firm provisioning (see {@link FirmFactory#dissolve}).
	 */
	public void markDissolved() {
		die();
	}

	/**
	 * The firm's age in days (colony steps) since it was founded — used by the
	 * dynamic provisioning's minimum-lifetime rule, so a freshly chartered firm is
	 * not dissolved on the next seasonal dip.
	 *
	 * @return days since founding
	 */
	public int getAgeDays() {
		return getColony().getTimeStep() - foundedStep;
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
		return config.A() * Math.pow(labor, config.beta())
				* Math.pow(K, 1 - config.beta());
	}
}
