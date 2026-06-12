package eos.agent.noble;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import eos.agent.Agent;
import eos.agent.Household;
import eos.agent.firm.Firm;
import eos.agent.firm.StrategicFirm;
import eos.bank.Account;
import eos.bank.Bank;
import eos.settlement.Settlement;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
import eos.market.LaborMarket;
import eos.name.Person;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * A noble: the owner of one or more firms (and, in principle, banks), who lives
 * off the surplus they produce rather than off wages. A noble does <b>not</b>
 * sell labor — it is a pure rentier that participates in the colony on the
 * demand side, exactly as sketched in "option A": its dividend income is spent
 * back into the consumer-good markets, so it influences the labor market only
 * indirectly (consumption → firm revenue → the labor-share wage budget).
 * <p>
 * Like a {@link eos.agent.laborer.Laborer}, a noble is a household identified by
 * a {@link Person} {@code head} drawn the same way — {@code
 * colony.getNames().nextHead()} — so it carries a male given name and a unique
 * dynasty surname from the session's name pool (on the separate naming RNG, so
 * naming a noble never perturbs the economic random stream). It also ages and
 * dies on the same schedule: the head carries a {@code birthDate} and may die
 * of old age each step against the {@code mortality/} life table, whereupon a
 * <b>successor of the same dynasty</b>
 * inherits the estate <i>and the ownership of the firms</i> (wired via {@link
 * Settlement#addReplacementPolicy}), so the aristocracy persists across generations.
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
public class Noble extends Agent implements Household {

	// tunable model parameters
	private final NobleConfig config;

	// head of this noble household: a male given name plus a dynasty surname,
	// drawn exactly as a laborer household's head is
	@Getter
	private final Person head;

	// in-game birth date of the head (the source of truth for its age)
	@Getter
	private final LocalDate birthDate;

	// in-game date this noble house came into being in the colony (its head
	// arrived and founded it); distinct from the head's birthDate
	@Getter
	private final LocalDate foundingDate;

	// this household's skill (0..20), drawn around the colony's mean skill like a
	// laborer's. It sets the noble's labor productivity if the colony runs a
	// strategic sector (see nobleLaborMkt); otherwise the noble sells no labor.
	@Getter
	private final int skill;

	// labor produced per step when employed by the strategic firm, derived from
	// skill by the same productivity curve laborers use (skill 10 -> 1 unit)
	private final double productivity;

	// the noble-only labor market the strategic firm employs from, or null when
	// the colony has no strategic sector (then the noble sells no labor and is
	// byte-identical to the pure-rentier design)
	private final LaborMarket nobleLaborMkt;

	// estate (checking, savings) snapshot taken at death so a successor noble can
	// inherit it; savings is negative for an outstanding loan
	private double estateChecking, estateSavings;

	// the firms this noble owns and draws dividends from
	private final List<Firm> firms;

	// the banks this noble owns and draws dividends from
	private final List<Bank> banks;

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

	// consumption ($) in the last step, and its necessity/enjoyment split
	@Getter
	private double consumption;
	@Getter
	private double nConsumption;
	@Getter
	private double eConsumption;

	// demand strategies posted to those markets: spend each budget at the going
	// price (nobles have no minimum-necessity floor — they never starve)
	private final Demand demandForE = price -> eConsumption / price;
	private final Demand demandForN = price -> nConsumption / price;

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
		this(initCheckingBal, initSavingsBal, false, ownedFirms, ownedBanks,
				config, bank, colony, null);
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
		this(predecessor.estateChecking, predecessor.estateSavings, true,
				predecessor.firms, predecessor.banks, config,
				predecessor.getBank(), colony, predecessor.head.surname());
	}

	/**
	 * Shared constructor. Opens the account either as a fresh endowment
	 * ({@code inherited == false}) or out of the bank's equity
	 * ({@code inherited == true}, for a successor noble), then initializes the
	 * rest of the household identically.
	 */
	private Noble(double initCheckingBal, double initSavingsBal,
			boolean inherited, List<Firm> ownedFirms, List<Bank> ownedBanks,
			NobleConfig config, Bank bank, Settlement colony, String surname) {
		super(bank, colony);
		if (inherited)
			bank.openInheritedAcct(getID(), initCheckingBal, initSavingsBal);
		else
			bank.openAcct(getID(), initCheckingBal, initSavingsBal);

		// aged the same way as a laborer household head: a working-age birth date
		// sampled on the separate mortality RNG. The house is founded now.
		this.birthDate = colony.getDate().minusDays(colony.getDemography()
				.sampleInitialAgeDays(colony.getMeanInitAgeYears()));
		this.foundingDate = colony.getDate();

		// skill is drawn around the colony mean exactly as for a laborer (each
		// head re-rolls), and sets the noble's labor productivity for the
		// strategic sector
		this.skill = colony.getDemography().sampleSkill(colony.getMeanSkill());
		this.productivity = Household.productivityOf(skill);

		// the head is named on the naming RNG with the given name's rarity tracking
		// skill, like a laborer; a null surname starts a new dynasty, else continue
		double nameRarity = (double) skill / Household.MAX_SKILL;
		this.head = (surname == null)
				? colony.getNames().nextHead(nameRarity)
				: colony.getNames().nextHeadInDynasty(surname, nameRarity);

		// every noble is a person of interest the colony tracks (and logs in its
		// yearly roster); a notable one (skill above the threshold) is also worth
		// recording by name at its founding
		colony.addPersonOfInterest(this);
		if (isNotable())
			log.info(String.format(
					"%s founded a noble house in the colony — notable (skill %d)",
					head.fullName(), skill));

		this.config = config;
		this.firms = new ArrayList<>(ownedFirms);
		this.banks = new ArrayList<>(ownedBanks);
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
		if (nobleLaborMkt != null)
			nobleLaborMkt.addEmployee(getID(), bank, productivity);
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
		if (getColony().getDemography().diesOfOldAge(ageDays())) {
			die();
			estateChecking = acct.getChecking();
			estateSavings = acct.getSavings();
			bank.inheritAndClose(getID());
			return;
		}

		// wage earned from strategic-sector labor last step (0 if the noble does
		// not work — no strategic sector, so priIC is never credited)
		wage = acct.priIC;

		// collect dividends: draw a share of each owned firm's positive profit,
		// moving retained earnings from the firm to this noble via the secondary-
		// income channel (the firm's bank may differ from the noble's, so the
		// transfer is split into a withdraw and a credit, as elsewhere)
		dividends = 0;
		for (Firm firm : firms) {
			if (!firm.isAlive())
				continue;
			double share = config.dividendRate() * Math.max(0, firm.getProfit());
			if (share > 0) {
				firm.getBank().withdraw(firm.getID(), share);
				bank.credit(getID(), share, Bank.SECIC);
				dividends += share;
			}
		}

		// dividends from owned banks: skim a share of each bank's retained profit
		// (interest spread + transaction fees) straight out of its equity,
		// leaving the inheritance / external-funds buffer that also sits in equity
		// untouched
		for (Bank b : banks) {
			double share = config.dividendRate() * b.getDistributableProfit();
			if (share > 0) {
				b.payDividend(share);
				bank.credit(getID(), share, Bank.SECIC);
				dividends += share;
			}
		}

		// income this step is the wage and dividends just credited plus interest
		income = wage + acct.secIC + acct.interest;

		double checking = acct.getChecking();
		double savings = acct.getSavings();

		// spend a steady fraction of liquid wealth on consumer goods so dividend
		// income flows back into the markets; deposit the remainder (a negative
		// remainder draws on savings to fund consumption, as for a laborer)
		consumption = config.consumptionRate() * (checking + savings);
		nConsumption = consumption * config.necessityShare();
		eConsumption = consumption - nConsumption;
		bank.deposit(getID(), checking - consumption);

		// post buy offers; the markets settle them in clear()
		eMkt.addBuyOffer(this, demandForE);
		nMkt.addBuyOffer(this, demandForN);

		// supply labor to the strategic sector for next round (if the colony has
		// one); the strategic firm pays a wage and takes the noble's skill-scaled
		// labor when the noble-labor market clears
		if (nobleLaborMkt != null)
			nobleLaborMkt.addEmployee(getID(), getBank(), productivity);

		// reset income accumulators so next step's income is counted fresh
		acct.priIC = 0;
		acct.secIC = 0;
		acct.interest = 0;
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

	/** Liquid wealth: checking plus savings (savings negative for a loan). */
	public double getWealth() {
		return getBank().getChecking(getID()) + getBank().getSavings(getID());
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
	 * A concise, debug-friendly summary: id, household head, and the latest
	 * income/consumption snapshot. Uses only cached fields, so it is safe even if
	 * the noble's account has been closed.
	 */
	@Override
	public String toString() {
		return String.format(
				"Noble#%d %s [%s age=%d firms=%d banks=%d dividends=%.2f income=%.2f consumption=%.2f]",
				getID(), head.fullName(), isAlive() ? "alive" : "dead",
				getAgeYears(), firms.size(), banks.size(), dividends, income,
				consumption);
	}
}
