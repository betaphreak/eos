package com.civstudio.geo;

/**
 * The seven <b>Python terrain categories</b> the Caveman2Cosmos planet generator's
 * {@code addFeatures} branches its feature weights on ({@code desert / plains /
 * grass / marsh / tundra / permafrost / snow}), plus {@link #OTHER} for eos terrains
 * with no C2C peer. eos grounds each plot on a curated Civ4 terrain drawn from the
 * real map (a wider set than the seven the script names), so the ported weight
 * tables need this classifier to fold an eos {@link Terrain} back onto the category
 * the script would have branched on. See {@code docs/c2c-generator-port.md}.
 * <p>
 * <b>Mind the name swap</b> (script variable names ≠ XML ids, L2676–2693): the
 * script's {@code terrainTundra} is {@code TERRAIN_TAIGA}, its {@code
 * terrainPermafrost} is {@code TERRAIN_TUNDRA}, and its {@code terrainSnow} is
 * {@code TERRAIN_ICE}. So eos {@code TAIGA} → {@link #TUNDRA}, eos {@code TUNDRA} →
 * {@link #PERMAFROST}, eos {@code ICE}/{@code PERMAFROST} → {@link #SNOW}. Getting
 * this wrong inverts every cold-terrain feature rule. The C2C-diversified variants
 * ({@code SALT_FLATS}/{@code DUNES}/{@code SCRUB} of desert; {@code BARREN}/{@code
 * ROCKY} of plains; {@code LUSH}/{@code MUDDY} of grass — L3143–3161) fold back onto
 * their parent category so a diversified plot keeps its parent's feature behaviour.
 */
public enum PyTerrain {

	/** {@code terrainDesert} — jungle-on-sand, floodplains, oasis host. */
	DESERT,
	/** {@code terrainPlains} — forest + (hot) jungle. */
	PLAINS,
	/** {@code terrainGrass} — the wettest non-marsh; most forest, some jungle. */
	GRASS,
	/** {@code terrainMarsh} — swamp host. */
	MARSH,
	/** Script {@code terrainTundra} = XML {@code TERRAIN_TAIGA} = eos {@code TAIGA} — forest, no jungle. */
	TUNDRA,
	/** Script {@code terrainPermafrost} = XML {@code TERRAIN_TUNDRA} = eos {@code TUNDRA} — forest, no jungle. */
	PERMAFROST,
	/** Script {@code terrainSnow} = XML {@code TERRAIN_ICE} (+ eos {@code PERMAFROST}) — no jungle. */
	SNOW,
	/** An eos terrain with no C2C category (only the temperature-driven weights apply). */
	OTHER;

	/**
	 * The Python feature category of an eos {@link Terrain}, minding the {@linkplain
	 * PyTerrain name swap}. The C2C-diversified variants fold onto their parent
	 * category; eos-only arid/rocky terrains map to the nearest peer.
	 *
	 * @param terrain the plot's ground terrain
	 * @return the category the script's weight tables branch on ({@link #OTHER} if none)
	 */
	public static PyTerrain of(Terrain terrain) {
		if (terrain == null)
			return OTHER;
		return switch (terrain.type()) {
			case "TERRAIN_DESERT", "TERRAIN_SALT_FLATS", "TERRAIN_DUNES",
					"TERRAIN_SCRUB", "TERRAIN_BADLAND" -> DESERT;
			case "TERRAIN_PLAINS", "TERRAIN_BARREN", "TERRAIN_ROCKY", "TERRAIN_JAGGED" -> PLAINS;
			case "TERRAIN_GRASSLAND", "TERRAIN_LUSH", "TERRAIN_MUDDY" -> GRASS;
			// the Anbennar forest-family terrains are grass-based, so foliage still spawns
			case "TERRAIN_ANCIENT_FOREST", "TERRAIN_GLADEWAY", "TERRAIN_FEY_GLADEWAY",
					"TERRAIN_BLOODGROVES" -> GRASS;
			case "TERRAIN_MARSH", "TERRAIN_SHADOW_SWAMP" -> MARSH;
			case "TERRAIN_TAIGA" -> TUNDRA;         // script "tundra"
			case "TERRAIN_TUNDRA" -> PERMAFROST;    // script "permafrost"
			case "TERRAIN_ICE", "TERRAIN_PERMAFROST", "TERRAIN_GLACIER" -> SNOW; // script "snow"
			default -> OTHER;                       // TERRAIN_CAVERN, TERRAIN_MUSHROOM_FOREST, …
		};
	}

	/** Whether jungle is forbidden here (the script's cold terrains: tundra/permafrost/snow, L2846). */
	public boolean isCold() {
		return this == TUNDRA || this == PERMAFROST || this == SNOW;
	}
}
