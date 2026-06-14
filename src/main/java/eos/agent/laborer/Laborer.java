package eos.agent.laborer;

import eos.agent.AbstractHousehold;
import eos.agent.Member;
import eos.bank.Bank;
import eos.bank.Account;
import eos.settlement.Settlement;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.market.ConsumerGoodMarket;
import eos.market.LaborMarket;
import eos.market.Demand;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * Laborer
 *
 * @author zhihongx
 *
 */
@Log
public class Laborer extends AbstractHousehold {

	// tunable model parameters
	private final LaborerConfig config;

	// true until this household's first act(): seeds consumption and the
	// interest-rate window. A successor born after step 0 must bootstrap just
	// like the founding cohort did, otherwise its multiplicative consumption
	// adjustment stays pinned at 0 and it hoards all income.
	private boolean firstAct = true;

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

	// demand for enjoyment: spend the enjoyment budget at the going price
	private final Demand demandForE = price -> eConsumption / price;

	// demand for necessity: spend the necessity budget, but never below the
	// minimum real quantity needed to eat
	private final Demand demandForN = price -> Math.max(nConsumption / price, minN);

	// total income
	@Getter
	private double income;

	// wage from employment
	@Getter
	private double wage;

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
	 * @param bank
	 *            the bank at which this laborer holds its accounts
	 * @param colony
	 *            the colony this laborer belongs to
	 */
	public Laborer(double initEQty, double initNQty, double initCheckingBal,
			double initSavingsBal, double initSavingsRate, LaborerConfig config,
			Bank bank, Settlement colony) {
		this(initEQty, initNQty, initCheckingBal, initSavingsBal, false,
				initSavingsRate, config, bank, colony, null);
	}

	/**
	 * Create a brand-new household funded out of the bank's equity rather than a
	 * fresh endowment — an externally-bankrolled immigrant settling in an open
	 * colony. It starts a new dynasty (a fresh working-age head with a unique
	 * surname); its opening balances are drawn from equity (so the external
	 * money that fed equity now circulates), as for a successor household.
	 *
	 * @param initEQty
	 *            initial enjoyment quantity
	 * @param initNQty
	 *            initial necessity quantity
	 * @param initCheckingBal
	 *            initial checking account balance (drawn from equity)
	 * @param initSavingsBal
	 *            initial savings account balance (drawn from equity)
	 * @param initSavingsRate
	 *            initial savings rate
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which this laborer holds its accounts
	 * @param colony
	 *            the colony this laborer belongs to
	 * @param fundedFromEquity
	 *            must be true; selects equity funding over a fresh endowment
	 *            (distinguishes this constructor from the founding one)
	 */
	public Laborer(double initEQty, double initNQty, double initCheckingBal,
			double initSavingsBal, double initSavingsRate, LaborerConfig config,
			Bank bank, Settlement colony, boolean fundedFromEquity) {
		this(initEQty, initNQty, initCheckingBal, initSavingsBal,
				fundedFromEquity, initSavingsRate, config, bank, colony, null);
	}

	/**
	 * Create the household that succeeds <tt>predecessor</tt> when its head
	 * dies: it inherits the predecessor's estate (its account balances, funded
	 * out of the bank's equity so money stays in circulation) and continues the
	 * same dynasty (a new head, same surname), banking at the same bank. A fresh
	 * working-age head is drawn.
	 *
	 * @param predecessor
	 *            the deceased household whose estate and dynasty are inherited
	 * @param initEQty
	 *            initial enjoyment quantity
	 * @param initNQty
	 *            initial necessity quantity
	 * @param initSavingsRate
	 *            initial savings rate
	 * @param config
	 *            tunable model parameters
	 * @param colony
	 *            the colony this laborer belongs to
	 */
	public Laborer(Laborer predecessor, double initEQty, double initNQty,
			double initSavingsRate, LaborerConfig config, Settlement colony) {
		this(initEQty, initNQty, predecessor.getEstateChecking(),
				predecessor.getEstateSavings(), true, initSavingsRate, config,
				predecessor.getBank(), colony, predecessor.getHead().surname());
	}

	/**
	 * Shared constructor. Opens the account either as a fresh endowment
	 * ({@code inherited == false}) or out of the bank's equity
	 * ({@code inherited == true}, for a successor household), then initializes
	 * the rest of the household identically.
	 */
	private Laborer(double initEQty, double initNQty, double initCheckingBal,
			double initSavingsBal, boolean inherited, double initSavingsRate,
			LaborerConfig config, Bank bank, Settlement colony, String surname) {
		super(initCheckingBal, initSavingsBal, inherited, surname, bank, colony);

		// a notable arrival (skill above the threshold) is worth recording by name,
		// and is a person of interest the colony tracks (and logs yearly)
		if (isNotable()) {
			var skills = getHead().skills();
			log.info(String.format(
					"%s founded a household in the colony — notable in %s (level %d); %s",
					getHead().fullName(), skills.peakSkill(), skills.peakLevel(),
					skills));
			colony.addPersonOfInterest(this);
		}

		this.config = config;
		enjoyment = new Enjoyment(initEQty);
		necessity = new Necessity(initNQty);
		eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
		lMkt = (LaborMarket) colony.getMarket("Labor");
		this.savingsRate = initSavingsRate;
		lMkt.addEmployee(this);
	}

