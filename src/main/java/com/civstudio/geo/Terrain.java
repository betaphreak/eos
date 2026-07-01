package com.civstudio.geo;

import java.util.Arrays;

/**
 * A Civ4 base terrain — the ground a {@link com.civstudio.settlement.Plot plot}
 * sits on, carrying its base Food/Production/Commerce yield. Exported (curated to
 * the settleable land subset) from {@code data/civ4/CIV4TerrainInfos.xml} by
 * {@link com.civstudio.geo.export.TerrainExporter} into {@code /terrains.json},
 * loaded by {@link TerrainRegistry}. See {@code docs/plots.md}.
 * <p>
 * Hills and peaks are <em>not</em> terrains here — they are a separate per-plot
 * {@code PlotType} axis (Phase 3), so {@code TERRAIN_HILL}/{@code TERRAIN_PEAK}
 * are excluded from the curated set.
 *
 * @param type          the Civ4 type key (e.g. {@code TERRAIN_GRASSLAND})
 * @param yields        base {@code [food, production, commerce]} (length 3)
 * @param bFound        whether the terrain is settleable ({@code <bFound>})
 * @param buildModifier percent build-cost surcharge on this terrain
 *                      ({@code <iBuildModifier>})
 * @param healthPercent stored but dormant (future disease/health axis)
 */
public record Terrain(
		String type,
		int[] yields,
		boolean bFound,
		int buildModifier,
		int healthPercent,
		int movement) {

	/** Normalize {@code yields} to a defensive length-3 copy (missing → 0). */
	public Terrain {
		yields = pad3(yields);
	}

	static int[] pad3(int[] in) {
		int[] out = new int[3];
		if (in != null)
			System.arraycopy(in, 0, out, 0, Math.min(in.length, 3));
		return out;
	}

	/** A yield component by index (0 = food, 1 = production, 2 = commerce). */
	public int yield(int i) {
		return yields[i];
	}

	@Override
	public String toString() {
		return type + Arrays.toString(yields);
	}
}
