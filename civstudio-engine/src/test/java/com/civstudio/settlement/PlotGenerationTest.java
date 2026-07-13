package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.PlotType;
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
			out.add(c.getDistrictPlots().get(i).terrain().type());
		}
		return out;
	}

	// claim the colony's whole province (every plot in its shared pool, draining it),
	// returning the terrain type of each laid plot. Claims nearest-center first, so a
	// small central cluster is laid before the periphery; draining the pool guarantees
	// the full province silhouette is covered. Stops when the pool is exhausted.
	private static List<String> claimWholeProvince(Settlement c, int provincePlots) {
		try {
			for (int i = 0; i < provincePlots; i++)
				c.claimPlot(new PlotOccupant() {
				});
		} catch (IllegalStateException poolDrained) {
			// the province pool ran out (peaks consume the cap without seating) — done
		}
		List<String> out = new ArrayList<>();
		for (Plot p : c.getDistrictPlots())
			out.add(p.terrain().type());
		return out;
	}

	@Test
	void provinceColonyGeneratesDeterministicVariedTerrain() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();

		// same seed -> identical terrain sequence (the terrain stream is reproducible)
		List<String> a = claimWholeProvince(
				new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh), dh.plots());
		List<String> b = claimWholeProvince(
				new GameSession(7).newSettlement("B", START, 30, 26, 5, 2, dh), dh.plots());
		assertEquals(a, b, "plot generation is deterministic per seed");

		// the real province map grounds the colony in more than one terrain (Dhenijansar
		// reads as plains/grassland with a desert fringe), not the uniform baseline a
		// province-less colony gets
		Set<String> distinct = new LinkedHashSet<>(a);
		assertTrue(distinct.size() >= 2,
				"a province colony's terrain varies across its real map, got " + distinct);
	}

	@Test
	void provinceLessColonyIsUniformlyBaseline() {
		Settlement bare = new GameSession(7).newSettlement("Bare", START, 30, 26, 5, 2,
				51.5074, -0.1278);
		for (String t : claimTerrains(bare, 30))
			assertEquals(TerrainGenerator.BASELINE_TERRAIN, t,
					"a province-less colony's plots are all baseline terrain");
		// a province-less colony's plots are all flat (no relief generation)
		for (Plot p : bare.getDistrictPlots())
			assertEquals(PlotType.FLAT, p.plotType(),
					"a province-less colony's plots are all flat");
	}

	@Test
	void provinceColonyGeneratesVariedReliefAndNeverSeatsAPeak() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();
		Settlement c = new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh);

		// seat a good run of occupants; with peaks counting toward the cap, this lays
		// more plots than firms — every seated occupant must be on a workable plot,
		// and the ladder should carry some non-flat relief over enough plots
		List<PlotOccupant> seated = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			PlotOccupant o = new PlotOccupant() {
			};
			c.claimPlot(o);
			seated.add(o);
		}
		Set<PlotType> relief = new LinkedHashSet<>();
		for (Plot p : c.getDistrictPlots())
			relief.add(p.plotType());
		assertTrue(relief.size() >= 2,
				"the province ladder should carry varied relief, got " + relief);
		// a peak is unworkable and never seated
		for (Plot p : c.getDistrictPlots())
			if (!p.isWorkable())
				assertTrue(p.isVacant(), "a peak is never seated");
	}
}
