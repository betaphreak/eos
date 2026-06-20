package com.civstudio.agent;

import java.util.List;

import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.market.WeddingMarket;
import com.civstudio.bank.Bank;
import com.civstudio.name.NameRegistry;
import com.civstudio.name.Person;
import com.civstudio.settlement.Settlement;

/**
 * An agent that is a <b>household</b> headed by a named {@link Person} carrying a
 * unique dynasty surname — a laborer or a noble. The surname identifies the
 * dynasty. When such a household dies <em>without</em> a successor (its
 * replacement policy yields none), its dynasty is extinct and the surname can be
 * recycled back into the drawable pool (see
 * {@link NameRegistry#releaseDynastyName(String)}), so a finite name
 * pool is not drained over a long run or across many colonies in one session.
 * <p>
 * Every household also carries a {@link #getSkill() skill} — an integer in
 * {@code [0, 20]} drawn at birth around the colony's mean skill. For a laborer it
 * sets labor productivity ({@link #productivityOf(int)}); on a noble it is
 * carried for later use (nobles sell no labor today). A household with skill
 * above {@value #NOTABLE_SKILL} is {@link #isNotable() notable} — worth logging
 * by name when it comes into being.
 */
public interface Household {

	/** Inclusive lower bound of the skill scale. */
	int MIN_SKILL = 0;

	/** Inclusive upper bound of the skill scale. */
	int MAX_SKILL = 20;

	/** A household with skill strictly above this is notable. */
	int NOTABLE_SKILL = 15;

	/**
	 * The {@link Member people} who make up this household; the first member is the
	 * {@linkplain #getHead() head}, whose surname names the dynasty. For now every
	 * household has exactly one member (its head), set at colony creation; the list
	 * is the seam for households to grow past size 1 later. Each {@code Member}
	 * wraps a {@link Person} (its name and skills) with its own birth date, age and
	 * old-age mortality, so members can age and die independently as households grow.
	 *
	 * @return the household's members, head first
	 */
	List<Member> getMembers();

	/**
	 * The head of this household — its first {@linkplain #getMembers() member},
	 * whose surname names the dynasty.
	 *
	 * @return the household head
	 */
	default Member getHead() {
		return getMembers().get(0);
	}

	/**
	 * The number of people in this household. A household with exactly one member
	 * (just its head) has no spouse and so seeks one on the {@link
	 * WeddingMarket}; a married household has two.
	 *
	 * @return the household size
	 */
	default int getMemberCount() {
		return getMembers().size();
	}

	/**
	 * Add a member to this household — e.g. a spouse wed off the {@link
	 * WeddingMarket} (a peasant taken out of the pool and given this
	 * household's surname). The new member ages, eats and works as part of the
	 * household.
	 *
	 * @param member
	 *            the member to add
	 */
	void addMember(Member member);

	/**
	 * This household's unique agent ID (also its bank account number); every
	 * household is an {@link Agent}, so the {@link WeddingMarket} can
	 * route the bride-price through its account.
	 *
	 * @return the household's agent ID
	 */
	int getID();

	/**
	 * This household's rank in the {@link WeddingMarket}'s priority
	 * order — higher weds first, so a higher-ranked household gets first pick of
	 * the ablest spouses. The ruler outranks nobles, who outrank laborers.
	 *
	 * @return the wedding priority (higher = weds first; default 0)
	 */
	default int weddingPriority() {
		return 0;
	}

	/**
	 * This household's skill, an integer in {@code [0, 20]}.
	 *
	 * @return the skill level
	 */
	int getSkill();

	/**
	 * This household's <b>peak</b> skill: the head's single highest skill level,
	 * in {@code [0, 20]}. Unlike {@link #getSkill()} (the all-round average across
	 * the twelve skills) this captures mastery of one specialty, and is what
	 * {@link #isNotable()} reads — a master of one craft is notable even if
	 * unremarkable on average.
	 *
	 * @return the head's highest single skill level
	 */
	int getPeakSkill();

	/**
	 * The household head's age in whole years.
	 *
	 * @return the head's age in years
	 */
	int getAgeYears();

