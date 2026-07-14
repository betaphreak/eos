package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the water-dominant urban-core siting ({@code docs/settlement-tiers.md}): over otherwise
 * identical ground, {@link CityPlacement} sites the centre on the plot with the most adjacent
 * water — a coastal or riverside cell wins against plain inland land. Pure, deterministic scan.
 */
class CityPlacementTest {

	private static final int W = 5, H = 5;

	// a uniform-grass 5×5 grid: every cell foundable land, so the only differentiator the test
	// leaves is the water term (coast / river) injected per case
	private static Terrain[] grassGrid() {
		Terrain grass = TerrainRegistry.load().terrain(TerrainGenerator.BASELINE_TERRAIN);
		Terrain[] ground = new Terrain[W * H];
		Arrays.fill(ground, grass);
		return ground;
	}

	private static PlotType[] flatGrid() {
		PlotType[] relief = new PlotType[W * H];
		Arrays.fill(relief, PlotType.FLAT);
		return relief;
	}

	private static List<int[]> allCells() {
		List<int[]> cells = new ArrayList<>();
		for (int ly = 0; ly < H; ly++)
			for (int lx = 0; lx < W; lx++)
				cells.add(new int[] { lx, ly });
		return cells;
	}

	private static ProvinceMask mask(int[] river, int[] coast) {
		boolean[] land = new boolean[W * H];
		Arrays.fill(land, true);
		return new ProvinceMask(0, 0, W, H, land, river, coast, null, null, null, null);
	}

	private static int primary(ProvinceMask mask) {
		return CityPlacement.coreCells(W, H, allCells(), grassGrid(), flatGrid(),
				new Feature[W * H], new Bonus[W * H], mask, 1).get(0);
	}

	@Test
	void coastalCellBeatsPlainInlandLand() {
		int[] coast = new int[W * H];
		// the west-edge cell (1,2) borders water on three sides — the prized coastal site
		int coastal = 2 * W + 1;
		coast[coastal] = 0b0111; // three water edges
		assertEquals(coastal, primary(mask(new int[W * H], coast)),
				"the coastal cell outscores the higher-yield centre");
	}

	@Test
	void riversideCellBeatsPlainInlandLand() {
		int[] river = new int[W * H];
		// a river runs down the west column (0,1)-(0,2)-(0,3); the on-river cell (0,2) wins
		river[1 * W] = 21;
		river[2 * W] = 21;
		river[3 * W] = 21;
		assertEquals(2 * W, primary(mask(river, new int[W * H])),
				"the riverside cell outscores the higher-yield centre");
	}

	@Test
	void withNoWaterTheHighestYieldCentreWins() {
		// no coast, no river → the plain foundValue picks the max-reachable-yield cell: the centre
		assertEquals(2 * W + 2, primary(mask(new int[W * H], new int[W * H])),
				"absent water, the central cell (most reachable yield) is chosen");
	}
}
