package eos.agent.laborer;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.bank.Account;
import eos.economy.Economy;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.market.ConsumerGoodMarket;
import eos.market.LaborMarket;
import eos.market.Demand;
import lombok.Getter;

/**
 * Laborer
 * 
 * @author zhihongx
 * 
 */
public class Laborer extends Agent {

	// tunable model parameters
	private final LaborerConfig config;

	// enjoyment market
	private final ConsumerGoodMarket eMkt;

	// necessity market
	private final ConsumerGoodMarket nMkt;

	// labor market
	private final LaborMarket lMkt;

	// enjoyment good
	private final Enjoyment enjoyment;

	// necessity good
	private final Necessity necessity;

	// savings rate (portion of total income+savings that is saved in the last
	// step)
	@Getter
	private double savingsRate;

	// consumption (in $)
	@Getter
	private double consumption;

	// consumption of enjoyment (in $)
	@Getter
	private double eConsumption;

	// consumption of necessity (in $)
	@Getter
	private double nConsumption;

	// minimum necessity (in real quantity) to buy in the current step
	private double minN;

	// lowest real interest rate seen
	private double lowRR;

	// highest real interest rate seen
	private double highRR;

	// demand for enjoyment
	private DemandForE demandForE;

	// demand for necessity
	private DemandForN demandForN;

	// total income
	@Getter
	private double income;

	// wage from employment
	@Getter
	private double wage;

	/* demand for enjoyment */
	private class DemandForE implements Demand {
		public double getDemand(double price) {
			return eConsumption / price;
		}
	}

	/* demand for necessity */
	private class DemandForN implements Demand {
		public double getDemand(double price) {
			return Math.max(nConsumption / price, minN);
		}
	}

	/**
	 * Create a new laborer
	 * 
	 * @param initEQty
	 *            initial enjoyment quantity
	 * @param initNQty
	 *            initial necessity quantity
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 * @param initSavingsRate
	 *            initial savings rate
	 * @param config
	 *            tunable model parameters
	 */
	public Laborer(double initEQty, double initNQty, double initCheckingBal,
			double initSavingsBal, double initSavingsRate, LaborerConfig config) {
		super();

		// open a checking account and a savings account
		Bank.openAcct(this.getID(), initCheckingBal, initSavingsBal);

		this.config = config;
		enjoyment = new Enjoyment(initEQty);
		necessity = new Necessity(initNQty);
		eMkt = (ConsumerGoodMarket) Economy.getMarket("Enjoyment");
		nMkt = (ConsumerGoodMarket) Economy.getMarket("Necessity");
		lMkt = (LaborMarket) Economy.getMarket("Labor");
		this.savingsRate = initSavingsRate;
		demandForE = new DemandForE();
		demandForN = new DemandForN();
		lMkt.addEmployee(this);
	}

	/**
	 * Called by Economy.step() in each step.
	 */
	public void act() {
		Account acct = Bank.getAcct(this.getID());
		wage = acct.priIC;
		income = wage + acct.secIC + acct.interest;

		// should have used real interest rate i.e. Bank.getDepositIR() -
		// Economy.getInflation(). But that seems to produce some instability
		// need further testing!!!
		double RR = Bank.getDepositIR();

		// not enough to eat; die
		if (necessity.decrease(config.eatAmt()) < config.eatAmt()) {
			die();
			System.out.println(Economy.getTimeStep() + ": " + getName()
					+ getID() + " died with " + acct.getChecking()
					+ " checking and " + acct.getSavings()
					+ " savings");
			Bank.closeAcct(getID());
			return;
		}

		if (Economy.getTimeStep() > 0) {
			if (RR < lowRR)
				lowRR = RR;
			if (RR > highRR)
				highRR = RR;
		} else {
			// initial step
			lowRR = RR;
			highRR = RR;
		}

		double checking = acct.getChecking();
		double savings = acct.getSavings();

		// compute target savings
		double targetSavings = income * config.baseSavingsToIncomeRatio();
		if (highRR > lowRR)
			targetSavings *= (RR - lowRR) / (highRR - lowRR) * config.epsilon() * 2 + 1
					- config.epsilon();

		// compute target consumption
		double targetConsumption = checking + savings - targetSavings;

		// compute consumption
		if (Economy.getTimeStep() == 0)
			consumption = income;
		else
			consumption = Math.min(
					Math.max(consumption * (1 - config.upsilon()), targetConsumption),
					consumption * (1 + config.upsilon()));

		// compute amount to deposit
		double new_deposit = checking - consumption;
		Bank.deposit(getID(), new_deposit);

		// compute savings rate
		savingsRate = (savings + new_deposit) / (checking + savings);

		// compute consumption of necessity (in $)
		nConsumption = consumption
				* Math.max(0, 1 - necessity.getQuantity() / config.targetNStock());

		// compute consumption of enjoyment (in $)
		eConsumption = consumption - nConsumption;

		// if laborer has only 1 unit of necessity left, buy at least 1
		minN = necessity.getQuantity() < 2 * config.eatAmt() ? config.eatAmt() : 0;

		// post buy offer to enjoyment market
		eMkt.addBuyOffer(this, demandForE);

		// post buy offer to necessity market
		nMkt.addBuyOffer(this, demandForN);

		// post to labor market
		lMkt.addEmployee(this);

		acct.priIC = 0;
		acct.secIC = 0;
		acct.interest = 0;
	}

	/**
	 * Return a reference to the good with name <tt>goodName</tt>
	 */
	public Good getGood(String goodName) {
		if (goodName.equals("Enjoyment"))
			return enjoyment;
		else if (goodName.equals("Necessity"))
			return necessity;
		return null;
	}

	/**
	 * Return savings
	 *
	 * @return savings
	 */
	public double getSavings() {
		return Bank.getSavings(getID());
	}
}
