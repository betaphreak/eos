package eos.mortality;

import eos.agent.Household;
import eos.util.Rng;

/**
 * The demographic service for a game session: a {@link LifeTable} plus the
 * random-number generators used for the household draws made at birth —
 * mortality/age on one generator, and the household's <b>skill</b> endowment on
 * a second, separate one. Owned by a {@code GameSession} and shared with its
 * colonies, on generators separate from the economic one so these draws are
 * deterministic yet do not perturb the economic random stream (and skill draws
 * do not perturb the mortality stream either).
 */
public final class Demography {

	private static final int DAYS_PER_YEAR = 365;

	// founding-cohort initial ages are drawn from a normal distribution centered
	// on a caller-supplied mean, truncated below so every founding head is of
	// working age
	private static final double INIT_AGE_STDDEV_YEARS = 10;
	private static final int MIN_INIT_AGE_YEARS = 15;

	// a household's skill is an integer in [Household.MIN_SKILL, MAX_SKILL] drawn
	// from a normal distribution centered on a caller-supplied mean (a
	// colony-start property), sd SKILL_STDDEV, clamped to the range — a natural
	// spread of mostly low-to-middling skill with the occasional adept
	private static final double SKILL_STDDEV = 3;

	private final Rng rng;
	private final Rng skillRng;
	private final LifeTable table;

	/**
	 * Create a demographic service drawing both age/mortality and skill from
	 * <tt>rng</tt> and the default {@link LifeTable#WEST_LEVEL_3} schedule.
	 * Convenience for callers that don't need the two draw streams decorrelated
	 * (e.g. tests); the session uses {@link #Demography(Rng, Rng, LifeTable)}.
	 *
	 * @param rng
	 *            the generator used for all demographic draws
	 */
	public Demography(Rng rng) {
		this(rng, rng, LifeTable.WEST_LEVEL_3);
	}

	/**
	 * Create a demographic service drawing age/mortality from <tt>rng</tt>, skill
	 * from <tt>skillRng</tt>, and using the default
	 * {@link LifeTable#WEST_LEVEL_3} schedule.
	 *
	 * @param rng
	 *            the generator used for mortality and initial-age draws
	 * @param skillRng
	 *            the generator used for skill draws (separate so skill does not
	 *            perturb the mortality stream)
	 */
	public Demography(Rng rng, Rng skillRng) {
		this(rng, skillRng, LifeTable.WEST_LEVEL_3);
	}

	/**
	 * Create a demographic service drawing age/mortality from <tt>rng</tt>, skill
	 * from <tt>skillRng</tt>, and mortality from <tt>table</tt>.
	 *
	 * @param rng
	 *            the generator used for mortality and initial-age draws
	 * @param skillRng
	 *            the generator used for skill draws
	 * @param table
	 *            the mortality schedule
	 */
	public Demography(Rng rng, Rng skillRng, LifeTable table) {
		this.rng = rng;
		this.skillRng = skillRng;
		this.table = table;
	}

	/**
	 * Draw an initial age (in days) for a founding household head from a normal
	 * distribution centered on <tt>meanYears</tt> (sd
	 * {@value #INIT_AGE_STDDEV_YEARS} years), truncated below at
	 * {@value #MIN_INIT_AGE_YEARS} so every founding head is of working age.
	 *
	 * @param meanYears
	 *            mean of the age distribution, in years
	 * @return an age in days
	 */
	public int sampleInitialAgeDays(double meanYears) {
		int years;
		do {
			years = (int) Math.round(
					rng.gaussian(meanYears, INIT_AGE_STDDEV_YEARS));
		} while (years < MIN_INIT_AGE_YEARS);
		return years * DAYS_PER_YEAR + rng.uniform(DAYS_PER_YEAR);
	}

	/**
	 * Draw a household's skill: an integer in
	 * [{@link Household#MIN_SKILL}, {@link Household#MAX_SKILL}] from a normal
	 * distribution centered on <tt>meanSkill</tt> (sd {@value #SKILL_STDDEV}),
	 * clamped to the range. Skill maps to labor productivity (see
	 * {@code Household.productivityOf}); a mean well below the unit-productivity
	 * skill of 10 makes the typical household unskilled. The mean is a
	 * colony-start property, so different colonies can be founded more or less
	 * skilled.
	 *
	 * @param meanSkill
	 *            center of the skill distribution (the colony's mean skill)
	 * @return a skill level in [{@link Household#MIN_SKILL},
	 *         {@link Household#MAX_SKILL}]
	 */
	public int sampleSkill(double meanSkill) {
		int skill = (int) Math.round(skillRng.gaussian(meanSkill, SKILL_STDDEV));
		return Math.max(Household.MIN_SKILL,
				Math.min(Household.MAX_SKILL, skill));
	}

	/**
	 * Decide whether a household head of the given age dies of old age today.
	 *
	 * @param ageDays
	 *            the head's age in days
	 * @return true if the head dies of old age this step
	 */
	public boolean diesOfOldAge(int ageDays) {
		return rng.uniform() < table.dailyDeathProb(ageDays / DAYS_PER_YEAR);
	}
}
