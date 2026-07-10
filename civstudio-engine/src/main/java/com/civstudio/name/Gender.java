package com.civstudio.name;

import com.civstudio.agent.Retinue;
import com.civstudio.market.WeddingMarket;

/**
 * A person's gender. Carried by every {@link Person}; for now it influences a
 * person's given-name table (male vs. female names) and the mean of its skill
 * distribution (see {@code Demography.sampleGender} and {@code
 * Settlement.getMeanSkill(Gender)}). Households whose heads are drawn directly
 * (nobles, the ruler, and laborers founded without a pool) are {@link #MALE} by
 * default; only people generated into the {@link Retinue} currently
 * roll a random gender.
 */
public enum Gender {
	MALE,
	FEMALE;

	/**
	 * The opposite gender — used by the {@link WeddingMarket} to match
	 * a household head with a spouse of the opposite gender.
	 *
	 * @return {@link #FEMALE} for {@link #MALE} and vice versa
	 */
	public Gender opposite() {
		return this == MALE ? FEMALE : MALE;
	}
}
