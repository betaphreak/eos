package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A Civ4 bonus class — the placement category a {@link Bonus} belongs to (crop,
 * livestock, strategic, luxury…). Like {@link Continent}, this is a small, fixed
 * taxonomy, so it is modeled as an {@code enum} with its data baked in rather than
 * loaded from a resource. The eleven values and their {@link #uniqueRange()} are
 * the curated content of {@code data/CIV4BonusClassInfos.xml}; the per-bonus
 * {@code BonusClassType} key is the only bonus-class data persisted (on each entry
 * of {@code bonuses.json}), and {@link #fromKey(String)} maps it back to the enum.
 * <p>
 * {@code iUniqueRange} is the minimum spacing the Civ4 map generator keeps between
 * two placements of bonuses in the same class; it is stored but dormant in this
 * Phase-0 data layer. See {@code docs/plots.md}.
 */
public enum BonusClass {

	MISC("BONUSCLASS_MISC", 0),
	CROP("BONUSCLASS_CROP", 2),
	LIVESTOCK("BONUSCLASS_LIVESTOCK", 3),
	SEAFOOD("BONUSCLASS_SEAFOOD", 0),
	STRATEGIC("BONUSCLASS_STRATEGIC", 1),
	LUXURY("BONUSCLASS_LUXURY", 2),
	PRODUCTION("BONUSCLASS_PRODUCTION", 2),
	/** Produced by buildings, never placed on the map. */
	MANUFACTURED("BONUSCLASS_MANUFACTURED", 0),
	/** Produced by cultural wonders, never placed on the map. */
	CULTURE("BONUSCLASS_CULTURE", 0),
	/** Produced by cultural wonders (technological in nature), never placed. */
	GENMODS("BONUSCLASS_GENMODS", 0),
	/** Produced by wonder buildings only, never placed. */
	WONDER("BONUSCLASS_WONDER", 0);

	private final String key;
	private final int uniqueRange;

	BonusClass(String key, int uniqueRange) {
		this.key = key;
		this.uniqueRange = uniqueRange;
	}

	/** The stable Civ4 type key (e.g. {@code "BONUSCLASS_CROP"}); the persisted form. */
	@JsonValue
	public String key() {
		return key;
	}

	/** Minimum map-generator spacing between same-class placements (dormant). */
	public int uniqueRange() {
		return uniqueRange;
	}

	/**
	 * The class for a {@code BonusClassType} key, for Jackson and the exporter.
	 *
	 * @param key a bonus-class key (e.g. {@code "BONUSCLASS_CROP"}), or {@code null}
	 * @return the matching class, or {@code null} if {@code key} is {@code null}
	 * @throws IllegalArgumentException if {@code key} is non-null but unknown
	 */
	@JsonCreator
	public static BonusClass fromKey(String key) {
		if (key == null)
			return null;
		for (BonusClass c : values())
			if (c.key.equals(key))
				return c;
		throw new IllegalArgumentException("unknown bonus class key: " + key);
	}
}
