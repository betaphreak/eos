package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The monsoon intensity of a {@link Province}, overlaid from {@code
 * data/climate.txt} (the {@code mild_monsoon}/{@code normal_monsoon}/{@code
 * severe_monsoon} blocks) by {@link com.civstudio.geo.export.ClimateExporter}.
 * Provinces in none of those blocks have {@link #NONE} (the default), so {@link
 * Province#monsoon()} is never {@code null}. A per-province environmental
 * attribute, peer to {@link ProvinceType}.
 */
public enum Monsoon {

	NONE("none", "None"),
	MILD("mild", "Mild"),
	NORMAL("normal", "Normal"),
	SEVERE("severe", "Severe");

	private final String rawKey;
	private final String displayName;

	Monsoon(String rawKey, String displayName) {
		this.rawKey = rawKey;
		this.displayName = displayName;
	}

	/** The stable {@code raw_key} (e.g. {@code "severe"}); the persisted form. */
	@JsonValue
	public String rawKey() {
		return rawKey;
	}

	/** The human-readable display name (e.g. {@code "Severe"}). */
	public String displayName() {
		return displayName;
	}

	/**
	 * The monsoon intensity for a {@code raw_key}, for Jackson and the exporter.
	 *
	 * @param key a {@code raw_key}, or {@code null}
	 * @return the matching intensity, or {@code null} if {@code key} is {@code null}
	 *         (the {@link Province} constructor coerces that to {@link #NONE})
	 * @throws IllegalArgumentException if {@code key} is non-null but unknown
	 */
	@JsonCreator
	public static Monsoon fromKey(String key) {
		if (key == null)
			return null;
		for (Monsoon m : values())
			if (m.rawKey.equals(key))
				return m;
		throw new IllegalArgumentException("unknown monsoon key: " + key);
	}
}
