package com.civstudio.race;

import com.civstudio.mortality.LifeTable;
import com.civstudio.name.Person;

/**
 * A person's ancestry — a first-class, per-{@link Person person}
 * attribute, so one colony can hold residents of several races at once (a mixed
 * settlement). A race carries the metadata the per-person and per-colony
 * services need: a resource-file {@link #id() slug} and a mortality {@link
 * LifeTable}.
 * <p>
 * The guiding constraint is that <b>{@link #HUMAN} reproduces the mono-cultural
 * behaviour byte-for-byte</b>: race is additive, and a pure-human colony draws no
 * new randomness and loads no new resources (see {@code docs/race.md}). The
 * resource-file convention {@code /{male,female,dynasty}-<id>.json},
 * {@code /feasts-<id>.json}, {@code /tech-effects-<id>.json} keys off {@link
 * #id()}, and every per-race resource <b>falls back to the human/base file when
 * its race-specific file is absent</b>, so a new race can ship with only the
 * files that actually differ.
 */
public enum Race {

	/**
	 * The default ancestry: the human name tables, calendar and tech graph, a
	 * working-age floor of 15 and young-adult immigrants aged 16–25.
	 */
	HUMAN("human", LifeTable.WEST_LEVEL_3, 15, 16, 25),

	/**
	 * The tiger-folk of <i>Anbennar</i> — the first non-human race. Reuses the
	 * human life table for now (its own schedule comes later); ships a distinctive
	 * dynasty (clan) surname table (see {@code docs/race.md}). The Harimari mature
	 * faster: a working-age floor of 9 and young-adult immigrants aged 9–16.
	 */
	HARIMARI("harimari", LifeTable.WEST_LEVEL_3, 9, 9, 16);

	// resource-file slug, e.g. "human" -> /names/human/male.json
	private final String id;

	// the race's mortality schedule
	private final LifeTable lifeTable;

	// the youngest a founding household head may be (the working-age floor the
	// founding-age draw is truncated below); see Demography.sampleInitialAgeDays
	private final int minInitAgeYears;

	// inclusive age range (years) of a "young, fresh" adult immigrant recruited into
	// the peasant pool; see Demography.sampleYoungAdultAgeDays
	private final int youngAdultMinYears;
	private final int youngAdultMaxYears;

	Race(String id, LifeTable lifeTable, int minInitAgeYears,
			int youngAdultMinYears, int youngAdultMaxYears) {
		this.id = id;
		this.lifeTable = lifeTable;
		this.minInitAgeYears = minInitAgeYears;
		this.youngAdultMinYears = youngAdultMinYears;
		this.youngAdultMaxYears = youngAdultMaxYears;
	}

	/**
	 * The resource-file slug for this race (e.g. {@code "human"}), used to build
	 * the per-race resource paths {@code /names/<id>/male.json},
	 * {@code /names/<id>/dynasty.json}, {@code /feasts-<id>.json}, etc.
	 *
	 * @return the resource slug
	 */
	public String id() {
		return id;
	}

	/**
	 * This race's mortality schedule, on which each of its people ages and dies.
	 *
	 * @return the race's life table
	 */
	public LifeTable lifeTable() {
		return lifeTable;
	}

	/**
	 * The youngest (in whole years) a founding household head of this race may be —
	 * the working-age floor the founding-age draw is truncated below.
	 *
	 * @return the working-age floor in years
	 */
	public int minInitAgeYears() {
		return minInitAgeYears;
	}

	/**
	 * The youngest (in whole years) a freshly-recruited young-adult immigrant of this
	 * race may be (inclusive).
	 *
	 * @return the young-adult lower age bound in years
	 */
	public int youngAdultMinYears() {
		return youngAdultMinYears;
	}

	/**
	 * The oldest (in whole years) a freshly-recruited young-adult immigrant of this
	 * race may be (inclusive).
	 *
	 * @return the young-adult upper age bound in years
	 */
	public int youngAdultMaxYears() {
		return youngAdultMaxYears;
	}
}
