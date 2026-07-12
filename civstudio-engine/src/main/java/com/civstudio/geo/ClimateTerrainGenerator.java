package com.civstudio.geo;

import java.util.LinkedHashMap;
import java.util.Map;

import com.civstudio.util.Rng;

/**
 * Procedural plot terrain, a faithful port of the Caveman2Cosmos planet generator's terrain stage
 * ({@code PrivateMaps/C2C_Planet_Generator_0_68.py}, {@code generateTerrainTypes} + the "Diversify
 * terrain" pass). This is the <b>primary</b> terrain source (as of the plot-generator rework,
 * {@code docs/plot-generator.md}) — it replaces reading {@code terrain.bmp} pixels, so all 33 terrains
 * appear climate-appropriately from each province's authored climate rather than the sparse EU4 palette.
 * <p>
 * Two stages, both weighted-probability draws (reusing {@link TerrainGenerator}'s pick):
 * <ol>
 * <li><b>Base by temperature × humidity</b> — {@link #pool} builds the C2C {@code randomTerrain} array
 * from overlapping temperature bands (humidity modulates the weights), exactly as the script does per row.
 * <li><b>Diversify</b> — {@link #diversify} replaces each base land terrain with a weighted detail
 * variant (desert→salt-flats/dunes/scrub, plains→barren/rocky, grass→lush/muddy).
 * </ol>
 * Naming quirk carried from the script (its variables don't match their keys): its {@code terrainTundra}
 * is our {@code TAIGA}, {@code terrainPermafrost} is our {@code TUNDRA}, and {@code terrainSnow} is our
 * coldest land terrain {@code PERMAFROST} (we have no {@code TERRAIN_ICE} land type). Cf. {@link LatitudeClimate}.
 * <p>
 * Temperature comes from the province's Anbennar {@link Climate}/{@link WinterSeverity} + latitude
 * ({@link #temperature}); humidity from {@link Climate}/{@link Monsoon} ({@link ClimateProfile#humidity}).
 * The two are the only per-tile inputs the C2C bands read. A single instance holds the Stage-1 pool for a
 * fixed (temperature, humidity) plus the three diversify pools, so a province builds ~4 generators, not one
 * per plot; {@link #next} then does the two draws.
 */
public final class ClimateTerrainGenerator {

	private final TerrainGenerator base;
	private final Map<String, TerrainGenerator> diversifiers;
	private final TerrainRegistry registry;

	/** A generator for a fixed (temperature, humidity) — e.g. one per province, or per temperature bucket. */
	public ClimateTerrainGenerator(TerrainRegistry registry, double temperature, double humidity) {
		this(registry, new TerrainGenerator(registry, pool(temperature, humidity)));
	}

	/**
	 * The IMPASSABLE-wasteland variant: a climate-appropriate <b>barren</b> pool (hot → desert/badland,
	 * cold → rocky/permafrost, temperate → rocky/scrub) instead of the full climate mix, so a wasteland
	 * reads as impassable waste rather than (say) a tropical jungle. Relief stays mountainous (its
	 * {@link ReliefGenerator.Params#MOUNTAINOUS} preset). See {@code docs/plot-generator.md}.
	 */
	public static ClimateTerrainGenerator barren(TerrainRegistry registry, double temperature) {
		return new ClimateTerrainGenerator(registry, new TerrainGenerator(registry, barrenPool(temperature)));
	}

	private ClimateTerrainGenerator(TerrainRegistry registry, TerrainGenerator base) {
		this.registry = registry;
		this.base = base;
		this.diversifiers = new LinkedHashMap<>();
		for (String b : new String[] { "TERRAIN_DESERT", "TERRAIN_PLAINS", "TERRAIN_GRASSLAND" })
			this.diversifiers.put(b, new TerrainGenerator(registry, diversify(b)));
	}

	/**
	 * Draw a plot's terrain: Stage-1 base then Stage-2 diversify. Consumes exactly <b>two</b> rng draws
	 * (base, then variant — a fixed count whether or not the base has variants), so the deterministic
	 * per-cell draw order the field relies on stays byte-stable ({@code docs/plot-generator.md} §Determinism).
	 */
	public Terrain next(Rng rng) {
		Terrain b = base.next(rng);
		TerrainGenerator div = diversifiers.get(b.type());
		Terrain variant = div != null ? div.next(rng) : registry.terrain(b.type());
		return variant != null ? variant : b;   // a spare draw for the no-variant terrains keeps the count fixed
	}

	// --- the C2C algorithm (static, unit-testable) -------------------------------------------------

