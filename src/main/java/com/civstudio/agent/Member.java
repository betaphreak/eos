package com.civstudio.agent;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.civstudio.mortality.Demography;
import com.civstudio.name.Gender;
import com.civstudio.name.NameRegistry;
import com.civstudio.race.Race;
import com.civstudio.name.Person;
import com.civstudio.skill.SkillTracker;

/**
 * A {@link Person} situated in a {@link Household}: the name-and-skills identity
 * plus the per-person <i>world</i> state a household member carries — its own
 * birth date, age and aliveness, and its own old-age mortality roll. Splitting
 * this from {@code Person} keeps that class a pure naming/skills value (the
 * product of {@link NameRegistry}) while giving each member of a
 * multi-person household independent age and death.
 * <p>
 * For now a household is founded with a single member (its head); this is the
 * unit that will age and die independently once households grow past size 1.
 * <p>
 * A {@code Member} has <b>reference identity</b> on purpose (no {@code equals}
 * override): two distinct people who happen to draw the same given+surname are
 * still distinct members, sidestepping {@link Person}'s name-only identity.
 */
public final class Member {

	private final Person person;
	// this person's biological birth date — the source of truth for its age
	private final LocalDate birthDate;
	private boolean alive = true;

	/**
	 * Create a living member from a named, skilled person and its birth date.
	 *
	 * @param person
	 *            the member's name-and-skills identity
	 * @param birthDate
	 *            the member's biological birth date
	 */
	public Member(Person person, LocalDate birthDate) {
		this.person = person;
		this.birthDate = birthDate;
	}

	/** @return the underlying name-and-skills identity */
	public Person person() {
		return person;
	}

	// --- delegators, so existing getHead().X call sites are unaffected ---

	/** @return the member's full name (given name + surname) */
	public String fullName() {
		return person.fullName();
	}

	/** @return the member's dynasty / household surname */
	public String surname() {
		return person.surname();
	}

	/** @return the member's gender */
	public Gender gender() {
		return person.gender();
	}

	/** @return the member's ancestry */
	public Race race() {
		return person.race();
	}

	/** @return the member's skills */
	public SkillTracker skills() {
		return person.skills();
	}

	/** @return this member's biological birth date */
	public LocalDate getBirthDate() {
		return birthDate;
	}

	/** @return whether this member is still alive */
	public boolean isAlive() {
		return alive;
	}

	/**
	 * This member's age in days as of {@code today}.
	 *
	 * @param today
	 *            the colony's current date
	 * @return the member's age in days
	 */
	public int ageDays(LocalDate today) {
		return (int) ChronoUnit.DAYS.between(birthDate, today);
	}

	/**
	 * This member's age in whole years as of {@code today}.
	 *
	 * @param today
	 *            the colony's current date
	 * @return the member's age in years
	 */
	public int getAgeYears(LocalDate today) {
		return ageDays(today) / 365;
	}

	/**
	 * Roll this member's old-age death for {@code today}: if the mortality
	 * schedule fires, mark the member dead and return true; a no-op (returns
	 * false) once the member is already dead. Settling the estate stays the
	 * household's job (see {@link AbstractHousehold#checkOldAgeDeath()}), because
	 * that is a household-level event, not a per-person one.
	 *
	 * @param demography
	 *            the colony's demographic service (holds the mortality schedule)
	 * @param today
	 *            the colony's current date
	 * @return true if the member died of old age this step
	 */
	public boolean rollOldAgeDeath(Demography demography, LocalDate today) {
		if (alive && demography.diesOfOldAge(ageDays(today), person.race())) {
			alive = false;
			return true;
		}
		return false;
	}
}
