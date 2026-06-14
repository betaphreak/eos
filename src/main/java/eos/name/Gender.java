package eos.name;

/**
 * A person's gender. Carried by every {@link Person}; for now it influences a
 * person's given-name table (male vs. female names) and the mean of its skill
 * distribution (see {@code Demography.sampleGender} and {@code
 * Settlement.getMeanSkill(Gender)}). Households whose heads are drawn directly
 * (nobles, the ruler, and laborers founded without a pool) are {@link #MALE} by
 * default; only people generated into the {@link eos.agent.PeasantPool} currently
 * roll a random gender.
 */
public enum Gender {
	MALE,
	FEMALE;
}
