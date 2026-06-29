package com.civstudio.agent;

import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.good.Necessity;
import com.civstudio.good.RationSize;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.market.Demand;
import com.civstudio.settlement.Settlement;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * The colony's <b>ever-normal granary</b>: a ruler-run food buffer that stabilizes the
 * necessity price by trading real stock against the market within a band around the
 * market's reference price (see {@code docs/granary.md}). It is the
 * keystone of the food-economy redesign — the single mechanism that serves <b>both</b>
 * price regimes a symmetric price band cannot, because it acts on <b>quantity</b> (food
 * in and out), not on price discovery:
 * <ul>
 * <li>when a supply glut would push the necessity price <b>below the floor</b>
 *     ({@link GranaryConfig#floorFactor()} &times; the market reference price), the
 *     granary <b>buys</b> the surplus and stocks it — the price cannot crash, so firm
 *     revenue and the labor-share wage budget do not collapse;</li>
 * <li>when scarcity would push the price <b>above the ceiling</b>
 *     ({@link GranaryConfig#ceilFactor()} &times;), the granary <b>sells</b> from stock
 *     — capping the death-throes spike at its source and putting more food on the
 *     market exactly when it is scarcest.</li>
 * </ul>
 *
 * <p>It is a bare {@link Agent} (not a household): it banks in <b>copper</b> — the base
 * currency, so its high-volume food trades pay no FX fee — holds a {@link Necessity}
 * stock, and never ages, eats or dies. Its net cost is borne by the <b>ruler</b>: each
 * step it reconciles its copper account against the gold treasury exactly as the
 * peasant {@link Retinue} bills the ruler for relief — covering any deficit (the
 * overdraft its market purchases ran up), while keeping the proceeds of its sales as
 * working capital, so over a buy-low / sell-high cycle it is roughly self-funding and
 * only taps the treasury when its capital is exhausted.
 *
 * <p>The granary's <b>stock</b> is the colony's strategic food reserve — the buffer
 * later phases (child relief, fission dowries) draw on via {@link #drawStock(double)}.
 * <b>Phase 1</b> builds only the price-stabilizing keystone; the relief draws are wired
 * in later phases.
 */
@Log
public class Granary extends Agent {

	// the granary's tunable band and reserve target (see GranaryConfig)
	private final GranaryConfig config;

	// the food the granary holds — its strategic reserve
	private final Necessity stock = new Necessity(0);

	// the necessity market it trades on, and that market's stable reference price
	private final ConsumerGoodMarket nMkt;

	// the granary's stock at the end of the previous act(), so the net traded each
	// cycle (bought when up, sold when down) can be measured for reporting — act() does
	// not move stock, only the market's clear() does
	private double stockAtLastAct;

	// this step's reserve target, recomputed each act() from the live workforce — kept
	// for reporting (and the buy cap)
	@Getter
	private double targetStock;

	// reporting: necessity bought / sold in the last cleared cycle, and the cumulative
	// totals over the granary's life
	@Getter
	private double lastBought;
	@Getter
	private double lastSold;
	@Getter
	private double totalBought;
	@Getter
	private double totalSold;

	// cumulative net cost to the ruler of the granary (deficits covered minus surpluses
	// remitted) — negative once the granary's buy-low/sell-high has netted the crown a
	// profit over its outlays
	@Getter
	private double totalBilledToRuler;

	/**
	 * Create the colony's granary, banking at the (copper) {@code bank} with an empty
	 * stock and account.
	 *
	 * @param bank
	 *            the copper bank the granary transacts through
	 * @param colony
	 *            the colony this granary belongs to
	 * @param config
	 *            the granary's tunable parameters
	 */
	public Granary(Bank bank, Settlement colony, GranaryConfig config) {
		super(bank, colony);
		this.config = config;
		setName("Granary");
		bank.openAcct(getID(), 0, 0);
		this.nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
	}

	/** Called by Settlement.newDay() in each step. */
	public void act() {
		// measure last cycle's net trade (the market's clear() moved stock since the
		// previous act): a rise is a purchase, a fall a sale
		double delta = stock.getQuantity() - stockAtLastAct;
		if (delta > 1e-9) {
			lastBought = delta;
			lastSold = 0;
			totalBought += delta;
		} else if (delta < -1e-9) {
			lastSold = -delta;
			lastBought = 0;
			totalSold += -delta;
		} else {
			lastBought = lastSold = 0;
		}

		// the granary's cash P&L is the crown's: reconcile its account to ~0 against the
		// gold treasury each step — the ruler covers a purchase's overdraft, and the
		// proceeds of a sale are remitted back — so the value the granary skims buying
		// low and selling high lands in the treasury (where it recirculates through
		// relief and endowments) rather than sitting idle in the granary
		reconcileWithRuler();

		// the strategic reserve the granary aims to hold, in days of the workforce's
		// consumption — recomputed live, so it scales with the colony
		targetStock = config.targetDays() * dailyConsumption();

		// decide this step's intervention from last step's price; the market settles the
		// offer in clear(). No price yet on step 0 (the necessity market has not cleared)
		// — sit out.
		double last = nMkt.getLastMktPrice();
		if (last <= 0) {
			stockAtLastAct = stock.getQuantity();
			return;
		}
		double ref = nMkt.getInitialPrice();
		double floor = config.floorFactor() * ref;
		double ceil = config.ceilFactor() * ref;

		if (last <= floor && stock.getQuantity() < targetStock) {
			// a glut: post a buy wall — demand the cap below the floor, nothing above —
			// so the clearing price cannot fall through the floor while there is room in
			// the reserve. Capped by the per-step throttle and the headroom to target.
			double cap = Math.min(config.perStepTradeCap(),
					targetStock - stock.getQuantity());
			if (cap > 1e-9) {
				final double buyCap = cap;
				final double buyFloor = floor;
				nMkt.addBuyOffer(this, price -> price <= buyFloor ? buyCap : 0);
			}
		} else if (last >= ceil && stock.getQuantity() > 1e-9) {
			// scarcity: add real supply from the reserve, pushing the price back down
			// and putting food on the market when it is most needed
			double sell = Math.min(config.perStepTradeCap(), stock.getQuantity());
			if (sell > 1e-9)
				nMkt.addSellOffer(this, sell);
		}

		stockAtLastAct = stock.getQuantity();
	}

	// reconcile the granary's account to ~0 against the ruler's (gold) treasury each
	// step, so its cash P&L flows to the crown rather than accumulating idle: a deficit
	// (the overdraft a purchase ran up) is reimbursed by the ruler — mirroring
	// Retinue.billRuler — and a surplus (sale proceeds) is remitted back to the treasury.
	// Both transfers cross gold<->copper and so fire the gold bank's FX fee; the ruler
	// borrows if its treasury is short. Skipped during an interregnum (a dead ruler has
	// no account), the balance then carrying over.
	private void reconcileWithRuler() {
		Ruler ruler = getColony().getRuler();
		if (ruler == null || !ruler.isAlive())
			return;
		Bank bank = getBank();
		double balance = bank.getChecking(getID()) + bank.getSavings(getID());
		if (balance < -1e-9) {
			// deficit: the ruler reimburses, clearing the overdraft (checking -> savings)
			double deficit = -balance;
			ruler.getBank().withdraw(ruler.getID(), deficit);
			bank.credit(getID(), deficit, Bank.OTHER);
			bank.deposit(getID(), deficit);
			totalBilledToRuler += deficit;
		} else if (balance > 1e-9) {
			// surplus: remit the sale proceeds to the treasury
			bank.withdraw(getID(), balance);
			ruler.getBank().credit(ruler.getID(), balance, Bank.OTHER);
			totalBilledToRuler -= balance;
		}
	}

	// the colony's daily necessity consumption by its workforce: each living workforce
	// household eats the FINE ration per member. The workforce is what the reserve must
	// carry through lean spells, so it sets the target; nobles/ruler/pool are a small,
	// separately-provisioned remainder.
	private double dailyConsumption() {
		double mouths = 0;
		for (Agent a : getColony().getAgents())
			if (a.isAlive() && a instanceof Household h && h.isWorkforce())
				mouths += h.getMemberCount();
		return mouths * RationSize.FINE.perDay();
	}

	/**
	 * Draw up to {@code requested} units of necessity out of the granary's reserve,
	 * returning the amount actually drawn (less than requested only if the reserve is
	 * short). The seam later phases use to feed children and dower fissioning households
	 * from the strategic reserve rather than fresh treasury (see {@code
	 * docs/granary.md} §5.2–5.3 and the consolidated draws in §4).
	 *
	 * @param requested
	 *            necessity units to draw from the reserve
	 * @return the amount actually removed from the reserve
	 */
	public double drawStock(double requested) {
		double drawn = Math.min(Math.max(0, requested), stock.getQuantity());
		stock.decrease(drawn);
		return drawn;
	}

	/** @return the necessity the granary currently holds in reserve */
	public double getStock() {
		return stock.getQuantity();
	}

	/** @return the granary's cash (checking + savings) at its copper bank */
	public double getCash() {
		Bank bank = getBank();
		return bank.getChecking(getID()) + bank.getSavings(getID());
	}

	@Override
	public Good getGood(String goodName) {
		return "Necessity".equals(goodName) ? stock : null;
	}

	@Override
	public String toString() {
		return String.format("Granary#%d [stock=%.1f target=%.1f cash=%.1f]", getID(),
				stock.getQuantity(), targetStock, getCash());
	}
}
