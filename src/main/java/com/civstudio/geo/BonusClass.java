package com.civstudio.geo;

import com.civstudio.good.ResourceType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A Civ4 bonus class — the placement category a {@link Bonus} belongs to (crop,
 * livestock, strategic, luxury…). Like {@link Continent}, this is a small, fixed
 * taxonomy, so it is modeled as an {@code enum} with its data baked in rather than
 * loaded from a resource. The eleven values and their {@link #uniqueRange()} are
 * the curated content of {@code data/civ4/CIV4BonusClassInfos.xml}; the per-bonus
 * {@code BonusClassType} key is the only bonus-class data persisted (on each entry
 * of {@code bonuses.json}), and {@link #fromKey(String)} maps it back to the enum.
 * <p>
 * {@code iUniqueRange} is the minimum spacing the Civ4 map generator keeps between
 * two placements of bonuses in the same class; it is stored but dormant in this
 * Phase-0 data layer. See {@code docs/plots.md}.
 * <p>
 * Each class also declares the {@linkplain ResourceType consumer-good category} a
 * bonus of that class supplies (its {@link #resourceType()}): the three <b>food</b>
 * classes — {@link #CROP}, {@link #LIVESTOCK}, {@link #SEAFOOD} — supply
 * {@link ResourceType#NECESSITY}, {@link #LUXURY} supplies
 * {@link ResourceType#ENJOYMENT}, and {@link #STRATEGIC} supplies
 * {@link ResourceType#STRATEGIC}. The remaining classes are not consumer goods
 * (production inputs, building/wonder outputs, misc) and map to {@code null}. This
 * is the seam by which a placed {@link Bonus} feeds the matching consumer sector.
 */
public enum BonusClass {

	MISC("BONUSCLASS_MISC", 0, null),
	CROP("BONUSCLASS_CROP", 2, ResourceType.NECESSITY),
	LIVESTOCK("BONUSCLASS_LIVESTOCK", 3, ResourceType.NECESSITY),
	SEAFOOD("BONUSCLASS_SEAFOOD", 0, ResourceType.NECESSITY),
	STRATEGIC("BONUSCLASS_STRATEGIC", 1, ResourceType.STRATEGIC),
	LUXURY("BONUSCLASS_LUXURY", 2, ResourceType.ENJOYMENT),
	PRODUCTION("BONUSCLASS_PRODUCTION", 2, null),
	/** Produced by buildings, never placed on the map. */
	MANUFACTURED("BONUSCLASS_MANUFACTURED", 0, null),
	/** Produced by cultural wonders, never placed on the map. */
	CULTURE("BONUSCLASS_CULTURE", 0, null),
	/** Produced by cultural wonders (technological in nature), never placed. */
	GENMODS("BONUSCLASS_GENMODS", 0, null),
	/** Produced by wonder buildings only, never placed. */
	WONDER("BONUSCLASS_WONDER", 0, null);

	private final String key;
	private final int uniqueRange;
	private final ResourceType resourceType;

	BonusClass(String key, int uniqueRange, ResourceType resourceType) {
		this.key = key;
		this.uniqueRange = uniqueRange;
		this.resourceType = resourceType;
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
	 * The consumer-good category a bonus of this class supplies, or {@code null}
	 * if the class is not a consumer good. The food classes ({@link #CROP},
	 * {@link #LIVESTOCK}, {@link #SEAFOOD}) supply {@link ResourceType#NECESSITY};
	 * {@link #LUXURY} supplies {@link ResourceType#ENJOYMENT}; {@link #STRATEGIC}
	 * supplies {@link ResourceType#STRATEGIC}.
	 */
	public ResourceType resourceType() {
		return resourceType;
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
