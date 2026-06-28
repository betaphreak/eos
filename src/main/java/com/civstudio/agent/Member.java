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
	// this person's parents, set only at birth (see Demography.newChild). Null for
	// every initially-generated individual — founding heads, seeded/promoted peasants,
	// wed-in spouses, immigrants — whose forebears are outside the colony's knowledge,
	// so parentage is populated only by in-colony births. Read-only after construction
	// (a person's parents never change); the seam for lineage / incest avoidance /
	// parent->child inheritance (nothing reads them yet). See docs/births.md.
	private final Member mother;
	private final Member father;
	private boolean alive = true;

	/**
	 * Create a living, <b>parentless</b> member from a named, skilled person and its
	 * birth date — an initially-generated individual (a founding head, a pooled
	 * peasant, a wed-in spouse, an immigrant). Its {@link #getMother() mother} and
	 * {@link #getFather() father} are {@code null}.
	 *
	 * @param person
	 *            the member's name-and-skills identity
	 * @param birthDate
	 *            the member's biological birth date
	 */
	public Member(Person person, LocalDate birthDate) {
		this(person, birthDate, null, null);
	}

	/**
	 * Create a living member with known {@code mother} and {@code father} — a
	 * newborn, the only individual whose parentage the colony knows (see
	 * {@link com.civstudio.mortality.Demography#newChild}). Either parent may be
	 * {@code null}.
	 *
	 * @param person
	 *            the member's name-and-skills identity
	 * @param birthDate
	 *            the member's biological birth date
	 * @param mother
	 *            the member's mother, or {@code null} if unknown
	 * @param father
	 *            the member's father, or {@code null} if unknown
	 */
	public Member(Person person, LocalDate birthDate, Member mother, Member father) {
		this.person = person;
		this.birthDate = birthDate;
		this.mother = mother;
		this.father = father;
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

	/** @return this member's mother, or {@code null} if unknown (not colony-born) */
	public Member getMother() {
		return mother;
	}

	/** @return this member's father, or {@code null} if unknown (not colony-born) */
	public Member getFather() {
		return father;
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
	 * Whether this member has reached working age — its race's
	 * {@linkplain Race#minInitAgeYears() working-age floor} (the same floor the model
	 * uses when drawing founding/promoted heads). Below it the member is a
	 * <b>child</b>: it eats the child ration, supplies no labour and trains no skills;
	 * at or above it the member is an <b>adult</b> worker (see {@code docs/births.md}).
	 *
	 * @param today
	 *            the colony's current date
	 * @return true if the member is of working age (an adult)
	 */
	public boolean isAdult(LocalDate today) {
		return getAgeYears(today) >= person.race().minInitAgeYears();
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
