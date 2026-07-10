package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The monsoon intensity of a {@link Province}, overlaid from {@code
 * data/anbennar/climate.txt} (the {@code mild_monsoon}/{@code normal_monsoon}/{@code
 * severe_monsoon} blocks) by {@link com.civstudio.geo.export.ClimateExporter}.
 * Provinces in none of those blocks have {@link #NONE} (the default), so {@link
 * Province#monsoon()} is never {@code null}. A per-province environmental
 * attribute, peer to {@link ProvinceType}.
 */
public enum Monsoon {

	NONE("none", "None", 1.00),
	MILD("mild", "Mild", 1.05),
	NORMAL("normal", "Normal", 1.10),
	SEVERE("severe", "Severe", 1.15);

	private final String rawKey;
	private final String displayName;
	private final double agricultureFactor;

	Monsoon(String rawKey, String displayName, double agricultureFactor) {
		this.rawKey = rawKey;
		this.displayName = displayName;
		this.agricultureFactor = agricultureFactor;
	}

	/**
	 * The multiplier this monsoon intensity applies to agricultural (necessity/
	 * food) total-factor productivity — the wet season is a boon to farming
	 * (paddy/irrigated agriculture), so the factor rises with intensity above the
	 * {@code 1.0} no-monsoon baseline. See {@link
	 * com.civstudio.settlement.Settlement#getAgricultureClimateMultiplier()}.
	 *
	 * @return the food-productivity multiplier for this monsoon intensity
	 */
	public double agricultureFactor() {
		return agricultureFactor;
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
