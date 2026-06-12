package eos.agent.ruler;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import eos.agent.Agent;
import eos.agent.Household;
import eos.bank.Account;
import eos.bank.Bank;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
import eos.name.Person;
import eos.settlement.Settlement;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * The <b>ruler</b> of the settlement: the owner of its gold bank. Unlike a
 * {@link eos.agent.noble.Noble} the ruler is not a rentier who draws dividends —
 * it is a <b>treasury that indulges</b>. It holds its fortune (gold) at the gold
 * bank, of which it is the sole account holder (the bank has no other clients),
 * and earns nothing, but each step it spends a small fraction of the treasury on
 * <b>enjoyment</b> — a sovereign luxury habit that slowly draws the reserves down.
 * Because enjoyment is priced in copper, that spending converts gold to copper, so
 * the gold bank skims its currency-exchange fee (the only thing that ever makes
 * the otherwise-idle gold bank transact). There is one ruler per settlement; it
 * never buys necessity and never starves.
 * <p>
 * Like the other households the ruler is a named {@link Person} that ages on the
 * mortality schedule and, when its head dies of old age, is succeeded by a heir
 * of the same dynasty who inherits the treasury (so the line endures). It is a
 * {@link Household} — named, skilled and tracked as a person of interest — but
 * sells no labor and owns no firms.
 */
@Log
public class Ruler extends Agent implements Household {

	// head of the ruling house: a male given name plus a unique dynasty surname
	@Getter
	private final Person head;

	// in-game birth date of the head (the source of truth for its age)
	@Getter
	private final LocalDate birthDate;

	// in-game date this ruling house came into being (its head acceded)
	@Getter
	private final LocalDate foundingDate;

	// this household's skill (0..20), drawn around the colony's mean skill like a
	// laborer's; carried for consistency with the other households (unused — the
	// ruler sells no labor)
	@Getter
	private final int skill;

	// fraction of the treasury (checking + savings) spent on enjoyment each step
	private final double consumptionRate;

	// the enjoyment the ruler buys and the market it buys it from
	private final Enjoyment enjoyment;
	private final ConsumerGoodMarket eMkt;

	// enjoyment spending ($) in the last step
	@Getter
	private double consumption;

	// demand strategy posted to the enjoyment market: spend the whole enjoyment
	// budget at the going price (the ruler never starves, so it has no floor)
	private final Demand demandForE = price -> consumption / price;

	// estate (checking, savings) snapshot taken at death so a successor ruler can
	// inherit it; savings is negative for an outstanding loan
	private double estateChecking, estateSavings;

	/**
	 * Create the settlement's founding ruler, holding <tt>initSavingsBal</tt> at
	 * (and as the sole client of) the gold bank.
	 *
	 * @param initSavingsBal
	 *            the ruler's opening fortune, in copper (the base unit)
	 * @param consumptionRate
	 *            fraction of the treasury spent on enjoyment each step
	 * @param goldBank
	 *            the gold bank the ruler owns and banks at
	 * @param colony
	 *            the colony this ruler belongs to
	 */
	public Ruler(double initSavingsBal, double consumptionRate, Bank goldBank,
			Settlement colony) {
		this(0, initSavingsBal, consumptionRate, false, goldBank, colony, null);
	}

	/**
	 * Create the ruler that succeeds <tt>predecessor</tt> when its head dies: a
	 * heir of the same dynasty who inherits the treasury (funded out of the gold
	 * bank's equity, as for any household succession) and banks at the same gold
	 * bank, with the same luxury habit.
	 *
	 * @param predecessor
	 *            the deceased ruler whose estate and dynasty are inherited
	 * @param colony
	 *            the colony this ruler belongs to
	 */
	public Ruler(Ruler predecessor, Settlement colony) {
		this(predecessor.estateChecking, predecessor.estateSavings,
				predecessor.consumptionRate, true, predecessor.getBank(), colony,
				predecessor.head.surname());
	}

