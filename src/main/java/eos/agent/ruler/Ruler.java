package eos.agent.ruler;

import java.util.ArrayList;
import java.util.List;

import eos.agent.AbstractHousehold;
import eos.agent.Agent;
import eos.agent.Rank;
import eos.agent.firm.ConsumerGoodFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.FirmFactory;
import eos.agent.firm.NFirm;
import eos.agent.firm.StrategicFirm;
import eos.agent.noble.Noble;
import eos.bank.Account;
import eos.bank.Bank;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.good.RationSize;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
import eos.market.LaborMarket;
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
	// a sector is supply-constrained — a reason to charter — when its firms run at or
	// above this smoothed utilization while demand still goes unfilled. This is the
	// price-bias-free capacity signal, and the one that cold-starts a 1-firm sector.
	private static final double OPEN_UTIL_THRESHOLD = 0.85;
	private static final double MIN_UNMET_TO_OPEN = 0.02;
	// ...or charter when demand pressure (short-run unmet above its long-run baseline)
	// surges this far above the colony's norm — the steady-state demand-growth trigger
	private static final double OPEN_PRESSURE_THRESHOLD = 0.05;
	// necessity is inelastic (every mouth eats daily), so react to a smaller surge —
	// the colony errs toward over-provisioning food
	private static final double INELASTIC_OPEN_PRESSURE_THRESHOLD = 0.03;
	// a sector is overbuilt — a reason to dissolve a firm — below this smoothed util
	private static final double CLOSE_UTIL_THRESHOLD = 0.55;
	// never cut a sector below this many firms (food and leisure both need a floor)
	private static final int MIN_NECESSITY_FIRMS = 1;
	private static final int MIN_ENJOYMENT_FIRMS = 1;
	// hysteresis against the seasonal charter/dissolve oscillation: a firm younger
	// than this is never dissolved (so a winter-chartered firm survives the spring
	// utilization dip), and a sector waits this long after an action of one kind
	// before taking the opposite kind (no charter↔dissolve flip-flop)
	private static final int MIN_FIRM_LIFETIME_DAYS = 365;
	private static final int REENTRY_COOLDOWN_DAYS = 365;

	// per-sector record of the last charter/dissolve step, for the re-entry cooldown
	private static final class SectorMemory {
		int lastCharterStep = Integer.MIN_VALUE / 2;
		int lastDissolveStep = Integer.MIN_VALUE / 2;
	}
	private final SectorMemory eMemory = new SectorMemory();
	private final SectorMemory nMemory = new SectorMemory();

	// the enjoyment and necessity the ruler buys, and the markets it buys from
	private final Enjoyment enjoyment;
	private final Necessity necessity;
	private final ConsumerGoodMarket eMkt;
	private final ConsumerGoodMarket nMkt;

	// the noble-only labor market the strategic export firm employs from, or null if
	// the colony has no export sector. The ruler works the strategic firm every step
	// (its head's SOCIAL drives the output, like a noble's), so the export
	// sector is never unstaffed — the aristocracy that normally staffs it is built up
	// over the first weeks by ennoblement (see SimulationHarness.topUpAristocracy).
	private final LaborMarket nobleLaborMkt;

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
		this.nobleLaborMkt =
				(LaborMarket) colony.getMarket(StrategicFirm.LABOR_MARKET);
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

		// once a month, review each consumer-good sector and charter/dissolve firms
		// so their count tracks demand (see reviewSectors)
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

		// work the strategic export firm: post the ruler to the noble-only labor
		// market every step (its head's SOCIAL drives its export output, exactly
		// like a noble's — see LaborMarket), so the export sector is never unstaffed
		// while the aristocracy is still being built up by ennoblement. A no-op for a
		// colony with no export sector. The export wage credits the gold treasury (FX
		// fee fires); the ruler does not draw dividends, and its reported income stays
		// 0 (getIncome) — the wage simply tops up the treasury.
		if (nobleLaborMkt != null)
			nobleLaborMkt.addEmployee(getID(), bank, 1.0, getHead().skills());

		// tax revenue enters via collectTaxes (as OTHER, not income); reset the income
		// accumulators each step (the export wage is left to settle into the treasury).
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
	 * <b>Dynamic firm provisioning.</b> Survey each consumer-good sector and, through
	 * the colony's {@link FirmFactory}, charter a firm where the colony is short of
	 * capacity or dissolve one where it is overbuilt — so the firm count tracks
	 * demand rather than being fixed at founding. A no-op when no factory is installed
	 * (the firm count then stays fixed) or a sector has no firms.
	 * <p>
	 * Called once a month — gated on the first of the month in {@link #act()} — a
	 * cadence slow relative to the production and revenue-smoothing lags, which (with
	 * the minimum-firm-lifetime and re-entry-cooldown hysteresis below) keeps the
	 * entry/exit from oscillating into a hog cycle.
	 * <p>
	 * The open rule is a hybrid of two demand signals: a <b>capacity</b> signal
	 * (smoothed utilization at/above {@link #OPEN_UTIL_THRESHOLD} with demand still
	 * unfilled), which is free of the band-limited-price bias and is what lets a
	 * one-firm sector cold-start, OR a <b>demand-growth</b> signal ({@link
	 * ConsumerGoodMarket#getUnmetPressure() pressure} — the short-run shortfall risen
	 * above the sector's own long-run baseline). Either, gated by the sector turning
	 * a profit (so a new entrant can be sustained), charters a firm. The close rule
	 * fires when the sector is overbuilt (smoothed utilization below {@link
	 * #CLOSE_UTIL_THRESHOLD}) and cuts the weakest firm old enough to be eligible.
	 */
	public void reviewSectors() {
		if (getColony().getFirmFactory() == null)
			return; // nothing to act with — the colony keeps a fixed firm count
		List<ConsumerGoodFirm> eFirms = new ArrayList<>();
		List<ConsumerGoodFirm> nFirms = new ArrayList<>();
		for (Agent a : getColony().getAgents()) {
			if (a instanceof EFirm f && f.isAlive())
				eFirms.add(f);
			else if (a instanceof NFirm f && f.isAlive())
				nFirms.add(f);
		}
		reviewSector("enjoyment", eMkt, eFirms, false, eMemory);
		reviewSector("necessity", nMkt, nFirms, true, nMemory);
	}

	/**
	 * Review one consumer-good sector and charter or dissolve at most one firm. See
	 * {@link #reviewSectors()} for the open/close rules; {@code necessity} selects
	 * the inelastic-good thresholds and sector floor, and {@code mem} carries the
	 * per-sector re-entry cooldown state.
	 */
	private void reviewSector(String label, ConsumerGoodMarket mkt,
			List<ConsumerGoodFirm> firms, boolean necessity, SectorMemory mem) {
		if (firms.isEmpty())
			return;
		int now = getColony().getTimeStep();
		FirmFactory factory = getColony().getFirmFactory();

		double unmet = mkt.getSmoothedUnmetFraction();
		double pressure = mkt.getUnmetPressure();
		double sectorProfit = 0, sumUtil = 0;
		for (ConsumerGoodFirm f : firms) {
			sectorProfit += f.getSmoothedProfit();
			sumUtil += f.getSmoothedUtilization();
		}
		double avgUtil = sumUtil / firms.size();

		// charter? sector profitable AND (supply-constrained OR demand surging), and
		// not within the re-entry cooldown after a recent dissolution in this sector
		double openPressure = necessity
				? INELASTIC_OPEN_PRESSURE_THRESHOLD : OPEN_PRESSURE_THRESHOLD;
		boolean supplyConstrained =
				avgUtil >= OPEN_UTIL_THRESHOLD && unmet > MIN_UNMET_TO_OPEN;
		boolean demandSurging = pressure > openPressure;
		boolean canCharter = now - mem.lastDissolveStep >= REENTRY_COOLDOWN_DAYS;
		if (sectorProfit > 0 && (supplyConstrained || demandSurging) && canCharter) {
			ConsumerGoodFirm f = factory.charter(necessity);
			if (f != null) {
				mem.lastCharterStep = now;
				log.info(String.format(
						"chartered a new %s firm (util=%.0f%% unmet=%.1f%% pressure=%+.1f%% sectorProfit=%.1f; now %d firms)",
						label, avgUtil * 100, unmet * 100, pressure * 100,
						sectorProfit, firms.size() + 1));
			}
			return;
		}

		// dissolve? sector overbuilt (idle capacity), above the floor, and not within
		// the cooldown after a recent charter; cut the weakest firm old enough to be
		// eligible (the minimum-lifetime rule spares a just-chartered firm)
		int minFirms = necessity ? MIN_NECESSITY_FIRMS : MIN_ENJOYMENT_FIRMS;
		boolean canDissolve = now - mem.lastCharterStep >= REENTRY_COOLDOWN_DAYS
				&& firms.size() > minFirms;
		if (avgUtil < CLOSE_UTIL_THRESHOLD && canDissolve) {
			ConsumerGoodFirm weakest = null;
			for (ConsumerGoodFirm f : firms) {
				if (f.getAgeDays() < MIN_FIRM_LIFETIME_DAYS)
					continue; // too young to cut (hysteresis against seasonal churn)
				if (f.getSmoothedProfit() < 0 && (weakest == null
						|| f.getSmoothedProfit() < weakest.getSmoothedProfit()))
					weakest = f;
			}
			if (weakest != null) {
				factory.dissolve(weakest);
				mem.lastDissolveStep = now;
				log.info(String.format(
						"dissolved %s (profit=%.1f age=%dd); %s overbuilt at util=%.0f%%, now %d firms",
						weakest.getName(), weakest.getSmoothedProfit(),
						weakest.getAgeDays(), label, avgUtil * 100,
						firms.size() - 1));
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

	/** A ruler commands a {@link Rank#VILLAGE} — the settlement it leads. */
	@Override
	public Rank rank() {
		return Rank.VILLAGE;
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
