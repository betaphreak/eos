package eos.agent.ruler;

import java.util.ArrayList;
import java.util.List;

import eos.agent.AbstractHousehold;
import eos.agent.Agent;
import eos.agent.firm.ConsumerGoodFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.NFirm;
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

	// --- dynamic firm provisioning thresholds (see reviewSectors) ---
	// charter another firm once demand pressure — the short-run unmet fraction above
	// its own long-run baseline — rises this far over the colony's norm
	private static final double OPEN_PRESSURE_THRESHOLD = 0.05;
	// necessity is inelastic (every mouth eats daily), so react to a smaller rise —
	// the colony errs toward over-provisioning food
	private static final double INELASTIC_OPEN_PRESSURE_THRESHOLD = 0.03;
	// once pressure falls this far below the norm the sector is easing (a glut)
	private static final double CLOSE_PRESSURE_THRESHOLD = -0.05;
	// only dissolve a firm running below this capacity utilization
	private static final double CLOSE_UTIL_THRESHOLD = 0.6;
	// never cut the necessity sector below this many firms (food is existential)
	private static final int MIN_NECESSITY_FIRMS = 1;

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

		// once a month, review each consumer-good sector for under- or
		// over-provisioning (log-only for now — see reviewSectors)
		if (getColony().getDate().getDayOfMonth() == 1)
			reviewSectors();

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

		// the ruler keeps a lavish table: eat the GOURMET ration per member each step
		// (it never starves) and restock necessity toward its reserve. Necessity is
		// copper-quoted like enjoyment, so the purchase converts gold -> copper and
		// fires the gold bank's FX fee.
		necessity.decrease(RationSize.GOURMET.perDay() * getMemberCount());
		double nReserve = NECESSITY_RESERVE_DAYS * RationSize.GOURMET.perDay()
				* getMemberCount();
		nGap = Math.max(0, nReserve - necessity.getQuantity());
		double nCost = nGap * Math.max(nMkt.getLastMktPrice(), 0.01);

		// fund both purchases from the treasury (the rest stays on deposit)
		bank.deposit(getID(), acct.getChecking() - consumption - nCost);
		eMkt.addBuyOffer(this, demandForE);
		nMkt.addBuyOffer(this, demandForN);

		// if unmarried, seek a spouse on the wedding market (the sovereign weds
		// first of all, from its own wards — see WeddingMarket)
		seekSpouseIfSingle();

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
	 * <b>Dynamic firm provisioning (trigger wired; actions still log-only).</b>
	 * Survey each consumer-good sector and decide whether the colony is short of
	 * firms (chronic unmet demand while the sector is profitable → charter another)
	 * or carrying idle capacity (no shortage plus a money-losing, under-used firm →
	 * dissolve the weakest). Necessity, whose demand is inelastic, is reviewed on a
	 * lower shortage threshold and never cut below {@link #MIN_NECESSITY_FIRMS}, so
	 * the colony errs toward over- rather than under-provisioning food.
	 * <p>
	 * Called once a month — gated on the first of the month in {@link #act()} — a
	 * cadence slow relative to the production and revenue-smoothing lags, so reading
	 * the signal does not provoke an entry/exit hog cycle. For now it only
	 * <em>logs</em> the decision it would take; the actual chartering (ruler funds
	 * seed capital from the treasury, gold→copper firing the FX fee, then assigns
	 * the firm to a noble owner via the existing dividend channel and {@link
	 * Settlement#claimSlot}, which queues a builder ring when the colony is full)
	 * and dissolution (liquidate capital, lay off, vacate the slot) are the next
	 * step. Until those land it moves no money and draws no randomness, so runs stay
	 * byte-identical; it is a no-op for a colony with no consumer firms. Reads the
	 * demand signal from {@link ConsumerGoodMarket#getUnmetPressure()} — the
	 * short-run shortfall relative to the sector's own long-run baseline, so the
	 * chronic band-limited-price bias cancels rather than firing every month.
	 */
	public void reviewSectors() {
		List<ConsumerGoodFirm> eFirms = new ArrayList<>();
		List<ConsumerGoodFirm> nFirms = new ArrayList<>();
		for (Agent a : getColony().getAgents()) {
			if (a instanceof EFirm f && f.isAlive())
				eFirms.add(f);
			else if (a instanceof NFirm f && f.isAlive())
				nFirms.add(f);
		}
		reviewSector("enjoyment", eMkt, eFirms, OPEN_PRESSURE_THRESHOLD, 0);
		reviewSector("necessity", nMkt, nFirms, INELASTIC_OPEN_PRESSURE_THRESHOLD,
				MIN_NECESSITY_FIRMS);
	}

	/**
	 * Review one consumer-good sector and log the firm it would open or close. The
	 * entry test pairs a demand signal (unmet-demand pressure past
	 * {@code openThreshold} — the short-run shortfall risen above its own long-run
	 * baseline) with a viability gate (the sector currently turns a profit, so a new
	 * entrant can be both filled and sustained); the exit test fires only as
	 * pressure eases below the norm and targets the weakest loss-making,
	 * under-utilized firm, never cutting below {@code minFirms}.
	 * <p>
	 * The profit gate is a stand-in for the design's return-on-capital-vs-interest
	 * margin — exposing each firm's capital value would let it compare ROC to
	 * {@code getBank().getLoanIR()} directly; sector profit uses only existing
	 * getters and keeps this skeleton self-contained.
	 */
	private void reviewSector(String label, ConsumerGoodMarket mkt,
			List<ConsumerGoodFirm> firms, double openThreshold, int minFirms) {
		if (firms.isEmpty())
			return;
		double pressure = mkt.getUnmetPressure();
		double unmet = mkt.getSmoothedUnmetFraction();
		double sectorProfit = 0;
		for (ConsumerGoodFirm f : firms)
			sectorProfit += f.getProfit();

		// short of capacity: demand pressure has risen above the colony's norm while
		// the sector is still profitable, so another firm can both be filled and
		// survive
		if (pressure > openThreshold && sectorProfit > 0) {
			log.info(String.format(
					"sector review: would charter a new %s firm (pressure=+%.1f%% unmet=%.1f%% sectorProfit=%.1f over %d firms)",
					label, pressure * 100, unmet * 100, sectorProfit, firms.size()));
			return;
		}

		// easing: demand pressure has fallen below the norm — dissolve the weakest
		// firm if it is losing money and running well below capacity (keeping the
		// sector floor)
		if (pressure < CLOSE_PRESSURE_THRESHOLD && firms.size() > minFirms) {
			ConsumerGoodFirm weakest = null;
			for (ConsumerGoodFirm f : firms) {
				double util = f.getCapacity() > 0 ? f.getOutput() / f.getCapacity() : 0;
				if (f.getProfit() < 0 && util < CLOSE_UTIL_THRESHOLD
						&& (weakest == null || f.getProfit() < weakest.getProfit()))
					weakest = f;
			}
			if (weakest != null) {
				double util = weakest.getCapacity() > 0
						? weakest.getOutput() / weakest.getCapacity() : 0;
				log.info(String.format(
						"sector review: would dissolve %s (profit=%.1f util=%.0f%%); %s easing, pressure=%.1f%%",
						weakest.getName(), weakest.getProfit(), util * 100, label,
						pressure * 100));
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

	/** The sovereign weds first of all (above nobles and laborers). */
	@Override
	public int weddingPriority() {
		return 2;
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
