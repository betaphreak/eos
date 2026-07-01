package com.civstudio.geo;

import java.util.Arrays;
import java.util.List;

/**
 * A Civ4 terrain feature — an optional overlay on a {@link
 * com.civstudio.settlement.Plot plot}'s base terrain (forest, jungle, oasis…),
 * contributing an additive yield change on top of the host terrain's triple and
 * carrying a clear cost (the work to remove it). Exported (curated to the
 * land-feature subset) from {@code data/civ4/CIV4FeatureInfos.xml} by
 * {@link com.civstudio.geo.export.FeatureExporter} into {@code /features.json},
 * loaded by {@link TerrainRegistry}. See {@code docs/plots.md}.
 *
 * @param type              the Civ4 type key (e.g. {@code FEATURE_FOREST})
 * @param yieldChanges      additive {@code [df, dp, dc]} on the host terrain
 *                          (length 3)
 * @param clearCost         the work to remove the feature
 *                          ({@code <iAdvancedStartRemoveCost>})
 * @param requiresFlatlands generation constraint — only on flat plots
 * @param requiresRiver     generation constraint — only on river plots
 * @param validTerrains     the host terrains this feature may sit on (from the
 *                          {@code <TerrainBooleans>} list)
 * @param healthPercent     stored but dormant (future health axis)
 * @param growth            stored but dormant (future feature-spread)
 */
public record Feature(
		String type,
		int[] yieldChanges,
		int clearCost,
		boolean requiresFlatlands,
		boolean requiresRiver,
		List<String> validTerrains,
		int healthPercent,
		int growth) {

	/** Normalize yields to length 3 and copy the valid-terrains list. */
	public Feature {
		yieldChanges = Terrain.pad3(yieldChanges);
		validTerrains = validTerrains == null ? List.of() : List.copyOf(validTerrains);
	}

	/** A yield-change component by index (0 = food, 1 = production, 2 = commerce). */
	public int yieldChange(int i) {
		return yieldChanges[i];
	}

	@Override
	public String toString() {
		return type + Arrays.toString(yieldChanges);
	}
}