	private Ruler(double initCheckingBal, double initSavingsBal,
			double consumptionRate, boolean inherited, Bank goldBank,
			Settlement colony, String surname) {
		super(goldBank, colony);
		this.consumptionRate = consumptionRate;
		this.enjoyment = new Enjoyment(0);
		this.eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		if (inherited)
			goldBank.openInheritedAcct(getID(), initCheckingBal, initSavingsBal);
		else
			goldBank.openAcct(getID(), initCheckingBal, initSavingsBal);

		// aged like any household head: a working-age birth date on the mortality
		// RNG; the house accedes now
		this.birthDate = colony.getDate().minusDays(colony.getDemography()
				.sampleInitialAgeDays(colony.getMeanInitAgeYears()));
		this.foundingDate = colony.getDate();
		this.skill = colony.getDemography().sampleSkill(colony.getMeanSkill());

		double nameRarity = (double) skill / Household.MAX_SKILL;
		this.head = (surname == null)
				? colony.getNames().nextHead(nameRarity)
				: colony.getNames().nextHeadInDynasty(surname, nameRarity);
		setName("Ruler");

		// the ruler is always a person of interest the colony tracks
		colony.addPersonOfInterest(this);
		log.info(head.fullName() + (surname == null ? " founded the ruling house"
				: " succeeded as ruler") + " of the settlement.");
	}

	/**
	 * Called by Settlement.newDay() in each step.
	 */
	public void act() {
		Bank bank = getBank();
		Account acct = bank.getAcct(getID());

		// the head may die of old age; its estate folds into the gold bank's
		// equity and a successor of the same dynasty inherits it (see the ruler
		// replacement policy)
		if (getColony().getDemography().diesOfOldAge(ageDays())) {
			die();
			estateChecking = acct.getChecking();
			estateSavings = acct.getSavings();
			bank.inheritAndClose(getID());
			return;
		}

		// a sovereign indulgence: spend a small fraction of the treasury on
		// enjoyment, posting a buy offer the market settles in clear(). Buying
		// copper-quoted enjoyment converts gold -> copper, so the gold bank skims
		// its FX fee. Move the budget into checking (drawing on savings) so the
		// purchase is funded; the rest stays on deposit.
		double wealth = acct.getChecking() + acct.getSavings();
		consumption = consumptionRate * wealth;
		bank.deposit(getID(), acct.getChecking() - consumption);
		eMkt.addBuyOffer(this, demandForE);

		// the ruler earns nothing. Reset the income accumulators each step.
		acct.priIC = 0;
		acct.secIC = 0;
		acct.interest = 0;
	}

	/**
	 * Return a reference to the good with name <tt>goodName</tt> (only enjoyment,
	 * which the ruler buys; it holds nothing else).
	 */
	public Good getGood(String goodName) {
		if (goodName.equals("Enjoyment"))
			return enjoyment;
		return null;
	}

	/** Liquid wealth: checking plus savings (in copper, the base unit). */
	public double getWealth() {
		return getBank().getChecking(getID()) + getBank().getSavings(getID());
	}

	/** The ruler's income: always zero (a passive treasury). */
	public double getIncome() {
		return 0;
	}

	/** The head's age in days: the span from its birth date to today. */
	private int ageDays() {
		return (int) ChronoUnit.DAYS.between(birthDate, getColony().getDate());
	}

	/**
	 * Return the head's age in whole years.
	 *
	 * @return the head's age in years
	 */
	public int getAgeYears() {
		return ageDays() / 365;
	}

	/**
	 * A concise, debug-friendly summary using only cached fields, so it is safe
	 * even after the ruler's account has been closed.
	 */
	@Override
	public String toString() {
		return String.format("Ruler#%d %s [%s skill=%d age=%d]", getID(),
				head.fullName(), isAlive() ? "alive" : "dead", skill,
				getAgeYears());
	}
}
