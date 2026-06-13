package eos.agent;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eos.bank.Account;
import eos.bank.Bank;
import eos.mortality.Demography;
import eos.name.Person;
import eos.settlement.Settlement;
import eos.skill.SkillTracker;
import lombok.Getter;

/**
 * Base class for every {@link Household} agent — a named dynasty that ages on
 * the mortality schedule and is succeeded by an heir when its head dies. It
 * consolidates the machinery that {@link eos.agent.laborer.Laborer},
 * {@link eos.agent.noble.Noble} and {@link eos.agent.ruler.Ruler} previously
 * each carried verbatim:
 * <ul>
 * <li>the household <b>identity</b> — its {@link #getMembers() members} (for now
 * a single {@link Person}, the {@code head}, drawn with a given-name rarity
 * tracking skill), the head's {@code birthDate}, the household's
 * {@code foundingDate}, and its {@code skill} — all sampled at construction on
 * the demographic / naming RNGs (never the economic stream);</li>
 * <li><b>account opening</b>, either as a fresh endowment or funded out of the
 * bank's equity (a successor's inheritance or an open-colony immigrant);</li>
 * <li><b>age</b> ({@link #ageDays()} / {@link #getAgeYears()}) and liquid
 * {@link #getWealth() wealth};</li>
 * <li><b>death settlement</b> — snapshotting the estate so a successor of the
 * same dynasty can inherit it, then closing the account
 * ({@link #dieAndSettleEstate()}), with the common old-age check
 * ({@link #checkOldAgeDeath()}) on top.</li>
 * </ul>
 * Subclasses supply only what differs: how the household earns and spends in
 * {@link #act()}, which goods it holds in {@link #getGood(String)}, and any
 * person-of-interest registration or founding log they wish to emit. The
 * constructor draws skill before the head, exactly as the original classes did,
 * so identity values stay byte-identical for a given seed.
 */
public abstract class AbstractHousehold extends Agent implements Household {

	// the people who make up this household; members.get(0) is the head, whose
	// (unique, for a new dynasty) surname names the dynasty. For now every
	// household is founded with a single member — the head — set at construction;
	// the list is the seam for a household to grow past size 1 later.
	private final List<Person> members = new ArrayList<>();

	// in-game birth date of the head (the source of truth for its age)
	@Getter
	private final LocalDate birthDate;

	// in-game date this household came into being (its already-grown head
	// arrived and founded it); distinct from the head's birthDate
	@Getter
	private final LocalDate foundingDate;

	// estate (checking, savings) snapshot taken at death so a successor can
	// inherit it; savings is negative for an outstanding loan
	private double estateChecking, estateSavings;

	/**
	 * Open this household's accounts and draw its identity (age, skill, named
	 * head). Account funding follows {@code fundedFromEquity}: a fresh endowment
	 * (a founding household) when false, or drawn out of the bank's equity (a
	 * successor's inheritance, or an externally-bankrolled immigrant) when true.
	 *
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance (negative for an opening loan)
	 * @param fundedFromEquity
	 *            true to open the account out of the bank's equity instead of as
	 *            a fresh endowment
	 * @param surname
	 *            an existing dynasty surname to continue, or {@code null} to
	 *            start a new dynasty with a fresh unique surname
	 * @param bank
	 *            the bank at which this household holds its accounts
	 * @param colony
	 *            the colony this household belongs to
	 */
	protected AbstractHousehold(double initCheckingBal, double initSavingsBal,
			boolean fundedFromEquity, String surname, Bank bank,
			Settlement colony) {
		super(bank, colony);

		if (fundedFromEquity)
			bank.openInheritedAcct(getID(), initCheckingBal, initSavingsBal);
		else
			bank.openAcct(getID(), initCheckingBal, initSavingsBal);

		// the head is aged on the separate mortality RNG: its birth date is today
		// less a sampled working-age span. The household itself is founded now.
		Demography demography = colony.getDemography();
		this.birthDate = colony.getDate().minusDays(
				demography.sampleInitialAgeDays(colony.getMeanInitAgeYears()));
		this.foundingDate = colony.getDate();

		// build the head's skills — one randomized record per skill — on the
		// demographic skill RNG (a separate stream), a fresh draw for every head
		// (founders, immigrants, and each successor), so skill is not inherited.
		// Done before the head is named so the given name's rarity can track the
		// head's overall skill, exactly as the single scalar skill did before.
		SkillTracker skills = demography.newSkillTracker(colony.getMeanSkill());

		// draw the head on the naming RNG with the given name's rarity tracking
		// its overall skill; a null surname starts a new dynasty, else continue the
		// given one. The named person carries its skills, and is the household's
		// sole member for now.
		double nameRarity = (double) skills.overallLevel() / Household.MAX_SKILL;
		Person head = (surname == null)
				? colony.getNames().nextHead(nameRarity).withSkills(skills)
				: colony.getNames().nextHeadInDynasty(surname, nameRarity)
						.withSkills(skills);
		members.add(head);
	}

