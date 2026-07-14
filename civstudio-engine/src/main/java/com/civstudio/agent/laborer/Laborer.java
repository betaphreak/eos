package com.civstudio.agent.laborer;

import java.time.LocalDate;

import com.civstudio.agent.AbstractHousehold;
import com.civstudio.agent.Granary;
import com.civstudio.agent.Member;
import com.civstudio.race.Race;
import com.civstudio.bank.Bank;
import com.civstudio.bank.Account;
import com.civstudio.settlement.Settlement;
import com.civstudio.good.Enjoyment;
import com.civstudio.good.Good;
import com.civstudio.good.Necessity;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.market.LaborMarket;
import com.civstudio.market.Demand;
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
		// a founding (pool-less) laborer takes the colony's founding race
		this(initEQty, initNQty, initCheckingBal, initSavingsBal, false,
				initSavingsRate, config, bank, colony, null,
				colony.getFoundingRace());
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
		// an immigrant is a freshly-generated person, so it rolls its ancestry against
		// the colony's race-mix (a mono-cultural colony draws nothing and gets HUMAN)
		this(initEQty, initNQty, initCheckingBal, initSavingsBal,
				fundedFromEquity, initSavingsRate, config, bank, colony, null,
				colony.getDemography().sampleRace(colony.getRaceMix()));
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
		// an heir continues its dynasty, so it keeps the line's race (no re-roll)
		this(initEQty, initNQty, predecessor.getEstateChecking(),
				predecessor.getEstateSavings(), true, initSavingsRate, config,
				predecessor.getBank(), colony, predecessor.getHead().surname(),
				predecessor.getHead().race());
	}

	/**
	 * Shared constructor. Opens the account either as a fresh endowment
	 * ({@code inherited == false}) or out of the bank's equity
	 * ({@code inherited == true}, for a successor household), then initializes
	 * the rest of the household identically.
	 */
	private Laborer(double initEQty, double initNQty, double initCheckingBal,
			double initSavingsBal, boolean inherited, double initSavingsRate,
			LaborerConfig config, Bank bank, Settlement colony, String surname,
			Race race) {
		super(initCheckingBal, initSavingsBal, inherited, surname, race, bank, colony);

		// a notable arrival (skill above the threshold) is worth recording by name,
		// and is a person of interest the colony tracks (and logs yearly)
		if (isNotable()) {
			var skills = getHead().skills();
			log.fine(String.format(
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
			log.fine(String.format(
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

		// the household eats per member, in priority order (head, then other adults,
		// then children): an adult eats the FINE worker ration, a child the SNACK
		// ration. The head eats first — if even it cannot be fed the household dies
		// (a successor of the same dynasty inherits the estate); a non-head adult that
		// cannot be fed starves off. A child the larder cannot feed draws its ration
		// from the colony granary (child relief) before starving, so the next generation
		// survives lean spells (see docs/granary.md §5.2 / the loop below); children are
		// appended last, so the youngest are the last to be relieved and the first to
		// starve when even the granary is empty. See docs/births.md.
		LocalDate today = getColony().getDate();
		var members = getMembers();
		double available = necessity.getQuantity();
		double headRation = rationFor(members.get(0), today);
		if (available < headRation) {
			dieAndSettleEstate();
			return;
		}
		double remaining = available - headRation;
		Granary granary = getColony().getGranary();
		// members that starve off this step (the first member the larder cannot feed and
		// every lower-priority member after it). Removed by reference at the end, so any
		// DRAFTED member interleaved among them — away with an expedition, fed by its
		// caravan, not starving here (docs/explorer-caravan.md) — is preserved.
		java.util.List<Member> starvedOff = null;
		for (int i = 1; i < members.size(); i++) {
			Member m = members.get(i);
			// a drafted member is away on the expedition and fed by its caravan, so the
			// household neither feeds it nor starves it off (docs/explorer-caravan.md)
			if (m.isDrafted())
				continue;
			double r = rationFor(m, today);
			if (remaining >= r) {
				remaining -= r;
				continue;
			}
			// the larder cannot feed this member. A non-head ADULT starves off (and every
			// lower-priority present member after it). A CHILD instead draws its ration from
			// the granary (subsidized child relief, billed to the crown), so the next
			// generation survives lean spells to reach working age — only starving if the
			// granary too is empty. Children are appended last, so once the loop reaches
			// them every adult is already fed. See docs/granary.md §5.2.
			if (!m.isAdult(today) && granary != null && granary.getStock() >= r) {
				granary.drawStock(r);
				continue;
			}
			starvedOff = new java.util.ArrayList<>();
			for (int j = i; j < members.size(); j++) {
				Member later = members.get(j);
				if (!later.isDrafted())
					starvedOff.add(later);
			}
			break;
		}
		necessity.decrease(available - remaining);
		if (starvedOff != null)
			for (Member m : starvedOff)
				removeMember(m);

		// bear a child: a married household (an adult couple) with a fertile female
		// and a food cushion bears a child — a new SNACK-eating member — per the
		// colony's fertility config (the universal birth mechanism, shared with
		// nobles and the ruler). The newborn is added now, so it counts toward this
		// step's necessity buffer below. See docs/births.md.
		bearChildIfFertile(necessity.getQuantity(), config.eatAmt());

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

		// compute consumption of necessity (in $). The food buffer / minimum-buy scale
		// with the household's actual mouths, a child counting as its (smaller) SNACK
		// ration rather than a full adult — so a married household keeps the same
		// per-mouth buffer and newborns don't inflate it by a full unit each.
		// `mouths` is the daily ration in adult-equivalents (an all-adult household
		// equals its member count, preserving the prior behaviour exactly).
		double dailyNeed = dailyRation(today);
		double mouths = dailyNeed / config.eatAmt();
		nConsumption = consumption * Math.max(0, 1 - necessity.getQuantity()
				/ (getColony().getTargetNStock() * mouths));

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

	/**
	 * <b>Emancipate a grown child</b> into its own household: remove and return this
	 * household's first adult, colony-born child (see {@link #releaseGrownChild}), or
	 * {@code null} if it has none. The child's food <b>dowry is granary-funded</b> (the
	 * colony's strategic store dowers the new household — see {@code docs/granary.md}
	 * §5.3), <b>not</b> drawn from this parent's larder: the parent's larder is typically
	 * depleted exactly when its child matures (a lean spell is what delays maturity), so
	 * gating fission on the parent's food was the second gate that kept it from ever
	 * firing (`docs/food-balance.md` #4). Fission grows the household <i>count</i> (the
	 * count the survival floor measures), the renewal path the finite peasant pool cannot
	 * provide.
	 *
	 * @param today
	 *            the colony's current date (sets working age)
	 * @return the released grown child, or {@code null} if none is eligible
	 */
	public Member emancipateChild(LocalDate today) {
		return releaseGrownChild(today);
	}

	// the daily necessity ration a member eats: an adult the FINE worker ration
	// (config.eatAmt()), a child the colony's configured child ration (SNACK)
	private double rationFor(Member m, LocalDate today) {
		return m.isAdult(today) ? config.eatAmt()
				: getColony().getFertilityConfig().childRation().perDay();
	}

	// the household's total daily necessity need: the sum of every member's ration
	// (adults at the worker ration, children at the smaller child ration)
	private double dailyRation(LocalDate today) {
		double sum = 0;
		for (Member m : getMembers())
			// a drafted member is away with the expedition (fed by its caravan), so it
			// does not size the household's necessity buy (docs/explorer-caravan.md)
			if (!m.isDrafted())
				sum += rationFor(m, today);
		return sum;
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
