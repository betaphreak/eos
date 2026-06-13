package eos.agent.ruler;

import eos.agent.AbstractHousehold;
import eos.agent.Agent;
import eos.bank.Account;
import eos.bank.Bank;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
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
 * Like the other households the ruler is a named {@link AbstractHousehold} that
 * ages on the mortality schedule and, when its head dies of old age, is
 * succeeded by a heir of the same dynasty who inherits the treasury (so the line
 * endures). It is skilled and tracked as a person of interest, but sells no
 * labor and owns no firms.
 */
@Log
public class Ruler extends AbstractHousehold {

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
		this(predecessor.getEstateChecking(), predecessor.getEstateSavings(),
				predecessor.consumptionRate, true, predecessor.getBank(), colony,
				predecessor.getHead().surname());
	}

	private Ruler(double initCheckingBal, double initSavingsBal,
			double consumptionRate, boolean inherited, Bank goldBank,
			Settlement colony, String surname) {
		super(initCheckingBal, initSavingsBal, inherited, surname, goldBank,
				colony);
		this.consumptionRate = consumptionRate;
		this.enjoyment = new Enjoyment(0);
		this.eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		setName("Ruler");

		// the ruler is always a person of interest the colony tracks
		colony.addPersonOfInterest(this);
		log.info(getHead().fullName() + (surname == null
				? " founded the ruling house" : " succeeded as ruler")
				+ " of the settlement.");
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
		if (checkOldAgeDeath())
			return;

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
		resetIncomeAccumulators(acct);
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

	/** The ruler's income: always zero (a passive treasury). */
	public double getIncome() {
		return 0;
	}

	/** Role label used in the persons-of-interest roster and death log. */
	@Override
	public String role() {
		return "Ruler";
	}

	/**
	 * The heir who succeeds this ruler: a same-dynasty sovereign inheriting the
	 * treasury. Also updates the colony's ruler reference, so anything that bills
	 * the ruler (e.g. a builder's public works) bills the heir, not the dead
	 * sovereign's closed account. The colony's built-in replacement policy calls
	 * this, so no simulation need wire a ruler rule.
	 */
	@Override
	public Agent successor(Settlement colony) {
		Ruler heir = new Ruler(this, colony);
		colony.setRuler(heir);
		return heir;
	}

	/**
	 * A concise, debug-friendly summary using only cached fields, so it is safe
	 * even after the ruler's account has been closed.
	 */
	@Override
	public String toString() {
		return String.format("Ruler#%d %s [%s skill=%d age=%d]", getID(),
				getHead().fullName(), isAlive() ? "alive" : "dead", getSkill(),
				getAgeYears());
	}
}
