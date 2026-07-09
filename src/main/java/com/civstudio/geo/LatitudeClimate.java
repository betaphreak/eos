package com.civstudio.geo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Latitude → temperature → cold-terrain model, ported from the Caveman2Cosmos planet
 * generator ({@code data/civ4/C2C_Planet_Generator_0_68.py}, {@code getTileTemperature} +
 * the {@code generateTerrain} temperature bands). The imported Anbennar {@code terrain.bmp}
 * ignores latitude — it paints the far north green grassland — so this cools the ground
 * toward taiga/tundra/permafrost as latitude rises, matching the generator's bands.
 * <p>
 * Temperature model (the generator's defaults): {@code climateTemperature = 40} at the
 * equator, {@code climateVariation = 0.4} so the poles reach {@code (40+50)*0.4 - 50 = -14};
 * linear in the distance from the equator, i.e. {@code temp = 40 - 0.6·|lat|} over 0..90°.
 * The band → terrain weights are the generator's (its {@code terrainTundra} = our
 * {@code TAIGA}, its {@code terrainPermafrost} = our {@code TUNDRA}, its {@code terrainSnow}
 * = our coldest land terrain {@code PERMAFROST} — we have no {@code TERRAIN_ICE} land type).
 * <p>
 * We <b>import</b> terrain rather than generate it, so this is applied as an override on the
 * imported <em>warm</em> ground (see {@link #isWarm}), ramped in by {@link #coldFraction} so
 * the temperate zone is untouched and the far north transitions smoothly to cold.
 */
public final class LatitudeClimate {

	private LatitudeClimate() {
	}

	// C2C planet-generator climate constants (its module globals)
	private static final double CLIMATE_TEMPERATURE = 40.0; // equator
	private static final double CLIMATE_VARIATION = 0.4;
	private static final double CLIMATE_HUMIDITY = 0.5;
	private static final double LOWEST_TEMPERATURE =
			(CLIMATE_TEMPERATURE + 50) * CLIMATE_VARIATION - 50; // -14 at the poles

	/** Temperature at |lat|=0 vs 90°, linear (the generator's {@code getTileTemperature}). */
	public static double temperature(double latitude) {
		double f = Math.min(1.0, Math.abs(latitude) / 90.0);
		return CLIMATE_TEMPERATURE + (LOWEST_TEMPERATURE - CLIMATE_TEMPERATURE) * f;
	}

	/**
	 * Extra cooling (°C) from the province's Anbennar winter severity (from {@code
	 * climate.txt}) — a harsher winter reads colder than latitude alone, so a
	 * severe-winter province at a middling latitude still turns taiga/tundra.
	 */
	public static double winterOffset(WinterSeverity winter) {
		return switch (winter) {
			case NONE -> 0.0;
			case MILD -> 5.0;
			case NORMAL -> 10.0;
			case SEVERE -> 16.0;
		};
	}

	/**
	 * The <b>effective</b> temperature the terrain bands read: latitude temperature minus the
	 * winter cooling. This is what {@link #coldFraction} and {@link #coldPool} should be given.
	 */
	public static double effectiveTemperature(double latitude, WinterSeverity winter) {
		return temperature(latitude) - winterOffset(winter);
	}

	// the temperature below which the imported warm terrain starts being cooled (taiga band top),
	// and the temperature at/below which it is fully replaced (deep cold). coldFraction ramps between.
	private static final double COLD_START = 12.0;   // ~|lat| 47° — first hint of boreal
	private static final double COLD_FULL = -6.0;    // ~|lat| 77° — fully cold

	/** How strongly latitude overrides the imported warm ground (0 in the temperate zone → 1 in the deep cold). */
	public static double coldFraction(double temperature) {
		if (temperature >= COLD_START)
			return 0.0;
		if (temperature <= COLD_FULL)
			return 1.0;
		return (COLD_START - temperature) / (COLD_START - COLD_FULL);
	}

	/**
	 * The warm, latitude-inappropriate land terrains a cold latitude replaces — everything
	 * temperate/warm and flat-ish. Mountains ({@code ROCKY}/{@code JAGGED}) and the
	 * already-cold terrains are kept, so relief and existing tundra survive.
	 */
	public static boolean isWarm(Terrain t) {
		return switch (t.type()) {
			case "TERRAIN_GRASSLAND", "TERRAIN_LUSH", "TERRAIN_PLAINS", "TERRAIN_SCRUB",
					"TERRAIN_MARSH", "TERRAIN_MUDDY", "TERRAIN_DESERT", "TERRAIN_DUNES",
					"TERRAIN_SALT_FLATS", "TERRAIN_BADLAND" -> true;
			default -> false; // ROCKY/JAGGED (mountains), TAIGA/TUNDRA/PERMAFROST (already cold), etc.
		};
	}

	/**
	 * The weighted cold-terrain pool for a temperature, from the generator's {@code
	 * generateTerrain} bands (weights = its {@code randomTerrain} slot indices at humidity 0.5;
	 * the warm bands above the cold override are dropped). Never empty for {@code temperature <
	 * COLD_START}; falls back to taiga so a plot always has a cold ground to draw.
	 */
	public static Map<String, Double> coldPool(double temperature) {
		Map<String, Double> w = new LinkedHashMap<>();
		double h = CLIMATE_HUMIDITY;
		if (temperature > 4 && temperature < 30)
			bump(w, "TERRAIN_GRASSLAND", 7 * (h + 0.5));       // 7
		if (temperature > -2 && temperature < 25)
			bump(w, "TERRAIN_PLAINS", 7);
		if (temperature > -5 && temperature < 18)
			bump(w, "TERRAIN_MARSH", 10);
		if (temperature > -10 && temperature < 10)
			bump(w, "TERRAIN_TAIGA", 7);                       // generator's "tundra"
		if (temperature < 0)
			bump(w, "TERRAIN_PERMAFROST", temperature < -30 ? 15 * (h / 2 + 0.75) : 7 * (h / 2 + 0.75)); // its "snow"
		if (temperature < 0 && temperature >= -20)
			bump(w, "TERRAIN_TUNDRA", temperature < -10 ? 15 * (h / 2 + 0.75) : 7 * (h / 2 + 0.75));      // its "permafrost"
		if (w.isEmpty())
			w.put("TERRAIN_TAIGA", 1.0);
		return w;
	}

	private static void bump(Map<String, Double> w, String type, double by) {
		w.merge(type, by, Double::sum);
	}
}
