package eos.mortality;

import java.util.EnumMap;
import java.util.Map;

import eos.agent.Household;
import eos.name.Gender;
import eos.skill.Passion;
import eos.skill.Skill;
import eos.skill.SkillRecord;
import eos.skill.SkillTracker;
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

	// a "young and fresh" immigrant's age is drawn uniformly in this inclusive
	// year range (a young working adult — a long-lived addition to the pool)
	private static final int YOUNG_ADULT_MIN_YEARS = 16;
	private static final int YOUNG_ADULT_MAX_YEARS = 25;

	// a household's skill is an integer in [Household.MIN_SKILL, MAX_SKILL] drawn
	// from a normal distribution centered on a caller-supplied mean (a
	// colony-start property), sd SKILL_STDDEV, clamped to the range — a natural
	// spread of mostly low-to-middling skill with the occasional adept
	private static final double SKILL_STDDEV = 3;

	// per-skill passion is assigned at random (placeholder weights): below the
	// first threshold NONE, below the second MINOR, else MAJOR — so roughly
	// 60% / 30% / 10% NONE / MINOR / MAJOR
	private static final double PASSION_NONE_BELOW = 0.6;
	private static final double PASSION_MINOR_BELOW = 0.9;

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
	 * Draw the age (in days) of a <b>young, fresh</b> adult — a working age drawn
	 * uniformly in [{@value #YOUNG_ADULT_MIN_YEARS},
	 * {@value #YOUNG_ADULT_MAX_YEARS}] years — on the age/mortality RNG. Used for an
	 * immigrant recruited into the {@link eos.agent.PeasantPool}, so a recruit is a
	 * long-lived addition rather than drawn from the older founding-age spread.
	 *
	 * @return a young-adult age in days
	 */
	public int sampleYoungAdultAgeDays() {
		int span = YOUNG_ADULT_MAX_YEARS - YOUNG_ADULT_MIN_YEARS + 1;
		int years = YOUNG_ADULT_MIN_YEARS + rng.uniform(span);
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
	 * Build a fresh {@link SkillTracker} for a new person: one randomized
	 * {@link SkillRecord} per {@link Skill}, drawn on the skill RNG. Each skill's
	 * starting level is drawn around <tt>meanSkill</tt> (as {@link
	 * #sampleSkill(double)}) and its {@link Passion} is assigned at random
	 * (roughly 60/30/10 NONE/MINOR/MAJOR) — a RimWorld-style spread, minus the
	 * genetic aptitudes and traits this model omits.
	 *
	 * @param meanSkill
	 *            center of each skill's starting-level distribution (the colony's
	 *            mean skill)
	 * @return a new tracker holding all twelve randomized records
	 */
	public SkillTracker newSkillTracker(double meanSkill) {
		Map<Skill, SkillRecord> records = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values())
			records.put(s, new SkillRecord(sampleSkill(meanSkill), samplePassion()));
		return new SkillTracker(records);
	}

	/**
	 * Draw a {@link Gender} at random (50/50 MALE/FEMALE) on the skill RNG — the
	 * same stream the gendered skill mean feeds into, kept off the mortality and
	 * economic streams. Used when generating people into the {@link
	 * eos.agent.PeasantPool}.
	 *
	 * @return a randomly drawn gender
	 */
	public Gender sampleGender() {
		return skillRng.uniform() < 0.5 ? Gender.MALE : Gender.FEMALE;
	}

	// draw a passion on the skill RNG using the placeholder weights
	private Passion samplePassion() {
		double r = skillRng.uniform();
		if (r < PASSION_NONE_BELOW)
			return Passion.NONE;
		if (r < PASSION_MINOR_BELOW)
			return Passion.MINOR;
		return Passion.MAJOR;
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
