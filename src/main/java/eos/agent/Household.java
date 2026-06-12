package eos.agent;

import eos.name.Person;

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
	 * The head of this household, whose surname names the dynasty.
	 *
	 * @return the household head
	 */
	Person getHead();

	/**
	 * This household's skill, an integer in {@code [0, 20]}.
	 *
	 * @return the skill level
	 */
	int getSkill();

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
	 * Labor productivity of a household with the given skill: a quadratic curve
	 * through the anchors skill&nbsp;0&nbsp;&rarr;&nbsp;0.01,
	 * skill&nbsp;10&nbsp;&rarr;&nbsp;1 (the legacy homogeneous unit), and
	 * skill&nbsp;20&nbsp;&rarr;&nbsp;4. Productivity rises with the square of skill
	 * ({@code (skill/10)^2}), floored at 0.01 so even an unskilled (skill&nbsp;0)
	 * household produces a sliver of labor.
	 *
	 * @param skill
	 *            a skill level (expected in {@code [0, 20]})
	 * @return the labor produced per step by an employed household of that skill
	 */
	static double productivityOf(int skill) {
		double s = skill / 10.0;
		return Math.max(0.01, s * s);
	}
}
