package com.civstudio.agent;

import com.civstudio.name.Gender;

/**
 * One {@link TitleMode register} of a {@link Rank}'s styling: the gendered titles
 * borne by the rank's holder plus a short description of the role in that register.
 * For most ranks the two genders share a title (e.g. "Captain"/"Captain"); the
 * feudal ranks differ ("Baron"/"Baroness", "Duke"/"Duchess").
 *
 * @param male
 *            the title borne by a male holder
 * @param female
 *            the title borne by a female holder
 * @param description
 *            a short description of the role in this register
 */
public record TitleSet(String male, String female, String description) {

	/**
	 * The title for a holder of the given gender — {@link #female()} for
	 * {@link Gender#FEMALE}, otherwise {@link #male()}.
	 *
	 * @param gender
	 *            the holder's gender
	 * @return the gender-appropriate title
	 */
	public String forGender(Gender gender) {
		return gender == Gender.FEMALE ? female : male;
	}
}
