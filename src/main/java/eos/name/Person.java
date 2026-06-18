package eos.name;

import java.util.Objects;

import eos.race.Race;
import eos.skill.SkillTracker;

/**
 * A named individual: a given name, a dynasty (household) surname, a {@link
 * Gender}, and the person's {@link SkillTracker skills}. In the current model
 * each household is represented by its head, so the surname identifies the
 * household and the given name identifies the head.
 * <p>
 * A person's identity is its name — {@link #equals(Object)} / {@link #hashCode()}
 * are over the given name and surname only, not the gender, {@link Race race} or
 * the (mutable) skills. The {@code skills} may be {@code null} for a bare name
 * produced by the {@link NameRegistry} before a household attaches skills via
 * {@link #withSkills(SkillTracker)}.
 * <p>
 * {@link #race()} is the person's ancestry (default {@link Race#HUMAN}); it is
 * metadata alongside the gender and skills, carried per person so a colony can
 * hold residents of several races (see {@code docs/race.md}).
 */
public final class Person {

	private final String givenName;
	private final String surname;
	private final Gender gender;
	private final SkillTracker skills;
	private final Race race;

	/**
	 * Create a {@link Gender#MALE male} person with a name but no skills yet (a
	 * household attaches them via {@link #withSkills(SkillTracker)}).
	 *
	 * @param givenName
	 *            the individual's given name (e.g. "James")
	 * @param surname
	 *            the dynasty / household surname (e.g. "Smith")
	 */
	public Person(String givenName, String surname) {
		this(givenName, surname, Gender.MALE, null);
	}

	/**
	 * Create a {@link Gender#MALE male} person with a name and skills.
	 *
	 * @param givenName
	 *            the individual's given name
	 * @param surname
	 *            the dynasty / household surname
	 * @param skills
	 *            the person's skills (may be null)
	 */
	public Person(String givenName, String surname, SkillTracker skills) {
		this(givenName, surname, Gender.MALE, skills);
	}

	/**
	 * Create a {@link Race#HUMAN human} person with a name, gender and skills.
	 *
	 * @param givenName
	 *            the individual's given name
	 * @param surname
	 *            the dynasty / household surname
	 * @param gender
	 *            the individual's gender
	 * @param skills
	 *            the person's skills (may be null)
	 */
	public Person(String givenName, String surname, Gender gender,
			SkillTracker skills) {
		this(givenName, surname, gender, skills, Race.HUMAN);
	}

	/**
	 * Create a person with a name, gender, skills and {@link Race}.
	 *
	 * @param givenName
	 *            the individual's given name
	 * @param surname
	 *            the dynasty / household surname
	 * @param gender
	 *            the individual's gender
	 * @param skills
	 *            the person's skills (may be null)
	 * @param race
	 *            the individual's ancestry
	 */
	public Person(String givenName, String surname, Gender gender,
			SkillTracker skills, Race race) {
		this.givenName = givenName;
		this.surname = surname;
		this.gender = gender;
		this.skills = skills;
		this.race = race;
	}

	/** @return the individual's given name */
	public String givenName() {
		return givenName;
	}

	/** @return the dynasty / household surname */
	public String surname() {
		return surname;
	}

	/** @return the individual's gender */
	public Gender gender() {
		return gender;
	}

	/** @return the person's skills, or {@code null} if none have been attached */
	public SkillTracker skills() {
		return skills;
	}

	/** @return the person's ancestry (default {@link Race#HUMAN}) */
	public Race race() {
		return race;
	}

	/**
	 * Return a copy of this person carrying the given skills (the name, gender and
	 * race are unchanged).
	 *
	 * @param skills
	 *            the skills to attach
	 * @return a person with this name, gender, race and the given skills
	 */
	public Person withSkills(SkillTracker skills) {
		return new Person(givenName, surname, gender, skills, race);
	}

	/** The full name, given name followed by surname (e.g. "James Smith"). */
	public String fullName() {
		return givenName + " " + surname;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof Person other))
			return false;
		return Objects.equals(givenName, other.givenName)
				&& Objects.equals(surname, other.surname);
	}

	@Override
	public int hashCode() {
		return Objects.hash(givenName, surname);
	}

	@Override
	public String toString() {
		return fullName();
	}
}
