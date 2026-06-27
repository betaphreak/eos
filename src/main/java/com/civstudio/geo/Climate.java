package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The climate band of a {@link Province}, overlaid from {@code data/climate.txt}
 * by {@link com.civstudio.geo.export.ClimateExporter}. The file lists the
 * {@code tropical}/{@code arid}/{@code arctic} provinces explicitly; anything
 * unlisted is {@link #TEMPERATE} (the default), so {@link Province#climate()} is
 * never {@code null}. A per-province environmental attribute, peer to {@link
 * ProvinceType} (not part of the {@link GeoTier} place hierarchy).
 */
public enum Climate {

	TROPICAL("tropical", "Tropical"),
	ARID("arid", "Arid"),
	ARCTIC("arctic", "Arctic"),
	TEMPERATE("temperate", "Temperate");

	private final String rawKey;
	private final String displayName;

	Climate(String rawKey, String displayName) {
		this.rawKey = rawKey;
		this.displayName = displayName;
	}

	/** The stable {@code raw_key} (e.g. {@code "tropical"}); the persisted form. */
	@JsonValue
	public String rawKey() {
		return rawKey;
	}

	/** The human-readable display name (e.g. {@code "Tropical"}). */
	public String displayName() {
		return displayName;
	}

	/**
	 * The climate for a {@code raw_key}, for Jackson and the exporter.
	 *
	 * @param key a climate {@code raw_key}, or {@code null}
	 * @return the matching climate, or {@code null} if {@code key} is {@code null}
	 *         (the {@link Province} constructor coerces that to {@link #TEMPERATE})
	 * @throws IllegalArgumentException if {@code key} is non-null but unknown
	 */
	@JsonCreator
	public static Climate fromKey(String key) {
		if (key == null)
			return null;
		for (Climate c : values())
			if (c.rawKey.equals(key))
				return c;
		throw new IllegalArgumentException("unknown climate key: " + key);
	}
}
