package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The winter severity of a {@link Province}, overlaid from {@code
 * data/anbennar/climate.txt} (the {@code mild_winter}/{@code normal_winter}/{@code
 * severe_winter} blocks) by {@link com.civstudio.geo.export.ClimateExporter}.
 * Provinces in none of those blocks have {@link #NONE} (the default), so {@link
 * Province#winter()} is never {@code null}. A per-province environmental attribute,
 * peer to {@link ProvinceType}.
 */
public enum WinterSeverity {

	NONE("none", "None", 1.00),
	MILD("mild", "Mild", 0.95),
	NORMAL("normal", "Normal", 0.90),
	SEVERE("severe", "Severe", 0.80);

	private final String rawKey;
	private final String displayName;
	private final double agricultureFactor;

	WinterSeverity(String rawKey, String displayName, double agricultureFactor) {
		this.rawKey = rawKey;
		this.displayName = displayName;
		this.agricultureFactor = agricultureFactor;
	}

	/**
	 * The multiplier this winter severity applies to agricultural (necessity/food)
	 * total-factor productivity — a flat, year-round growing-season penalty (frost,
	 * shorter season) standing apart from the daylight-length scaling the labor
	 * market already applies. {@code 1.0} for no winter, falling as winters
	 * harden. See {@link
	 * com.civstudio.settlement.Settlement#getAgricultureClimateMultiplier()}.
	 *
	 * @return the food-productivity multiplier for this winter severity
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
	 * The winter severity for a {@code raw_key}, for Jackson and the exporter.
	 *
	 * @param key a {@code raw_key}, or {@code null}
	 * @return the matching severity, or {@code null} if {@code key} is {@code null}
	 *         (the {@link Province} constructor coerces that to {@link #NONE})
	 * @throws IllegalArgumentException if {@code key} is non-null but unknown
	 */
	@JsonCreator
	public static WinterSeverity fromKey(String key) {
		if (key == null)
			return null;
		for (WinterSeverity w : values())
			if (w.rawKey.equals(key))
				return w;
		throw new IllegalArgumentException("unknown winter key: " + key);
	}
}
