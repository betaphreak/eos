package eos.agent;

import java.util.List;

import eos.bank.Bank;
import eos.name.Person;
import eos.settlement.Settlement;

/**
 * An agent that is a <b>household</b> headed by a named {@link Person} carrying a
 * unique dynasty surname — a laborer or a noble. The surname identifies the
 * dynasty. When such a household dies <em>without</em> a successor (its
 * replacement policy yields none), its dynasty is extinct and the surname can be
 * recycled back into the drawable pool (see
 * {@link eos.name.NameRegistry#releaseDynastyName(String)}), so a finite name
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
	 * The people who make up this household; the first member is the
	 * {@linkplain #getHead() head}, whose surname names the dynasty. For now every
	 * household has exactly one member (its head), set at colony creation; the list
	 * is the seam for households to grow past size 1 later.
	 *
	 * @return the household's members, head first
	 */
	List<Person> getMembers();

	/**
	 * The head of this household — its first {@linkplain #getMembers() member},
	 * whose surname names the dynasty.
	 *
	 * @return the household head
	 */
	default Person getHead() {
		return getMembers().get(0);
	}

	/**
	 * The number of people in this household.
	 *
	 * @return the household size (currently always 1)
	 */
	default int getMemberCount() {
		return getMembers().size();
	}

	/**
	 * This household's skill, an integer in {@code [0, 20]}.
	 *
	 * @return the skill level
	 */
	int getSkill();

	/**
	 * The household head's age in whole years.
	 *
	 * @return the head's age in years
	 */
	int getAgeYears();

	/**
	 * Whether this is a <b>notable</b> household: its skill exceeds
	 * {@value #NOTABLE_SKILL}. Such a household's head is worth logging by name
	 * when the household is created.
	 *
	 * @return true if the household is notable
	 */
	default boolean isNotable() {
		return getSkill() > NOTABLE_SKILL;
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
