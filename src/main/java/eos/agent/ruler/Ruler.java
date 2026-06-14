package eos.agent.ruler;

import eos.agent.AbstractHousehold;
import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.bank.Account;
import eos.bank.Bank;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.good.RationSize;
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
 * the gold bank skims its currency-exchange fee. It also keeps a lavish table —
 * eating the {@link eos.good.RationSize#GOURMET} ration each step and restocking
 * necessity toward a reserve (also copper-quoted, so it likewise fires the FX fee).
 * There is one ruler per settlement; it never starves.
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

	// per-step tax rates: a fraction of each bank's distributable profit and of
	// each noble's income, skimmed into the treasury (0 disables — the default)
	private final double bankProfitTaxRate;
	private final double nobleIncomeTaxRate;

	// cumulative tax collected over the ruler's life (for reporting/tests)
	@Getter
	private double taxCollected;

	// days of its GOURMET ration the ruler keeps as a stocked larder
	private static final int NECESSITY_RESERVE_DAYS = 30;

	// the enjoyment and necessity the ruler buys, and the markets it buys from
	private final Enjoyment enjoyment;
	private final Necessity necessity;
	private final ConsumerGoodMarket eMkt;
	private final ConsumerGoodMarket nMkt;

	// enjoyment spending ($) in the last step
	@Getter
	private double consumption;

	// necessity units to buy this step (the gap to the reserve target, set in act)
	private double nGap;

	// demand strategies: spend the whole enjoyment budget at the going price, and
	// restock necessity toward the reserve (the ruler never starves, so no floor).
	// The ruler's necessity draw is tiny (its own household), so price-inelastic
	// restocking does not move the market.
	private final Demand demandForE = price -> consumption / price;
	private final Demand demandForN = price -> nGap;

	/**
	 * Create the settlement's founding ruler, holding <tt>initSavingsBal</tt> at
	 * (and as the sole client of) the gold bank.
	 *
	 * @param initSavingsBal
	 *            the ruler's opening fortune, in copper (the base unit)
	 * @param consumptionRate
	 *            fraction of the treasury spent on enjoyment each step
	 * @param bankProfitTaxRate
	 *            fraction of each bank's distributable profit taxed each step
	 * @param nobleIncomeTaxRate
	 *            fraction of each noble's income taxed each step
	 * @param goldBank
	 *            the gold bank the ruler owns and banks at
	 * @param colony
	 *            the colony this ruler belongs to
	 */
	public Ruler(double initSavingsBal, double consumptionRate,
			double bankProfitTaxRate, double nobleIncomeTaxRate, Bank goldBank,
			Settlement colony) {
		this(0, initSavingsBal, consumptionRate, bankProfitTaxRate,
				nobleIncomeTaxRate, false, goldBank, colony, null);
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
				predecessor.consumptionRate, predecessor.bankProfitTaxRate,
				predecessor.nobleIncomeTaxRate, true, predecessor.getBank(), colony,
				predecessor.getHead().surname());
	}

	private Ruler(double initCheckingBal, double initSavingsBal,
			double consumptionRate, double bankProfitTaxRate,
			double nobleIncomeTaxRate, boolean inherited, Bank goldBank,
			Settlement colony, String surname) {
		super(initCheckingBal, initSavingsBal, inherited, surname, goldBank,
				colony);
		this.consumptionRate = consumptionRate;
		this.bankProfitTaxRate = bankProfitTaxRate;
		this.nobleIncomeTaxRate = nobleIncomeTaxRate;
		this.enjoyment = new Enjoyment(0);
		this.necessity = new Necessity(0);
		this.eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		this.nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
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

		// tax the colony's accumulated wealth into the treasury before spending
		collectTaxes();

		// a sovereign indulgence: spend a small fraction of the treasury on
		// enjoyment, posting a buy offer the market settles in clear(). Buying
		// copper-quoted enjoyment converts gold -> copper, so the gold bank skims
		// its FX fee. Move the budget into checking (drawing on savings) so the
		// purchase is funded; the rest stays on deposit.
		// indulge only out of a positive treasury; a ruler driven into debt (e.g.
		// borrowing to feed the peasant pool) stops buying luxuries rather than
		// posting a negative demand
		double wealth = acct.getChecking() + acct.getSavings();
		consumption = consumptionRate * Math.max(0, wealth);

		// the ruler keeps a lavish table: eat the GOURMET ration each step (it never
		// starves) and restock necessity toward its reserve. Necessity is copper-quoted
		// like enjoyment, so the purchase converts gold -> copper and fires the gold
		// bank's FX fee.
		necessity.decrease(RationSize.GOURMET.perDay());
		double nReserve = NECESSITY_RESERVE_DAYS * RationSize.GOURMET.perDay();
		nGap = Math.max(0, nReserve - necessity.getQuantity());
		double nCost = nGap * Math.max(nMkt.getLastMktPrice(), 0.01);

		// fund both purchases from the treasury (the rest stays on deposit)
		bank.deposit(getID(), acct.getChecking() - consumption - nCost);
		eMkt.addBuyOffer(this, demandForE);
		nMkt.addBuyOffer(this, demandForN);

		// the ruler earns no wage/dividends; tax revenue enters via collectTaxes
		// (as OTHER, not income), so reset the income accumulators each step.
		resetIncomeAccumulators(acct);
	}

	/**
	 * Tax the colony's accumulated wealth into the treasury (the first slice of
	 * the taxation feature): a fraction ({@code bankProfitTaxRate}) of each bank's
	 * {@linkplain Bank#getDistributableProfit() distributable profit} — skimmed out
	 * of the bank's equity exactly as a noble dividend is, leaving estates-in-transit
	 * and injected funds alone — and a fraction ({@code nobleIncomeTaxRate}) of each
	 * living noble's income this step, withdrawn from the noble's account. A no-op
	 * when both rates are 0 (the default), so an untaxed colony is unchanged.
	 * <p>
	 * Run before the ruler's own spending; because the ruler acts last each step,
	 * every noble's income field already holds this step's value, and the bank
	 * profit reflects what owners have already drawn. The revenue lands in the
	 * (gold) treasury, so copper-quoted taxes fire the gold bank's FX fee.
	 */
	private void collectTaxes() {
		if (bankProfitTaxRate <= 0 && nobleIncomeTaxRate <= 0)
			return;
		Bank treasury = getBank();

		if (bankProfitTaxRate > 0) {
			for (Bank b : getColony().getBanks()) {
				double tax = bankProfitTaxRate * b.getDistributableProfit();
				if (tax > 0) {
					b.payDividend(tax);
					treasury.credit(getID(), tax, Bank.OTHER);
					taxCollected += tax;
				}
			}
		}

		if (nobleIncomeTaxRate > 0) {
			for (Agent a : getColony().getAgents()) {
				if (a instanceof Noble noble && noble.isAlive()) {
					double tax = nobleIncomeTaxRate * noble.getIncome();
					if (tax > 0) {
						noble.getBank().withdraw(noble.getID(), tax);
						treasury.credit(getID(), tax, Bank.OTHER);
						taxCollected += tax;
					}
				}
			}
		}
	}

	/**
	 * Return a reference to the good with name <tt>goodName</tt> (only enjoyment,
	 * which the ruler buys; it holds nothing else).
	 */
	public Good getGood(String goodName) {
		if (goodName.equals("Enjoyment"))
			return enjoyment;
		if (goodName.equals("Necessity"))
			return necessity;
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
		return String.format("Ruler#%d %s [%s %s age=%d]", getID(),
				getHead().fullName(), isAlive() ? "alive" : "dead",
				getHead().skills(), getAgeYears());
	}
}
