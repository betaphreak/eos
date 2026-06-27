package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A continent of the world map: the coarsest geographic tier, and — unlike the
 * open-ended {@link Area}/{@link Region}/{@link SuperRegion} tiers — a small,
 * fixed taxonomy, so it is modeled as an {@code enum} rather than data loaded from
 * a resource. The seven values are the geographic continents of the Anbennar
 * setting (including the underground {@code serpentspine}); the file's
 * non-geographic utility pseudo-continents ({@code debug_continent},
 * {@code island_check_provinces}, {@code new_world}) are not continents and have
 * no value here.
 * <p>
 * A continent groups provinces <em>directly</em> (there is no continent→region
 * link): every province names its continent via {@link Province#continent()}, and
 * the {@link WorldMap} builds the inverse membership ({@link
 * WorldMap#provincesInContinent(Continent)}) from those. There is no
 * {@code continents.json}; the per-province {@code continent} key — stamped onto
 * {@code provinces.json} by {@link com.civstudio.geo.export.ContinentExporter}
 * from {@code continent.txt} — is the only persisted continent data, and {@link
 * #fromKey(String)} maps it back to the enum.
 */
public enum Continent implements GeoTier {

	EUROPE("europe", "Europe"),
	SERPENTSPINE("serpentspine", "Serpentspine"),
	ASIA("asia", "Asia"),
	AFRICA("africa", "Africa"),
	NORTH_AMERICA("north_america", "North America"),
	SOUTH_AMERICA("south_america", "South America"),
	OCEANIA("oceania", "Oceania");

	private final String rawKey;
	private final String displayName;

	Continent(String rawKey, String displayName) {
		this.rawKey = rawKey;
		this.displayName = displayName;
	}

	/** The stable {@code raw_key} (e.g. {@code "asia"}); the persisted form. */
	@JsonValue
	@Override
	public String rawKey() {
		return rawKey;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	/**
	 * The continent for a {@code raw_key}, for Jackson and the exporter.
	 *
	 * @param key a continent {@code raw_key} (e.g. {@code "asia"}), or {@code null}
	 * @return the matching continent, or {@code null} if {@code key} is {@code null}
	 * @throws IllegalArgumentException if {@code key} is non-null but unknown
	 */
	@JsonCreator
	public static Continent fromKey(String key) {
		if (key == null)
			return null;
		for (Continent c : values())
			if (c.rawKey.equals(key))
				return c;
		throw new IllegalArgumentException("unknown continent key: " + key);
	}
}
