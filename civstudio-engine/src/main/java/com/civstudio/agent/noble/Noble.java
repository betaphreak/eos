package com.civstudio.agent.noble;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import com.civstudio.agent.firm.ScienceFirm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.AbstractHousehold;
import com.civstudio.agent.Agent;
import com.civstudio.agent.Property;
import com.civstudio.agent.Member;
import com.civstudio.agent.Rank;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.race.Race;
import com.civstudio.bank.Account;
import com.civstudio.io.SimLog;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;
import com.civstudio.good.Enjoyment;
import com.civstudio.good.Good;
import com.civstudio.good.Necessity;
import com.civstudio.good.RationSize;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.market.Demand;
import com.civstudio.market.LaborMarket;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * A noble: the owner of one or more firms (and, in principle, banks), who lives
 * off the surplus they produce rather than off wages. A noble does <b>not</b>
 * sell labor on the general market — it is a pure rentier that participates in
 * the colony on the demand side, exactly as sketched in "option A": its dividend
 * income is spent back into the consumer-good markets, so it influences the
 * labor market only indirectly (consumption → firm revenue → the labor-share
 * wage budget).
 * <p>
 * Like a {@link Laborer}, a noble is a {@link AbstractHousehold
 * household} identified by a named head drawn the same way, ages and dies on the
 * same mortality schedule, and on death is succeeded by a <b>heir of the same
 * dynasty</b> who inherits the estate <i>and the ownership of the firms and
 * banks</i> (wired via {@link Settlement#addReplacementPolicy}), so the
 * aristocracy persists across generations.
 * <p>
 * Each step the noble <em>pulls</em> a dividend from every owned firm: a share
 * of the firm's positive profit, moved from the firm's account to the noble's
 * through the bank's secondary-income ({@link Bank#SECIC}) channel — the
 * dividend pathway that was wired into the model but previously never fired.
 * Drawing only on public firm getters and the bank's existing transfer
 * primitives, this adds nobles without touching firm or bank code, so a run with
 * no nobles is byte-identical to before.
 */
@Log
public class Noble extends AbstractHousehold {

	// tunable model parameters
	private final NobleConfig config;


	// the noble-only labor market the strategic firm employs from, or null when
	// the colony has no strategic sector (then the noble sells no labor and is
	// byte-identical to the pure-rentier design)
	private final LaborMarket nobleLaborMkt;

	// the scholar labor market the science firm employs from, or null when the colony
	// has no science firm. A noble supplies its intellectual labor to both this and
	// the export market (scholar-merchant double duty); see postNobleLabor.
	private final LaborMarket scholarLaborMkt;

	// the properties this noble owns and draws dividends from — firms and banks, in
	// one list. Iterated in insertion order; the constructors seat firms before
	// banks so the dividend collection order matches the two separate loops this
	// replaced (see act).
	private final List<Property> properties;

	// the FIEF this noble holds — the plot granted to it (its manor ground, where its palace is
	// raised), or null for a landless noble (docs/estate-system.md, vassalage P3). Set on
	// ennoblement (the ablest laborer keeps its home plot as its fief) or by a ruler's grant.
	private com.civstudio.settlement.Plot fief;

	// enjoyment and necessity the noble consumes
	private final Enjoyment enjoyment;
	private final Necessity necessity;

	// consumer-good markets the noble buys from
	private final ConsumerGoodMarket eMkt;
	private final ConsumerGoodMarket nMkt;

	// wage earned from strategic-sector labor in the last step (0 if the noble
	// does not work — no strategic sector)
	@Getter
	private double wage;

	// dividends collected in the last step
	@Getter
	private double dividends;

	// total income in the last step (dividends + interest)
	@Getter
	private double income;

	// consecutive days this noble has been insolvent (a net debtor: wealth < 0),
	// reset to 0 on any solvent step. The rank-ladder demotion trigger reads this:
	// a noble ruined (insolvent) past a grace period is demoted back to a laborer
	// (see SimulationHarness). Only *sustained* insolvency counts, so a noble that
	// earns its way back into the black keeps its rank.
	@Getter
	private int consecutiveInsolventDays = 0;

	// consumption ($) in the last step, and its necessity/enjoyment split
	@Getter
	private double consumption;
	@Getter
	private double nConsumption;
	@Getter
	private double eConsumption;

	// remaining necessity to buy this step to reach the stockpile target (set in
	// act); +inf means uncapped, so the noble spends its whole necessity budget as
	// before (the default when it does not stockpile)
	private double nGap = Double.POSITIVE_INFINITY;

	// demand strategies posted to those markets: spend each budget at the going
	// price (nobles have no minimum-necessity floor — they never starve). The
	// necessity demand is additionally capped at nGap when the noble stockpiles.
	private final Demand demandForE = price -> eConsumption / price;
	private final Demand demandForN = price -> Math.min(nGap, nConsumption / price);

	/**
	 * Create a new (founding) noble owning <tt>ownedFirms</tt>, endowed with a
	 * fresh opening balance.
	 *
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 * @param ownedFirms
	 *            the firms this noble owns (a defensive copy is kept)
	 * @param ownedBanks
	 *            the banks this noble owns (a defensive copy is kept)
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which this noble holds its accounts
	 * @param colony
	 *            the colony this noble belongs to
	 */
	public Noble(double initCheckingBal, double initSavingsBal,
			List<Firm> ownedFirms, List<Bank> ownedBanks, NobleConfig config,
			Bank bank, Settlement colony) {
		// a founding noble takes the colony's founding race
		this(initCheckingBal, initSavingsBal, false,
				combine(ownedFirms, ownedBanks), config, bank, colony, null,
				colony.getFoundingRace());
	}

	// firms first, then banks, into one properties list — preserving the historical
	// dividend-collection order (all firms before all banks)
	private static List<Property> combine(List<Firm> firms, List<Bank> banks) {
		List<Property> properties = new ArrayList<>(firms);
		properties.addAll(banks);
		return properties;
	}

	/**
	 * Create the noble household that succeeds <tt>predecessor</tt> when its head
	 * dies: it inherits the predecessor's estate (its account balances, funded
	 * out of the bank's equity so money stays in circulation) <b>and the
	 * ownership of the same firms</b>, and continues the same dynasty (a new head,
	 * same surname), banking at the same bank. A fresh working-age head is drawn.
	 *
	 * @param predecessor
	 *            the deceased noble whose estate, firms, banks and dynasty are
	 *            inherited
	 * @param config
	 *            tunable model parameters
	 * @param colony
	 *            the colony this noble belongs to
	 */
	public Noble(Noble predecessor, NobleConfig config, Settlement colony) {
		// an heir continues its dynasty, so it keeps the line's race (no re-roll)
		this(predecessor.getEstateChecking(), predecessor.getEstateSavings(),
				true, predecessor.properties, config,
				predecessor.getBank(), colony, predecessor.getHead().surname(),
				predecessor.getHead().race());
	}

	/**
	 * Create a noble by <b>elevating an existing commoner household</b> — e.g. a
	 * laborer the colony's dynamic firm provisioning ennobles to own a chartered firm
	 * when the colony has no noble yet. The new noble <b>adopts the household's
	 * head</b> (the caller transfers any further members afterwards), keeping its
	 * identity — name, skills and age — so the rise is meritocratic, and opens a fresh
	 * account at <tt>bank</tt> (the silver bank) with the balances carried over from
	 * its former commoner account. It owns no firms or banks at first (the caller
	 * grants it the firm it was raised to own).
	 *
	 * @param head
	 *            the elevated household's head, adopted as this noble's head
	 * @param initCheckingBal
	 *            checking balance carried over from the former account
	 * @param initSavingsBal
	 *            savings balance carried over (negative for a loan)
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the (silver) bank at which this noble now holds its accounts
	 * @param colony
	 *            the colony this noble belongs to
	 */
	public Noble(Member head, double initCheckingBal, double initSavingsBal,
			NobleConfig config, Bank bank, Settlement colony) {
		super(initCheckingBal, initSavingsBal, false, head, bank, colony);

		// the new noble is a person of interest the colony tracks
		colony.addPersonOfInterest(this);
		// HOLDING scope, not HOUSEHOLD: a noble IS a holding (the firms and banks it owns), so a
		// ruler at VILLAGE still hears about it — its vassals are exactly the rung below — while an
		// ordinary household's affairs do not reach that far.
		SimLog.event(Rank.HOLDING, Level.FINE, String.format(
				"%s was ennobled — risen from commoner to noble, now banking in %s.",
				getHead().fullName(), bank.getCurrency()));

		this.config = config;
		this.properties = new ArrayList<>();
		this.enjoyment = new Enjoyment(0);
		this.necessity = new Necessity(0);
		this.eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		this.nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
		this.nobleLaborMkt =
				(LaborMarket) colony.getMarket(StrategicFirm.LABOR_MARKET);
		this.scholarLaborMkt =
				(LaborMarket) colony.getMarket(ScienceFirm.LABOR_MARKET);
		// this noble works the strategic firm if one exists, exactly like any other —
		// but it is created mid-run, so (unlike a founding noble, whose constructor
		// posting is consumed by the pre-run primeNobleLabor clear) it does NOT post
		// labor here: it posts in its first act() instead, which avoids a duplicate
		// posting that would otherwise have it hired twice on its first working step.
	}

	/**
	 * Shared constructor. Opens the account either as a fresh endowment
	 * ({@code inherited == false}) or out of the bank's equity
	 * ({@code inherited == true}, for a successor noble), then initializes the
	 * rest of the household identically.
	 */
	private Noble(double initCheckingBal, double initSavingsBal,
			boolean inherited, List<Property> ownedProperties,
			NobleConfig config, Bank bank, Settlement colony, String surname,
			Race race) {
		super(initCheckingBal, initSavingsBal, inherited, surname, race, bank, colony);

		// every noble is a person of interest the colony tracks (and logs in its
		// yearly roster); a notable one (skill above the threshold) is also worth
		// recording by name at its founding
		colony.addPersonOfInterest(this);
		if (isNotable()) {
			var skills = getHead().skills();
			SimLog.event(Rank.HOLDING, Level.FINE, String.format(
					"%s founded a noble house in the colony — notable in %s (level %d); %s",
					getHead().fullName(), skills.peakSkill(), skills.peakLevel(),
					skills));
		}

		this.config = config;
		this.properties = new ArrayList<>(ownedProperties);
		// a bank this noble owns is named after its house (a same-dynasty heir keeps
		// the surname, so the name carries across successions); a shared bank the noble
		// merely banks at, but does not own, is untouched
		for (Property property : properties)
			if (property instanceof Bank ownedBank)
				ownedBank.setName(getHead().surname() + " Bank");
		this.enjoyment = new Enjoyment(0);
		this.necessity = new Necessity(0);
		this.eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		this.nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");

		// if the colony runs a strategic sector, the noble works it: join the
		// noble-only labor market now so the strategic firm has workers before
		// step 0 (like a laborer posts in its constructor). Absent that market the
		// noble is a pure rentier and this is a no-op.
		this.nobleLaborMkt =
				(LaborMarket) colony.getMarket(StrategicFirm.LABOR_MARKET);
		this.scholarLaborMkt =
				(LaborMarket) colony.getMarket(ScienceFirm.LABOR_MARKET);
		postNobleLabor();
	}

	// supply every household member (head and any spouse) to the noble labor markets,
	// each with its own skills; all wages credit the one household account. A noble
	// supplies its intellectual labor to BOTH the export sector (NobleLabor) and the
	// science firm (ScholarLabor) — a scholar-merchant doing double duty — so it posts
	// to each market that exists (a no-op for one the colony lacks).
	private void postNobleLabor() {
		LocalDate today = getColony().getDate();
		for (Member member : getMembers()) {
			// children of the house supply no labor (they are schooled, not put to
			// work) — only adult members work the export / science sectors
			if (!member.isAdult(today))
				continue;
			if (nobleLaborMkt != null)
				nobleLaborMkt.addEmployee(getID(), getBank(), 1.0, member.skills());
			if (scholarLaborMkt != null)
				scholarLaborMkt.addEmployee(getID(), getBank(), 1.0, member.skills());
		}
	}

	/**
	 * Called by Settlement.newDay() in each step.
	 */
	public void act() {
		Bank bank = getBank();
		Account acct = bank.getAcct(getID());

		// the head may die of old age; its estate folds into the bank's equity,
		// and a successor of the same dynasty inherits both the estate and the
		// firms (see addReplacementPolicy)
		if (checkOldAgeDeath())
			return;

		// wage earned from strategic-sector labor last step (0 if the noble does
		// not work — no strategic sector, so priIC is never credited)
		wage = acct.priIC;

		// collect dividends: draw a share of each property's distributable profit and
		// move it from the property to this noble via the secondary-income channel. A
		// firm reports max(0, profit) and disburses from its own account (which may
		// differ from the noble's, so disburse + credit is split, as elsewhere); a
		// bank reports its retained profit and disburses by skimming its equity,
		// leaving the inheritance / external-funds buffer untouched. A dead firm
		// reports 0, so it is skipped (its closed account is never touched).
		dividends = 0;
		for (Property property : properties) {
			double share = config.dividendRate() * property.distributableProfit();
			if (share > 0) {
				property.disburse(share);
				bank.credit(getID(), share, Bank.SECIC);
				dividends += share;
			}
		}

		// income this step is the wage and dividends just credited plus interest
		income = wage + acct.secIC + acct.interest;

		double checking = acct.getChecking();
		double savings = acct.getSavings();

		// track sustained insolvency for the demotion trigger: a net-debtor step
		// (wealth < 0) advances the counter; any solvent step resets it
		if (checking + savings < 0)
			consecutiveInsolventDays++;
		else
			consecutiveInsolventDays = 0;

		// spend a steady fraction of liquid wealth on consumer goods so dividend
		// income flows back into the markets; deposit the remainder (a negative
		// remainder draws on savings to fund consumption, as for a laborer). Floor the
		// wealth at 0 so a noble in debt (e.g. one just ennobled from an indebted
		// laborer) spends nothing rather than posting a negative consumption demand.
		consumption = config.consumptionRate() * Math.max(0, checking + savings);
		nConsumption = consumption * config.necessityShare();
		eConsumption = consumption - nConsumption;
		bank.deposit(getID(), checking - consumption);

		// the noble household eats per member each step from its larder (it never
		// starves — it simply draws its table down, then refills it toward the reserve
		// target below): an adult eats the LAVISH ration, a child the smaller child
		// ration (so a noble's children eat like any colony child, not lavishly).
		LocalDate today = getColony().getDate();
		necessity.decrease(householdDailyNeed(RationSize.LAVISH.perDay(),
				getColony().getFertilityConfig().childRation().perDay(), today));

		// bear a child: a wed noble couple with a fertile female and a stocked larder
		// bears a child — a new child-ration-eating member of the house — exactly as a
		// laborer household does (the universal birth mechanism). A noble child becomes
		// the hereditary heir by promotion when the head dies, so the line continues
		// through its own offspring rather than a fresh-drawn successor. See
		// docs/births.md.
		bearChildIfFertile(necessity.getQuantity(), RationSize.LAVISH.perDay());

		// if the noble keeps a necessity reserve, cap this step's necessity buying
		// at the gap to its target stock so it fills toward the target "if possible"
		// and then holds; otherwise leave it uncapped (spend the whole necessity
		// budget, as before)
		nGap = config.necessityReserveDays() > 0
				? Math.max(0, necessityStockTarget() - necessity.getQuantity())
				: Double.POSITIVE_INFINITY;

		// post buy offers; the markets settle them in clear()
		eMkt.addBuyOffer(this, demandForE);
		nMkt.addBuyOffer(this, demandForN);

		// supply labor to the strategic sector for next round (if the colony has
		// one); the strategic firm pays a wage and takes each member's skill-scaled
		// labor when the noble-labor market clears
		postNobleLabor();

		// if unmarried, seek a spouse on the wedding market (it weds on weekends)
		seekSpouseIfSingle();

		// reset income accumulators so next step's income is counted fresh
		resetIncomeAccumulators(acct);
	}

	/**
	 * The noble's necessity-stockpile target, in units: {@code necessityReserveDays
	 * × (own household size)} — that many days of necessity for its <b>own</b>
	 * household (a member eats one unit a day), not the population. So the noble buys
	 * a modest larder, then holds; it does not hoard food in proportion to the labor
	 * force (which starved the workforce out of the necessity market — a noble buys
	 * necessity every step but never eats it, so an uncapped target accumulates
	 * without bound).
	 *
	 * @return the noble's own-household necessity stockpile target in units
	 */
	private double necessityStockTarget() {
		return config.necessityReserveDays() * getMemberCount();
	}

	/** Role label used in the persons-of-interest roster and death log. */
	@Override
	public String role() {
		return "Noble";
	}

	/**
	 * The housing gate (build economy B3b): a noble on a build-economy colony needs a
	 * current house — commissioned from the BuilderFirm — to wed.
	 */
	@Override
	public boolean housedForGate() {
		return hasCurrentHouse();
	}

	/** A noble commands a {@link Rank#HOLDING} — the firms and estates it owns. */
	@Override
	public Rank rank() {
		return Rank.HOLDING;
	}

	/** Nobles wed after the ruler but before laborers. */
	@Override
	public int weddingPriority() {
		return 1;
	}

	/**
	 * The heir who succeeds this noble: a same-dynasty household inheriting the
	 * estate and the ownership of the firms and banks (the colony's built-in
	 * replacement policy calls this, so no simulation need wire a noble rule).
	 */
	@Override
	public Agent successor(Settlement colony) {
		return new Noble(this, config, colony);
	}

	/**
	 * A noble founding a new dynasty draws its surname from the rarest tier, so it
	 * carries a rare, distinctive house name (e.g. a Harimari clan-name). (Ennobled
	 * nobles keep the commoner surname they rose with, and a successor continues its
	 * dynasty's surname — this governs only a noble that starts a fresh dynasty.)
	 */
	@Override
	protected boolean drawsRareDynasty() {
		return true;
	}

	/**
	 * Take ownership of <tt>firm</tt> (e.g. a firm the ruler has just chartered and
	 * granted to this noble): from the next step it draws a dividend from the firm
	 * like any it owns. The colony's dynamic firm provisioning uses this.
	 *
	 * @param firm
	 *            the firm to add to this noble's property
	 */
	public void addFirm(Firm firm) {
		properties.add(firm);
	}

	/**
	 * Relinquish ownership of <tt>firm</tt> (e.g. when it is dissolved), so the
	 * noble stops drawing a dividend from it.
	 *
	 * @param firm
	 *            the firm to remove from this noble's property
	 * @return whether this noble owned the firm
	 */
	public boolean removeFirm(Firm firm) {
		return properties.remove(firm);
	}

	/** Number of firms this noble currently owns (used to spread newly chartered
	 * firms across owners; counts firms specifically, not any owned banks). */
	public int getFirmCount() {
		return (int) properties.stream().filter(h -> h instanceof Firm).count();
	}

	/**
	 * The fief this noble holds — the plot granted to it (its manor ground, where its palace is
	 * raised), or {@code null} for a landless noble (docs/estate-system.md, vassalage P3).
	 *
	 * @return this noble's fief plot, or {@code null}
	 */
	public com.civstudio.settlement.Plot getFief() {
		return fief;
	}

	/**
	 * Grant this noble a fief — its manor ground (the ennoblement / grant seam). Passing {@code
	 * null} makes it landless.
	 *
	 * @param fief the plot this noble now holds, or {@code null}
	 */
	public void setFief(com.civstudio.settlement.Plot fief) {
		this.fief = fief;
	}

	/**
	 * Hand all of this noble's property — its owned {@link Firm firms} and {@link
	 * Bank banks} — to <tt>heir</tt>, after which this noble owns nothing. Used
	 * before a ruined noble is demoted to a laborer (which owns no property), so its
	 * firms and banks keep a rentier owner drawing their dividends rather than being
	 * orphaned.
	 *
	 * @param heir
	 *            the noble that takes over this one's property
	 */
	public void transferPropertyTo(Noble heir) {
		heir.properties.addAll(properties);
		properties.clear();
	}

	/**
	 * Return a reference to the good with name <tt>goodName</tt>.
	 */
	public Good getGood(String goodName) {
		if (goodName.equals("Enjoyment"))
			return enjoyment;
		if (goodName.equals("Necessity"))
			return necessity;
		return null;
	}

	/** Units of necessity the noble currently holds (its reserve, see {@link
	 * NobleConfig#necessityReserveDays()}). */
	public double getNecessityStock() {
		return necessity.getQuantity();
	}

	/**
	 * A concise, debug-friendly summary: id, household head, and the latest
	 * income/consumption snapshot. Uses only cached fields, so it is safe even if
	 * the noble's account has been closed.
	 */
	@Override
	public String toString() {
		int firmCount = getFirmCount();
		return String.format(
				"Noble#%d %s [%s age=%d firms=%d banks=%d dividends=%.2f income=%.2f consumption=%.2f]",
				getID(), getHead().fullName(), isAlive() ? "alive" : "dead",
				getAgeYears(), firmCount, properties.size() - firmCount, dividends,
				income, consumption);
	}
}
