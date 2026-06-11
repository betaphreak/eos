package eos.agent.noble;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import eos.agent.Agent;
import eos.agent.firm.Firm;
import eos.bank.Account;
import eos.bank.Bank;
import eos.economy.Economy;
import eos.good.Enjoyment;
import eos.good.Good;
import eos.good.Necessity;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
import eos.name.Person;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * A noble: the owner of one or more firms (and, in principle, banks), who lives
 * off the surplus they produce rather than off wages. A noble does <b>not</b>
 * sell labor — it is a pure rentier that participates in the economy on the
 * demand side, exactly as sketched in "option A": its dividend income is spent
 * back into the consumer-good markets, so it influences the labor market only
 * indirectly (consumption → firm revenue → the labor-share wage budget).
 * <p>
 * Like a {@link eos.agent.laborer.Laborer}, a noble is a household identified by
 * a {@link Person} {@code head} drawn the same way — {@code
 * economy.getNames().nextHead()} — so it carries a male given name and a unique
 * dynasty surname from the session's name pool (on the separate naming RNG, so
 * naming a noble never perturbs the economic random stream). It also ages and
 * dies on the same schedule: when mortality is enabled the head carries a
 * {@code birthDate} and may die of old age each step against the {@code
 * mortality/} life table, whereupon a <b>successor of the same dynasty</b>
 * inherits the estate <i>and the ownership of the firms</i> (wired via {@link
 * Economy#addReplacementPolicy}), so the aristocracy persists across generations.
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
public class Noble extends Agent {

	// tunable model parameters
	private final NobleConfig config;

	// head of this noble household: a male given name plus a dynasty surname,
	// drawn exactly as a laborer household's head is
	@Getter
	private final Person head;

	// in-game birth date of the head (the source of truth for its age). Null when
	// mortality is disabled, since heads neither age nor die of old age then.
	@Getter
	private final LocalDate birthDate;

	// estate (checking, savings) snapshot taken at death so a successor noble can
	// inherit it; savings is negative for an outstanding loan
	private double estateChecking, estateSavings;

	// the firms this noble owns and draws dividends from
	private final List<Firm> firms;

	// enjoyment and necessity the noble consumes
	private final Enjoyment enjoyment;
	private final Necessity necessity;

	// consumer-good markets the noble buys from
	private final ConsumerGoodMarket eMkt;
	private final ConsumerGoodMarket nMkt;

	// demand strategies posted to those markets
	private final DemandForE demandForE = new DemandForE();
	private final DemandForN demandForN = new DemandForN();

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

	/* demand for enjoyment: spend the enjoyment budget at the going price */
	private class DemandForE implements Demand {
		public double getDemand(double price) {
			return eConsumption / price;
		}
	}

	/* demand for necessity: spend the necessity budget at the going price */
	private class DemandForN implements Demand {
		public double getDemand(double price) {
			return nConsumption / price;
		}
	}

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
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which this noble holds its accounts
	 * @param economy
	 *            the economy this noble belongs to
	 */
	public Noble(double initCheckingBal, double initSavingsBal,
			List<Firm> ownedFirms, NobleConfig config, Bank bank,
			Economy economy) {
		this(initCheckingBal, initSavingsBal, false, ownedFirms, config, bank,
				economy, economy.getNames().nextHead());
	}

	/**
	 * Create the noble household that succeeds <tt>predecessor</tt> when its head
	 * dies: it inherits the predecessor's estate (its account balances, funded
	 * out of the bank's equity so money stays in circulation) <b>and the
	 * ownership of the same firms</b>, and continues the same dynasty (a new head,
	 * same surname), banking at the same bank. A fresh working-age head is drawn.
	 *
	 * @param predecessor
	 *            the deceased noble whose estate, firms and dynasty are inherited
	 * @param config
	 *            tunable model parameters
	 * @param economy
	 *            the economy this noble belongs to
	 */
	public Noble(Noble predecessor, NobleConfig config, Economy economy) {
		this(predecessor.estateChecking, predecessor.estateSavings, true,
				predecessor.firms, config, predecessor.getBank(), economy,
				economy.getNames()
						.nextHeadInDynasty(predecessor.head.surname()));
	}

	/**
	 * Shared constructor. Opens the account either as a fresh endowment
	 * ({@code inherited == false}) or out of the bank's equity
	 * ({@code inherited == true}, for a successor noble), then initializes the
	 * rest of the household identically.
	 */
	private Noble(double initCheckingBal, double initSavingsBal,
			boolean inherited, List<Firm> ownedFirms, NobleConfig config,
			Bank bank, Economy economy, Person head) {
		super(bank, economy);
		if (inherited)
			bank.openInheritedAcct(getID(), initCheckingBal, initSavingsBal);
		else
			bank.openAcct(getID(), initCheckingBal, initSavingsBal);

		// named the same way as a laborer household head, and aged the same way:
		// a working-age birth date sampled on the separate mortality RNG (only
		// when mortality is active, so a no-mortality run stays age-less)
		this.head = head;
		this.birthDate = economy.isMortalityEnabled()
				? economy.getDate().minusDays(economy.getDemography()
						.sampleInitialAgeDays(economy.getMeanInitAgeYears()))
				: null;

		this.config = config;
		this.firms = new ArrayList<>(ownedFirms);
		this.enjoyment = new Enjoyment(0);
		this.necessity = new Necessity(0);
		this.eMkt = (ConsumerGoodMarket) economy.getMarket("Enjoyment");
		this.nMkt = (ConsumerGoodMarket) economy.getMarket("Necessity");
	}

	/**
	 * Called by Economy.newDay() in each step.
	 */
	public void act() {
		Bank bank = getBank();
		Account acct = bank.getAcct(getID());

		// the head may die of old age (only when mortality is active); its estate
		// folds into the bank's equity, and a successor of the same dynasty
		// inherits both the estate and the firms (see addReplacementPolicy)
		if (getEconomy().isMortalityEnabled()
				&& getEconomy().getDemography().diesOfOldAge(ageDays())) {
			die();
			log.info(String.format(
					"%s (noble %d, b. %s) died of old age at %d",
					head.fullName(), getID(), birthDate, getAgeYears()));
			estateChecking = acct.getChecking();
			estateSavings = acct.getSavings();
			bank.inheritAndClose(getID());
			return;
		}

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

		// income this step is the dividends just credited plus interest earned
		income = acct.secIC + acct.interest;

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
		return (int) ChronoUnit.DAYS.between(birthDate, getEconomy().getDate());
	}

	/**
	 * Return the head's age in whole years, or 0 when mortality is disabled
	 * (heads have no birth date and do not age).
	 *
	 * @return the head's age in years
	 */
	public int getAgeYears() {
		return birthDate == null ? 0 : ageDays() / 365;
	}

	/**
	 * A concise, debug-friendly summary: id, household head, and the latest
	 * income/consumption snapshot. Uses only cached fields, so it is safe even if
	 * the noble's account has been closed.
	 */
	@Override
	public String toString() {
		return String.format(
				"Noble#%d %s [%s age=%d firms=%d dividends=%.2f income=%.2f consumption=%.2f]",
				getID(), head.fullName(), isAlive() ? "alive" : "dead",
				getAgeYears(), firms.size(), dividends, income, consumption);
	}
}
