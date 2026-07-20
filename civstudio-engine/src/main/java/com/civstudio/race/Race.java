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
	HUMAN("human", LifeTable.LENIENT, 15, 16, 25),

	/**
	 * The tiger-folk of <i>Anbennar</i> — the first non-human race. Reuses the
	 * human life table for now (its own schedule comes later); ships a distinctive
	 * dynasty (clan) surname table (see {@code docs/race.md}). The Harimari mature
	 * faster: a working-age floor of 9 and young-adult immigrants aged 9–16.
	 */
	HARIMARI("harimari", LifeTable.WEST_LEVEL_3, 9, 9, 16),

	/**
	 * The elves of <i>Anbennar</i> — a long-lived Anbennar race. Reuses the human
	 * life table for now (its own schedule comes later) and ships the Anbennar elven
	 * name tables ({@code /names/elven/}); it has no elven feast or tech-effect file,
	 * so it falls back to the human liturgical calendar and tech overlay. Long-lived:
	 * a working-age floor of 20 and young-adult immigrants aged 20–40.
	 */
	ELVEN("elven", LifeTable.WEST_LEVEL_3, 20, 20, 40),

	// ---- The remaining Anbennar races imported from anb_cultures.txt. Each ships
	// only its own name tables under /names/<id>/; with no per-race feast, tech-effect
	// or life-table data yet, they reuse the human placeholder life table and fall back
	// to the human liturgical calendar and tech overlay. Maturation is a placeholder
	// too (tune per race when real schedules arrive). ----

	// Long-lived elf and dwarf races: a later working-age floor (20) and older young
	// adults (20–40), like ELVEN above.
	DWARVEN("dwarven", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	DEGENERATED_ELF("degenerated_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	AMADIAN_RUINBORN_ELF("amadian_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	DEVANDI_RUINBORN_ELF("devandi_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	EFFELAI_RUINBORN_ELF("effelai_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	ELTIBHARI_RUINBORN_ELF("eltibhari_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	EORDAN_RUINBORN_ELF("eordan_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	HARAFIC_RUINBORN_ELF("harafic_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	KHEIONAI_RUINBORN_ELF("kheionai_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	NORTH_RUINBORN_ELF("north_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	SOUTH_RUINBORN_ELF("south_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	TAYCHENDI_RUINBORN_ELF("taychendi_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),
	YNNIC_RUINBORN_ELF("ynnic_ruinborn_elf", LifeTable.WEST_LEVEL_3, 20, 20, 40),

	// Human-like cultures and the remaining fantasy folk: human-baseline maturation
	// (the single-argument constructor below).
	AKASI("akasi"),
	ALENIC("alenic"),
	ANBENNARIAN("anbennarian"),
	BOM("bom"),
	BULWARI("bulwari"),
	BUSINORI("businori"),
	CENTAUR("centaur"),
	DOSTANORIAN_G("dostanorian_g"),
	ESCANNI("escanni"),
	GERUDIAN("gerudian"),
	GIANTKIND("giantkind"),
	GNOLLISH("gnollish"),
	GNOMISH("gnomish"),
	GOBLIN("goblin"),
	GOWON("gowon"),
	HALFLING("halfling"),
	HARPY("harpy"),
	HOBGOBLIN("hobgoblin"),
	INYASWAROSA("inyaswarosa"),
	IRSUKUBA("irsukuba"),
	KAI("kai"),
	KELINO("kelino"),
	KHANTAAR("khantaar"),
	KHETERATAN("kheteratan"),
	KHUDI("khudi"),
	KOBOLD("kobold"),
	LENCORI("lencori"),
	LIZARDFOLK("lizardfolk"),
	MENGI("mengi"),
	MIDDLE_RAHENI("middle_raheni"),
	OGRE("ogre"),
	ORCISH("orcish"),
	REACHMAN("reachman"),
	TRIUNIC("triunic"),
	TROLLSBAYER("trollsbayer"),
	TYVORKAN("tyvorkan"),
	UPPER_RAHENI("upper_raheni"),
	VUREBINDU("vurebindu"),
	WEST_SARHALY("west_sarhaly"),
	WUHYUN("wuhyun"),
	YAN("yan"),
	YANGLAM("yanglam");

	// resource-file slug, e.g. "human" -> /human-names/male.json
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
	 * Convenience constructor for a race that ships only its own name tables and has
	 * no tuned demography yet: it reuses the human placeholder {@linkplain
	 * LifeTable#WEST_LEVEL_3 life table} and human-baseline maturation (working-age
	 * floor 15, young-adult immigrants 16–25). It still loads its own
	 * {@code /names/<id>/} tables and falls back to the human calendar / tech overlay.
	 *
	 * @param id
	 *            the resource-file slug (its {@code /names/<id>/} folder)
	 */
	Race(String id) {
		this(id, LifeTable.WEST_LEVEL_3, 15, 16, 25);
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

	// id -> race, built once. The ids ARE the Anbennar culture-group keys (this enum was imported
	// from anb_cultures.txt), which is what makes the lookup below a rename rather than a mapping
	// table someone has to maintain.
	private static final java.util.Map<String, Race> BY_ID;
	static {
		java.util.Map<String, Race> m = new java.util.HashMap<>(values().length * 2);
		for (Race r : values())
			m.put(r.id, r);
		BY_ID = java.util.Collections.unmodifiableMap(m);
	}

	/**
	 * The race of an Anbennar <b>culture group</b> — how a settlement learns who lives in it.
	 * <p>
	 * A province carries a culture ({@code Province.culture}), a culture belongs to a group
	 * ({@code Culture.group}), and that group key <em>is</em> this enum's {@link #id()}: both were
	 * imported from {@code common/cultures/anb_cultures.txt}. So the world map already knows the race
	 * of every province — it does not have to be authored a second time.
	 * <p>
	 * Measured against the shipped world: <b>57 of 70</b> culture groups name a race exactly. The
	 * remainder ({@code baashidi}, {@code ynnsman}, {@code anakue}, … and the two odd ones out,
	 * {@code construct} and {@code undead}) have no race authored yet and fall back to {@link #HUMAN}
	 * — the same fallback every unauthored per-race resource takes (life table, calendar, tech
	 * overlay; see {@code docs/race.md}). {@code HUMAN} is itself the one race with no culture group,
	 * because it is the engine's default rather than an Anbennar people.
	 * <p>
	 * Takes the group key rather than a {@code Culture} on purpose: this package stays independent of
	 * {@code com.civstudio.geo}.
	 *
	 * @param cultureGroup the culture group's {@code raw_key}, or {@code null}
	 * @return the matching race, or {@link #HUMAN} when the group names none
	 */
	public static Race ofCultureGroup(String cultureGroup) {
		if (cultureGroup == null || cultureGroup.isBlank())
			return HUMAN;
		return BY_ID.getOrDefault(cultureGroup.trim().toLowerCase(java.util.Locale.ROOT), HUMAN);
	}

	/**
	 * Whether {@code cultureGroup} actually names a race, as opposed to falling back to {@link #HUMAN}.
	 * Lets a caller tell "this is a human people" from "we have no race for these people yet", which
	 * {@link #ofCultureGroup(String)} deliberately collapses.
	 *
	 * @param cultureGroup the culture group's {@code raw_key}, or {@code null}
	 * @return {@code true} if a race is authored for that group
	 */
	public static boolean isAuthoredCultureGroup(String cultureGroup) {
		return cultureGroup != null && !cultureGroup.isBlank()
				&& BY_ID.containsKey(cultureGroup.trim().toLowerCase(java.util.Locale.ROOT));
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
