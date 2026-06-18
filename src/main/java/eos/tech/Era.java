package eos.tech;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A technological era, in chronological order — the coarse age a {@link Tech} belongs
 * to. The enum declaration order <b>is</b> the chronological order, so {@link
 * #ordinal()} compares eras ("at or before") directly; this is what
 * {@link TechTree#preKnownThrough(Era)} reads to seed a colony's pre-known set.
 * <p>
 * These are exactly the eras <b>eos models</b>: the source {@code techs.json} (a
 * Caveman2Cosmos tech graph) runs on past these into an Industrial era and beyond,
 * but the simulation is scoped to end at the {@link #RENAISSANCE}. A tech whose era
 * is not one of these (i.e. {@code C2C_ERA_INDUSTRIAL}) is therefore <b>out of
 * scope</b> and dropped when the tree is loaded (see {@link TechTree#load()}). So the
 * set of values here doubles as the definition of "in scope".
 */
public enum Era {

	PREHISTORIC("C2C_ERA_PREHISTORIC"),
	ANCIENT("C2C_ERA_ANCIENT"),
	CLASSICAL("C2C_ERA_CLASSICAL"),
	MEDIEVAL("C2C_ERA_MEDIEVAL"),
	RENAISSANCE("C2C_ERA_RENAISSANCE");

	// the raw era key used in techs.json
	private final String key;

	// reverse lookup from the raw JSON key to the enum constant
	private static final Map<String, Era> BY_KEY = new HashMap<>();
	static {
		for (Era e : values())
			BY_KEY.put(e.key, e);
	}

	Era(String key) {
		this.key = key;
	}

	/** @return the raw era key as it appears in {@code techs.json} */
	public String key() {
		return key;
	}

	/**
	 * Resolve a raw {@code techs.json} era key to an {@link Era}, or
	 * {@link Optional#empty()} if the key names an era this model does not cover
	 * (e.g. {@code C2C_ERA_INDUSTRIAL}) — such techs are out of scope and dropped at
	 * load.
	 *
	 * @param key
	 *            the raw era key (e.g. {@code "C2C_ERA_MEDIEVAL"})
	 * @return the matching era, or empty if out of scope / unknown
	 */
	public static Optional<Era> fromKey(String key) {
		return Optional.ofNullable(BY_KEY.get(key));
	}

	/**
	 * Whether this era is no later than {@code other} (chronological order, by
	 * {@link #ordinal()}). A tech is pre-known at a given start era exactly when its
	 * era {@code isAtOrBefore} that start era.
	 *
	 * @param other
	 *            the era to compare against
	 * @return true if this era is at or before {@code other}
	 */
	public boolean isAtOrBefore(Era other) {
		return ordinal() <= other.ordinal();
	}
}
