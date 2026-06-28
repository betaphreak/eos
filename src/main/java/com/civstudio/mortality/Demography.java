package com.civstudio.mortality;

import java.util.EnumMap;
import java.util.Map;

import com.civstudio.agent.Retinue;
import com.civstudio.agent.Household;
import com.civstudio.name.Gender;
import com.civstudio.race.Race;
import com.civstudio.skill.Passion;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillRecord;
import com.civstudio.skill.SkillTracker;
import com.civstudio.util.Rng;

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
	// working age. The working-age floor is per race (see Race.minInitAgeYears) —
	// faster-maturing races start younger — so it is read from the person's race,
	// not a shared constant; the spread is shared.
	private static final double INIT_AGE_STDDEV_YEARS = 10;

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

	// each race ages and dies on its own mortality schedule (see Race.lifeTable());
	// the HUMAN entry is overridable via the constructor's table param so a test (or
	// a session) can supply a custom human schedule. A non-degenerate colony rolls
	// each person's race (sampleRace) and the old-age check reads the dying head's
	// race's table; a single-race human colony only ever touches HUMAN, unchanged.
	private final Map<Race, LifeTable> lifeTables;

	/**
	 * Create a demographic service drawing both age/mortality and skill from
	 * <tt>rng</tt> and the default {@link LifeTable#LENIENT} schedule.
	 * Convenience for callers that don't need the two draw streams decorrelated
	 * (e.g. tests); the session uses {@link #Demography(Rng, Rng, LifeTable)}.
	 *
	 * @param rng
	 *            the generator used for all demographic draws
	 */
	public Demography(Rng rng) {
		this(rng, rng, LifeTable.LENIENT);
	}

	/**
	 * Create a demographic service drawing age/mortality from <tt>rng</tt>, skill
	 * from <tt>skillRng</tt>, and using the default
	 * {@link LifeTable#LENIENT} schedule.
	 *
	 * @param rng
	 *            the generator used for mortality and initial-age draws
	 * @param skillRng
	 *            the generator used for skill draws (separate so skill does not
	 *            perturb the mortality stream)
	 */
	public Demography(Rng rng, Rng skillRng) {
		this(rng, skillRng, LifeTable.LENIENT);
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
		// each race carries its own schedule; the human one is the supplied table
		// (so the LENIENT default — or a test's custom table — applies to humans)
		this.lifeTables = new EnumMap<>(Race.class);
		for (Race r : Race.values())
			lifeTables.put(r, r.lifeTable());
		lifeTables.put(Race.HUMAN, table);
	}

	/**
	 * Draw an initial age (in days) for a founding household head of the given race
	 * from a normal distribution centered on <tt>meanYears</tt> (sd
	 * {@value #INIT_AGE_STDDEV_YEARS} years), truncated below at the race's
	 * {@link Race#minInitAgeYears() working-age floor} so every founding head is of
	 * working age.
	 *
	 * @param meanYears
	 *            mean of the age distribution, in years
	 * @param race
	 *            the head's race (sets the working-age floor)
	 * @return an age in days
	 */
	public int sampleInitialAgeDays(double meanYears, Race race) {
		int minYears = race.minInitAgeYears();
		int years;
		do {
			years = (int) Math.round(
					rng.gaussian(meanYears, INIT_AGE_STDDEV_YEARS));
		} while (years < minYears);
		return years * DAYS_PER_YEAR + rng.uniform(DAYS_PER_YEAR);
	}

	/**
	 * Draw the age (in days) of a <b>young, fresh</b> adult of the given race — a
	 * working age drawn uniformly in the race's young-adult range
	 * [{@link Race#youngAdultMinYears()}, {@link Race#youngAdultMaxYears()}] years —
	 * on the age/mortality RNG. Used for an immigrant recruited into the
	 * {@link Retinue}, so a recruit is a long-lived addition rather than
	 * drawn from the older founding-age spread.
	 *
	 * @param race
	 *            the recruit's race (sets the young-adult age range)
	 * @return a young-adult age in days
	 */
	public int sampleYoungAdultAgeDays(Race race) {
		int min = race.youngAdultMinYears();
		int span = race.youngAdultMaxYears() - min + 1;
		int years = min + rng.uniform(span);
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
		return SkillTracker.of(records);
	}

	/**
	 * Draw a {@link Gender} at random (50/50 MALE/FEMALE) on the skill RNG — the
	 * same stream the gendered skill mean feeds into, kept off the mortality and
	 * economic streams. Used when generating people into the {@link
	 * Retinue}.
	 *
	 * @return a randomly drawn gender
	 */
	public Gender sampleGender() {
		return skillRng.uniform() < 0.5 ? Gender.MALE : Gender.FEMALE;
	}

	/**
	 * Roll a person's {@link Race} against a colony's race-mix weight map, on the
	 * skill RNG (the same demographic stream {@link #sampleGender}/{@link
	 * #newSkillTracker} use, kept off the mortality and economic streams).
	 * <p>
	 * The roll is <b>gated on a non-degenerate mix</b>: a {@code null}, empty, or
	 * single-entry map draws <b>no</b> randomness and returns the lone race (or
	 * {@link Race#HUMAN}). This is what keeps a single-race colony — every current
	 * scenario — byte-identical: the human-only mix never touches the RNG. Only a
	 * genuine multi-race mix rolls, weighted by the map's values.
	 *
	 * @param raceMix
	 *            race &rarr; weight (need not be normalized); degenerate maps skip
	 *            the roll
	 * @return the rolled race
	 */
	public Race sampleRace(Map<Race, Double> raceMix) {
		if (raceMix == null || raceMix.isEmpty())
			return Race.HUMAN;
		if (raceMix.size() == 1)
			return raceMix.keySet().iterator().next();
		double total = 0;
		for (double w : raceMix.values())
			total += w;
		double r = skillRng.uniform() * total;
		// iterate in a fixed Race.values() order (NOT the map's, whose iteration order
		// is unspecified — e.g. Map.of randomizes it per JVM) so the weighted draw is
		// reproducible across runs for a given seed
		Race last = Race.HUMAN;
		for (Race race : Race.values()) {
			Double w = raceMix.get(race);
			if (w == null)
				continue;
			last = race;
			r -= w;
			if (r < 0)
				return race;
		}
		return last; // floating-point slack: fall back to the last entry
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
	 * Decide whether a person of the given age and race dies of old age today, on
	 * its race's mortality schedule.
	 *
	 * @param ageDays
	 *            the person's age in days
	 * @param race
	 *            the person's ancestry (selects the mortality schedule)
	 * @return true if the person dies of old age this step
	 */
	public boolean diesOfOldAge(int ageDays, Race race) {
		return rng.uniform() < lifeTables.get(race)
				.dailyDeathProb(ageDays / DAYS_PER_YEAR);
	}
}