	/**
	 * Whether this is a <b>notable</b> household: its {@linkplain #getPeakSkill()
	 * peak} (single highest) skill exceeds {@value #NOTABLE_SKILL} — i.e. the head
	 * has mastered some specialty. Such a household's head is worth logging by name
	 * when the household is created. (Notability reads the peak, not the all-round
	 * {@link #getSkill() average}: averaging twelve skills around a low colony mean
	 * can never reach the threshold, so a master would otherwise never register.)
	 *
	 * @return true if the household is notable
	 */
	default boolean isNotable() {
		return getPeakSkill() > NOTABLE_SKILL;
	}

	/**
	 * This household's {@link Rank} — the scope of what it commands in the realm's
	 * hierarchy. Defaults to {@link Rank#HOUSEHOLD} (a household commanding only its
	 * own family, what a laborer is); types that command more override it (a
	 * {@link Noble} commands a {@link Rank#HOLDING}, a
	 * {@link Ruler} a {@link Rank#VILLAGE}). The rank ladder's
	 * promotion/demotion (see {@code docs/rank-ladder.md}) reads this to find the
	 * adjacent rank to reform a household into.
	 *
	 * @return the household's rank
	 */
	default Rank rank() {
		return Rank.HOUSEHOLD;
	}

	/**
	 * Whether this household belongs to the colony's <b>workforce</b> — the
	 * population whose total disappearance ends the colony's life (see
	 * {@link Settlement#isDead()}). A laborer is workforce; rentier households
	 * such as nobles and the ruler are not. Defaults to false, so a new
	 * population type does not silently prop the colony up.
	 *
	 * @return true if this household counts toward the colony's workforce
	 */
	default boolean isWorkforce() {
		return false;
	}

	/**
	 * A short, human-readable label for this household's population role, used in
	 * logs and the persons-of-interest roster (e.g. "Noble", "Ruler", "Notable
	 * laborer"). Defaults to the class simple name, which a type may override.
	 *
	 * @return the household's role label
	 */
	default String role() {
		return getClass().getSimpleName();
	}

	/**
	 * Produce the heir that succeeds this household when its head dies: a new
	 * household of the same dynasty, banking at the same bank, that inherits this
	 * one's estate (and any holdings). Return {@code null} if the household cannot
	 * replace itself from its own state — then a replacement policy registered
	 * with the colony (e.g. by the simulation harness, which holds the seeding
	 * constants a laborer's successor needs) must supply the heir instead.
	 * <p>
	 * The colony registers a built-in policy that calls this for every dead
	 * household, so a self-replacing population type (most rentier/owner types)
	 * is succeeded automatically with no per-simulation wiring.
	 *
	 * @param colony
	 *            the colony the heir will belong to
	 * @return the successor household, or {@code null} if none
	 */
	default Agent successor(Settlement colony) {
		return null;
	}

	/**
	 * This household's latest income, in copper, for reporting.
	 *
	 * @return the latest income
	 */
	double getIncome();

	/**
	 * This household's liquid wealth (checking plus savings), in copper, for
	 * reporting.
	 *
	 * @return the liquid wealth
	 */
	double getWealth();

	/**
	 * The bank at which this household holds its accounts (its currency is the
	 * household's denomination for display).
	 *
	 * @return the household's bank
	 */
	Bank getBank();

	/**
	 * Labor productivity of a household with the given skill, anchored at
	 * skill&nbsp;0&nbsp;&rarr;&nbsp;0.01, skill&nbsp;10&nbsp;&rarr;&nbsp;1 (the
	 * legacy homogeneous unit) and skill&nbsp;20&nbsp;&rarr;&nbsp;8. The curve is
	 * <b>piecewise</b>, steepening once past the reference level so that mastery
	 * is disproportionately rewarded:
	 * <ul>
	 * <li>up to skill&nbsp;10 it rises with the square of skill
	 * ({@code (skill/10)^2}), floored at 0.01 — so an ordinary skill-5 worker
	 * still produces 0.25, unchanged;</li>
	 * <li>above skill&nbsp;10 it rises with the cube ({@code (skill/10)^3}),
	 * reaching 8 at skill&nbsp;20 (a specialist far outproduces a journeyman)
	 * rather than the 4 a pure square would give.</li>
	 * </ul>
	 * The two pieces meet continuously at skill&nbsp;10 (both equal 1).
	 *
	 * @param skill
	 *            a skill level (expected in {@code [0, 20]})
	 * @return the labor produced per step by an employed household of that skill
	 */
	static double productivityOf(int skill) {
		double s = skill / 10.0;
		if (skill <= 10)
			return Math.max(0.01, s * s);
		return s * s * s;
	}
}
