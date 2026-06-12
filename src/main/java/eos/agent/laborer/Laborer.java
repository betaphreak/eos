package eos.agent.laborer;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import eos.agent.Agent;
import eos.agent.Household;
import eos.bank.Bank;
import eos.bank.Account;
import eos.settlement.Settlement;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.market.ConsumerGoodMarket;
import eos.market.LaborMarket;
import eos.market.Demand;
import eos.name.Person;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * Laborer
 * 
 * @author zhihongx
 *
 */
@Log
public class Laborer extends Agent implements Household {

	// tunable model parameters
	private final LaborerConfig config;

	// head of this household (each laborer represents one household): a male
	// given name plus a unique dynasty surname
	@Getter
	private final Person head;

	// in-game birth date of the household head: the date of construction less a
	// sampled initial age. The source of truth for the head's age (derived as
	// the days from here to the colony's current date).
	@Getter
	private final LocalDate birthDate;

	// in-game date this household came into being in the colony — the moment its
	// head (an already-grown person, born back on birthDate) arrived and founded
	// it. Distinct from birthDate: a working-age adult founds the household now.
	@Getter
	private final LocalDate foundingDate;

	// this household's skill (an integer in [0, 20]): the source of its labor
	// productivity. Drawn at birth from the demographic skill distribution and
	// re-rolled for a successor head, so skill is not inherited across
	// generations.
	@Getter
	private final int skill;

	// labor produced per step when employed, derived from skill by the
	// productivity curve (skill 10 -> 1 unit, the legacy homogeneous case)
	@Getter
	private final double productivity;

	// estate (checking, savings) snapshot taken at death so a successor
	// household can inherit it; savings is negative for an outstanding loan
	private double estateChecking, estateSavings;

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
		this(initEQty, initNQty, predecessor.estateChecking,
				predecessor.estateSavings, true, initSavingsRate, config,
				predecessor.getBank(), colony, predecessor.head.surname());
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
		super(bank, colony);

		// open a checking account and a savings account
		if (inherited)
			bank.openInheritedAcct(this.getID(), initCheckingBal, initSavingsBal);
		else
			bank.openAcct(this.getID(), initCheckingBal, initSavingsBal);

		// the head is aged on a separate mortality RNG: its birth date is today
		// less a sampled initial age. The household itself is founded now.
		this.birthDate = colony.getDate().minusDays(colony.getDemography()
				.sampleInitialAgeDays(colony.getMeanInitAgeYears()));
		this.foundingDate = colony.getDate();

		// skill is a fresh draw for every head (founders, immigrants, and each
		// successor), centered on the colony's mean skill, on the demographic
		// skill RNG; it fixes this household's labor productivity for life
		this.skill = colony.getDemography().sampleSkill(colony.getMeanSkill());
		this.productivity = Household.productivityOf(skill);

		// draw the head on the naming RNG with the given name's rarity tracking
		// skill — abler households get rarer, more distinctive names. A null
		// surname starts a new dynasty; otherwise the head continues the given one.
		double nameRarity = (double) skill / Household.MAX_SKILL;
		this.head = (surname == null)
				? colony.getNames().nextHead(nameRarity)
				: colony.getNames().nextHeadInDynasty(surname, nameRarity);

		// a notable arrival (skill above the threshold) is worth recording by name,
		// and is a person of interest the colony tracks (and logs yearly)
		if (isNotable()) {
			log.info(String.format(
					"%s founded a household in the colony — notable (skill %d)",
					head.fullName(), skill));
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
	 * Called by Settlement.newDay() in each step.
	 */
	public void act() {
		Bank bank = getBank();
		Account acct = bank.getAcct(this.getID());

		// the household head may die of old age; its age in days is the span
		// from its birth date to today
		if (getColony().getDemography().diesOfOldAge(ageDays())) {
			die();
			estateChecking = acct.getChecking();
			estateSavings = acct.getSavings();
			bank.inheritAndClose(getID());
			return;
		}

		wage = acct.priIC;
		income = wage + acct.secIC + acct.interest;

		// should have used real interest rate i.e. Bank.getDepositIR() -
		// Settlement.getInflation(). But that seems to produce some instability
		// need further testing!!!
		double RR = bank.getDepositIR();

		// not enough to eat; die (a successor household of the same dynasty
		// inherits the estate)
		if (necessity.decrease(config.eatAmt()) < config.eatAmt()) {
			die();
			estateChecking = acct.getChecking();
			estateSavings = acct.getSavings();
			bank.inheritAndClose(getID());
			return;
		}

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

		// compute consumption of necessity (in $)
		nConsumption = consumption * Math.max(0,
				1 - necessity.getQuantity() / getColony().getTargetNStock());

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
		firstAct = false;
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

	/** Liquid wealth: checking plus savings (savings negative for a loan). */
	public double getWealth() {
		return getBank().getChecking(getID()) + getBank().getSavings(getID());
	}

	/**
	 * Return the household head's age in days: the span from its birth date to
	 * the colony's current date.
	 *
	 * @return the head's age in days
	 */
	private int ageDays() {
		return (int) ChronoUnit.DAYS.between(birthDate, getColony().getDate());
	}

	/**
	 * Return the household head's age in whole years.
	 *
	 * @return the head's age in years
	 */
	public int getAgeYears() {
		return ageDays() / 365;
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
				getID(), head.fullName(), isAlive() ? "alive" : "dead",
				birthDate, getAgeYears(), wage, income, consumption, savingsRate);
	}
}
