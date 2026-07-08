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

	/**
	 * The C2C map option {@code temperature} — the equator temperature of the
	 * {@link #pyTemperature(double) latitudinal tent} ({@code getTileTemperature},
	 * L3301). The default the script falls back to when the option is unset.
	 */
	public static final double CLIMATE_TEMPERATURE = 40.0;

	/**
	 * The C2C map option {@code variation} — how far the poles cool below the
	 * {@linkplain #CLIMATE_TEMPERATURE equator} (default; L387/L3296).
	 */
	public static final double CLIMATE_VARIATION = 0.4;

	/**
	 * The pole temperature of the tent: {@code (climateTemperature + 50)·variation − 50}
	 * (L3304). With the defaults this is {@code (90·0.4) − 50 = −14}.
	 */
	public static final double LOWEST_TEMPERATURE = (CLIMATE_TEMPERATURE + 50.0) * CLIMATE_VARIATION - 50.0;

	/**
	 * The C2C per-tile temperature on the <b>Python scale</b> for a latitude, porting
	 * {@code getTileTemperature(y, h)} (L3301–3310). The script keys its terrain and
	 * feature weights off this value — a latitudinal tent from {@link
	 * #CLIMATE_TEMPERATURE} at the equator down to {@link #LOWEST_TEMPERATURE} at the
	 * poles — so its thresholds ({@code > 30}, {@code > 40}, {@code 5..−10}, {@code
	 * < −20}, …) only transfer verbatim when the temperature is on the same scale.
	 * This reproduces the tent from the province latitude (rather than the eos
	 * {@link #temperature()} scale) so the ported feature weights apply unchanged.
	 * <p>
	 * The tent is linear in the distance from the equator: {@code y > half} and
	 * {@code y < half} are symmetric about the equator in the script, so here the
	 * absolute latitude (equator {@code 0} … pole {@code ±90}) drives it directly.
	 *
	 * @param latitude the plot/province latitude in decimal degrees (north positive)
	 * @return the C2C-scale temperature (≈ {@code −14 … 40} with the defaults)
	 */
	public static double pyTemperature(double latitude) {
		double fractionToPole = Math.min(1.0, Math.abs(latitude) / 90.0);
		return CLIMATE_TEMPERATURE + (LOWEST_TEMPERATURE - CLIMATE_TEMPERATURE) * fractionToPole;
	}
}