	/**
	 * Stage 1 — the C2C {@code randomTerrain} weighted pool for a temperature (°C, C2C scale ≈ −15..40)
	 * and humidity ([0,1]). Overlapping bands, humidity-modulated, verbatim from the script's
	 * {@code generateTerrainTypes} loop. Falls back to grassland if a temperature lands in no band.
	 */
	public static Map<String, Double> pool(double temperature, double humidity) {
		Map<String, Double> w = new LinkedHashMap<>();
		double h = humidity;
		// Anbennar adaptation of the C2C desert band: the script gates desert on temperature alone (hot
		// equator), because it has no per-province aridity. Anbennar authors an `arid` climate, so a DRY
		// province reads desert/dunes/scrub across its latitude range (a cold-ish steppe desert), not just
		// when scorching — otherwise a mid-latitude arid province cools below 30 and turns to plains.
		if (temperature > 30 || (h < 0.25 && temperature > 10))
			bump(w, "TERRAIN_DESERT", 7 * (1.5 - h));
		if (temperature > 15 && temperature < 39)
			bump(w, "TERRAIN_PLAINS", 7);
		if (temperature > 4 && temperature < 30)
			bump(w, "TERRAIN_GRASSLAND", 7 * (h + 0.5));
		if (temperature > -2 && temperature < 25)
			bump(w, "TERRAIN_PLAINS", 7);
		if (temperature > -5 && temperature < 18 && h > 0.35)   // wetland needs moisture — a dry province stays steppe
			bump(w, "TERRAIN_MARSH", 10);
		if (temperature > -10 && temperature < 10)
			bump(w, "TERRAIN_TAIGA", 7);                                          // C2C "tundra"
		if (temperature < 0)
			bump(w, "TERRAIN_PERMAFROST", (temperature < -30 ? 15 : 7) * (h / 2 + 0.75)); // C2C "snow" → coldest land
		if (temperature < 0 && temperature >= -20)
			bump(w, "TERRAIN_TUNDRA", (temperature < -10 ? 15 : 7) * (h / 2 + 0.75));      // C2C "permafrost"
		if (w.isEmpty())
			w.put("TERRAIN_GRASSLAND", 1.0);
		return w;
	}

	/**
	 * Stage 2 — the C2C "Diversify terrain" weighted variant pool for a base land terrain (its
	 * {@code randomTerrain} weights), or {@code null} for a terrain the script does not diversify
	 * (marsh, the cold terrains).
	 */
	public static Map<String, Double> diversify(String baseType) {
		Map<String, Double> w = new LinkedHashMap<>();
		switch (baseType) {
			case "TERRAIN_DESERT" -> {
				w.put("TERRAIN_DESERT", 2.0);
				w.put("TERRAIN_SALT_FLATS", 1.0);
				w.put("TERRAIN_DUNES", 4.0);
				w.put("TERRAIN_SCRUB", 3.0);
			}
			case "TERRAIN_PLAINS" -> {
				w.put("TERRAIN_PLAINS", 3.0);
				w.put("TERRAIN_BARREN", 1.0);
				w.put("TERRAIN_ROCKY", 2.0);
			}
			case "TERRAIN_GRASSLAND" -> {
				w.put("TERRAIN_GRASSLAND", 2.0);
				w.put("TERRAIN_LUSH", 3.0);
				w.put("TERRAIN_MUDDY", 1.0);
			}
			default -> {
				return null;
			}
		}
		return w;
	}

	/**
	 * The terrain-generation temperature (°C, on the C2C band scale) for a province: an Anbennar
	 * climate-band base, cooled by winter severity, the poleward latitude term, and a per-region offset.
	 * Calibrated so each band lands in its intended C2C terrain range — tropical/arid read hot (desert vs
	 * grass split by humidity), temperate mild, arctic cold. Separate from {@link ClimateProfile#temperature}
	 * (which the feature stage reads on its own scale), so the two can be tuned independently.
	 */
	public static double temperature(Province p) {
		double base = switch (p.climate()) {
			case TROPICAL -> 26.0;   // solidly in the grass band (humid → grass/lush); jungle from the feature stage
			case ARID -> 32.0;       // hot + dry → desert/scrub/dunes (the dry-desert gate keeps it desert when cooled)
			case TEMPERATE -> 18.0;  // grass/plains/marsh; cools to taiga/tundra at high latitude
			case ARCTIC -> -3.0;     // taiga/tundra/permafrost
		};
		base -= LatitudeClimate.winterOffset(p.winter());
		base -= Math.max(0, Math.abs(p.latitude()) - 30) * 0.4;   // cooler beyond the subtropics
		base += LatitudeClimate.regionTempOffset(p);              // lore anomalies (e.g. Yarikhoi runs warm)
		return base;
	}

	/**
	 * A climate-appropriate <b>barren</b> pool for an IMPASSABLE wasteland (not a C2C stage — a CivStudio
	 * choice, {@code docs/plot-generator.md}): hot waste → desert/badland/dunes, cold waste → rocky/jagged/
	 * permafrost, temperate waste → rocky/barren/scrub. All impassable, so relief carries the mountains.
	 */
	public static Map<String, Double> barrenPool(double temperature) {
		Map<String, Double> w = new LinkedHashMap<>();
		if (temperature > 24) {
			w.put("TERRAIN_DESERT", 3.0);
			w.put("TERRAIN_BADLAND", 3.0);
			w.put("TERRAIN_DUNES", 1.0);
			w.put("TERRAIN_ROCKY", 2.0);
		} else if (temperature < 2) {
			w.put("TERRAIN_ROCKY", 3.0);
			w.put("TERRAIN_JAGGED", 2.0);
			w.put("TERRAIN_PERMAFROST", 2.0);
			w.put("TERRAIN_BARREN", 1.0);
		} else {
			w.put("TERRAIN_ROCKY", 3.0);
			w.put("TERRAIN_BARREN", 2.0);
			w.put("TERRAIN_SCRUB", 2.0);
			w.put("TERRAIN_JAGGED", 1.0);
		}
		return w;
	}

	private static void bump(Map<String, Double> w, String type, double by) {
		w.merge(type, by, Double::sum);
	}
}
