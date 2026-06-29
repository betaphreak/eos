package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.geo.TerrainGenerator;

/**
 * Phase-1 coverage for the plot model's terrain generation (see {@code
 * docs/plots.md}): a province-founded colony generates its plot terrain from the
 * province's climate off a dedicated terrain stream — <b>deterministic per
 * seed</b> — while a province-less (bare-coordinate) colony uses the <b>baseline
 * terrain</b> uniformly. The generated terrain is not yet read for yield or travel
 * in this cut; this just pins the structural swap from the disc {@code SlotTable}
 * to the {@code List<Plot>}.
 */
class PlotGenerationTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	// genesis-append `n` plots onto the colony and return their terrain type keys
	private static List<String> claimTerrains(Settlement c, int n) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			c.claimPlot(new PlotOccupant() {
			});
			out.add(c.getPlots().get(i).terrain().type());
		}
		return out;
	}

	@Test
	void provinceColonyGeneratesDeterministicVariedTerrain() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();

		// same seed -> identical terrain sequence (the terrain stream is reproducible)
		List<String> a = claimTerrains(
				new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh), 40);
		List<String> b = claimTerrains(
				new GameSession(7).newSettlement("B", START, 30, 26, 5, 2, dh), 40);
		assertEquals(a, b, "plot generation is deterministic per seed");

		// the climate pool yields more than one terrain over 40 draws (it is not the
		// uniform baseline a bare colony gets)
		Set<String> distinct = new LinkedHashSet<>(a);
		assertTrue(distinct.size() >= 2,
				"a province colony's terrain varies with its climate, got " + distinct);
	}

	@Test
	void provinceLessColonyIsUniformlyBaseline() {
		Settlement bare = new GameSession(7).newSettlement("Bare", START, 30, 26, 5, 2,
				51.5074, -0.1278);
		for (String t : claimTerrains(bare, 30))
			assertEquals(TerrainGenerator.BASELINE_TERRAIN, t,
					"a province-less colony's plots are all baseline terrain");
	}
}