	/**
	 * The people who make up this household. The first member is the
	 * {@linkplain #getHead() head}, whose surname names the dynasty; the returned
	 * list is an unmodifiable view, head first. For now a household always has
	 * exactly one member.
	 *
	 * @return an unmodifiable view of the household's members, head first
	 */
	@Override
	public List<Person> getMembers() {
		return Collections.unmodifiableList(members);
	}

	/**
	 * The head of this household — its first {@linkplain #getMembers() member}.
	 *
	 * @return the household head
	 */
	@Override
	public Person getHead() {
		return members.get(0);
	}

	/**
	 * This household's skill scalar in {@code [Household.MIN_SKILL, MAX_SKILL]}:
	 * the {@linkplain SkillTracker#overallLevel() overall level} of the head's
	 * skills. Drives labor productivity, notability and given-name rarity — the
	 * single skill number the economy reads, now derived from the head's
	 * twelve-skill tracker rather than stored on the household.
	 *
	 * @return the head's overall skill level
	 */
	@Override
	public int getSkill() {
		return getHead().skills().overallLevel();
	}

	/**
	 * The predecessor's estate checking balance, for a successor's constructor to
	 * pass back in as its opening balance (drawn out of equity).
	 *
	 * @return the snapshotted estate checking balance
	 */
	protected final double getEstateChecking() {
		return estateChecking;
	}

	/**
	 * The predecessor's estate savings balance (negative for an inherited loan).
	 *
	 * @return the snapshotted estate savings balance
	 */
	protected final double getEstateSavings() {
		return estateSavings;
	}

	/**
	 * The head's age in days: the span from its birth date to the colony's
	 * current date.
	 *
	 * @return the head's age in days
	 */
	protected final int ageDays() {
		return (int) ChronoUnit.DAYS.between(birthDate, getColony().getDate());
	}

	@Override
	public final int getAgeYears() {
		return ageDays() / 365;
	}

	/** Liquid wealth: checking plus savings (savings negative for a loan). */
	public double getWealth() {
		return getBank().getChecking(getID()) + getBank().getSavings(getID());
	}

	/**
	 * Settle this household's death: mark it dead, snapshot its estate so a
	 * successor of the same dynasty can inherit it, and fold the account into the
	 * bank's equity. Call this from {@link #act()} on any death path (old age or,
	 * for those that can, starvation).
	 */
	protected final void dieAndSettleEstate() {
		Account acct = getBank().getAcct(getID());
		die();
		estateChecking = acct.getChecking();
		estateSavings = acct.getSavings();
		getBank().inheritAndClose(getID());
	}

	/**
	 * If the head dies of old age this step, settle the estate
	 * ({@link #dieAndSettleEstate()}) and return true so the caller can stop its
	 * step early; otherwise return false.
	 *
	 * @return true if the head died of old age this step
	 */
	protected final boolean checkOldAgeDeath() {
		if (getColony().getDemography().diesOfOldAge(ageDays())) {
			dieAndSettleEstate();
			return true;
		}
		return false;
	}

	/**
	 * Reset the per-step income accumulators so next step's income is counted
	 * fresh. Call at the end of {@link #act()}.
	 *
	 * @param acct
	 *            this household's account
	 */
	protected final void resetIncomeAccumulators(Account acct) {
		acct.priIC = 0;
		acct.secIC = 0;
		acct.interest = 0;
	}
}