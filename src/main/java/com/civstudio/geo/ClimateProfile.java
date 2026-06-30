package com.civstudio.geo;

/**
 * A province's climate reduced to the two scalars the Caveman2Cosmos planet
 * generator's per-tile stage reads: a <b>temperature</b> (its terrain/feature
 * thresholds — {@code > 30} is hot enough for jungle, {@code < 0} for the frozen
 * terrains) and a <b>humidity</b> in {@code [0, 1]} (how readily plants grow). The
 * C2C generator derives temperature from latitude bands and humidity from a global
 * option; here both come from the province's authored
 * {@link Climate}/{@link WinterSeverity}/{@link Monsoon} (and a small latitude
 * nudge), so a single fixed-climate province maps onto the same per-tile logic.
 * See {@code docs/province-plots.md}.
 * <p>
 * The constants are placeholders pending the same calibration the rest of the plot
 * model awaits ({@code docs/plots.md}).
 *
 * @param temperature the C2C-scale temperature (≈ {@code -15..50})
 * @param humidity    the wetness in {@code [0, 0.95]}
 */
public record ClimateProfile(double temperature, double humidity) {

	/** The climate profile of a province (temperature from band + winter + latitude). */
	public static ClimateProfile of(Province p) {
		double temp = switch (p.climate()) {
			case TROPICAL -> 45;
			case ARID -> 35;
			case TEMPERATE -> 28;
			case ARCTIC -> 0;
		};
		temp -= switch (p.winter()) {
			case NONE -> 0;
			case MILD -> 5;
			case NORMAL -> 10;
			case SEVERE -> 15;
		};
		// cooler toward the poles, beyond the subtropics
		temp -= Math.max(0, Math.abs(p.latitude()) - 30) * 0.4;

		double humidity = switch (p.climate()) {
			case TROPICAL -> 0.70;
			case TEMPERATE -> 0.50;
			case ARCTIC -> 0.30;
			case ARID -> 0.10;
		};
		humidity += switch (p.monsoon()) {
			case NONE -> 0;
			case MILD -> 0.10;
			case NORMAL -> 0.20;
			case SEVERE -> 0.30;
		};
		humidity = Math.min(0.95, humidity);
		return new ClimateProfile(temp, humidity);
	}

	/** Whether it is hot enough for jungle rather than forest (the C2C {@code > 40}/jungle band, softened). */
	public boolean isHot() {
		return temperature > 32;
	}
}
