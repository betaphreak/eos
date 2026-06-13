package eos.skill;

/**
 * A person's passion for a given {@link Skill}: how keenly they learn it.
 * Passion is per-person, per-skill, and scales the experience gained when
 * {@linkplain SkillRecord#learn(double) learning} that skill.
 */
public enum Passion {

	/** No passion: learns slowly. */
	NONE(0.35),

	/** Minor passion: the baseline learn rate. */
	MINOR(1.0),

	/** Major passion: learns fastest. */
	MAJOR(1.5);

	/** Learn-rate factor for {@link #NONE}. */
	public static final double LEARN_FACTOR_PASSION_NONE = 0.35;

	/** Learn-rate factor for {@link #MINOR}. */
	public static final double LEARN_FACTOR_PASSION_MINOR = 1.0;

	/** Learn-rate factor for {@link #MAJOR}. */
	public static final double LEARN_FACTOR_PASSION_MAJOR = 1.5;

	private final double learnRateFactor;

	Passion(double learnRateFactor) {
		this.learnRateFactor = learnRateFactor;
	}

	/**
	 * The factor by which this passion scales experience gained while learning:
	 * {@value #LEARN_FACTOR_PASSION_NONE} for {@link #NONE},
	 * {@value #LEARN_FACTOR_PASSION_MINOR} for {@link #MINOR},
	 * {@value #LEARN_FACTOR_PASSION_MAJOR} for {@link #MAJOR}.
	 *
	 * @return the learn-rate factor
	 */
	public double learnRateFactor() {
		return learnRateFactor;
	}
}