	/**
	 * Create a laborer household by <b>adopting a promoted peasant</b> as its head:
	 * the {@code head} keeps its name, skills and age (so promotion is meritocratic),
	 * the household opens with the given config balances (a fresh endowment its
	 * sponsor — the ruler — funds externally), and it starts a new dynasty (the head
	 * carries a freshly-drawn surname). Used by the pool-promotion replacement policy
	 * (see {@code SimulationHarness}).
	 *
	 * @param head
	 *            the promoted peasant this household adopts as its head
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
	 * @param bank
	 *            the bank at which this laborer holds its accounts
	 * @param colony
	 *            the colony this laborer belongs to
	 */
	public Laborer(Member head, double initEQty, double initNQty,
			double initCheckingBal, double initSavingsBal, double initSavingsRate,
			LaborerConfig config, Bank bank, Settlement colony) {
		super(initCheckingBal, initSavingsBal, false, head, bank, colony);
		this.config = config;
		enjoyment = new Enjoyment(initEQty);
		necessity = new Necessity(initNQty);
		eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
		lMkt = (LaborMarket) colony.getMarket("Labor");
		this.savingsRate = initSavingsRate;
		lMkt.addEmployee(this);
		// a notable promoted head is recorded by name, like any notable arrival
		if (isNotable()) {
			var skills = getHead().skills();
			log.info(String.format(
					"%s was promoted from the peasantry — notable in %s (level %d); %s",
					getHead().fullName(), skills.peakSkill(), skills.peakLevel(),
					skills));
			colony.addPersonOfInterest(this);
		}
	}

	/**
	 * Called by Settlement.newDay() in each simulation step.
	 */
	public void act() {
		Bank bank = getBank();
		Account acct = bank.getAcct(this.getID());

		// the household head may die of old age; its estate folds into the bank's
		// equity, and a successor of the same dynasty inherits it
		if (checkOldAgeDeath())
			return;

		wage = acct.priIC;
		income = wage + acct.secIC + acct.interest;

		// should have used real interest rate i.e. Bank.getDepositIR() -
		// Settlement.getInflation(). But that seems to produce some instability
		// need further testing!!!
		double RR = bank.getDepositIR();

		// the household eats one ration per member (head plus any spouse). If it
		// cannot feed even the head it dies (a successor of the same dynasty
		// inherits the estate); if it can feed the head but not everyone, the
		// non-head members (the spouse) starve off and the household lives on.
		double ration = config.eatAmt();
		double ate = necessity.decrease(ration * getMemberCount());
		if (ate < ration) {
			dieAndSettleEstate();
			return;
		}
		int fed = (int) Math.floor(ate / ration + 1e-9);
		while (getMemberCount() > Math.max(1, fed))
			removeNonHeadMember();

		if (!firstAct) {
			if (RR < lowRR)
				lowRR = RR;
			if (RR > highRR)
				highRR = RR;
		} else {
			// this household's first step
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
		if (firstAct)
			consumption = income;
		else
			consumption = Math.min(
					Math.max(consumption * (1 - config.upsilon()), targetConsumption),
					consumption * (1 + config.upsilon()));

		// compute amount to deposit
		double new_deposit = checking - consumption;
		bank.deposit(getID(), new_deposit);

		// compute savings rate
		savingsRate = (savings + new_deposit) / (checking + savings);

		// compute consumption of necessity (in $). The stock target scales with the
		// household size, so a married household keeps the same per-member food buffer
		double dailyNeed = config.eatAmt() * getMemberCount();
		nConsumption = consumption * Math.max(0, 1 - necessity.getQuantity()
				/ (getColony().getTargetNStock() * getMemberCount()));

		// compute consumption of enjoyment (in $)
		eConsumption = consumption - nConsumption;

		// if the household has under two days' food, buy at least a day's worth for
		// everyone
		minN = necessity.getQuantity() < 2 * dailyNeed ? dailyNeed : 0;

		// post buy offer to enjoyment market
		eMkt.addBuyOffer(this, demandForE);

		// post buy offer to necessity market
		nMkt.addBuyOffer(this, demandForN);

		// post every household member to the labor market (head and any spouse)
		lMkt.addEmployee(this);

		// if unmarried, seek a spouse on the wedding market (it weds on weekends)
		seekSpouseIfSingle();

		resetIncomeAccumulators(acct);
		firstAct = false;
	}

	/** A laborer is the colony's workforce: its labor sustains the colony. */
	@Override
	public boolean isWorkforce() {
		return true;
	}

	/** Role label used in the persons-of-interest roster and death log. */
	@Override
	public String role() {
		return "Notable laborer";
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
		return getBank().getSavings(getID());
	}

	/**
	 * A concise, debug-friendly summary: id, household head, alive status and
	 * the latest economic snapshot. Uses only cached fields (no bank lookup),
	 * so it is safe to call even after the laborer has died and closed its
	 * account.
	 */
	@Override
	public String toString() {
		return String.format(
				"Laborer#%d %s [%s b=%s age=%d wage=%.2f income=%.2f consumption=%.2f savingsRate=%.2f]",
				getID(), getHead().fullName(), isAlive() ? "alive" : "dead",
				getBirthDate(), getAgeYears(), wage, income, consumption, savingsRate);
	}
}
