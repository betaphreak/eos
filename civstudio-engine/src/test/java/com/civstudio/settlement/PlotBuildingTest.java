package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.PlotType;
import com.civstudio.geo.Terrain;
import com.civstudio.geo.TerrainRegistry;

/**
 * Phase-1 coverage for the plot <b>buildings</b> data model (see {@code docs/plots.md},
 * <i>Buildings vs. improvements</i>): a {@link Plot} tracks a collection of center
 * {@link Building}s — Civ4-style city buildings — distinct from its single tile
 * {@link Plot#improvement() improvement}. This is the data model only: nothing
 * populates buildings in a run and they carry no yield, so the change is
 * byte-identical; this test exercises the tracking directly, including the
 * yields-unchanged guard that makes it byte-identical.
 */
class PlotBuildingTest {

	private static final Terrain GRASSLAND =
			TerrainRegistry.load().terrain("TERRAIN_GRASSLAND");

	private static Plot plot() {
		return new Plot(0, GRASSLAND, PlotType.FLAT, null);
	}

	@Test
	void aFreshPlotHasNoBuildings() {
		Plot p = plot();
		assertTrue(p.buildings().isEmpty());
		assertFalse(p.hasBuilding("FIRM_BANKING_HOUSE"));
	}

	@Test
	void addBuildingTracksItById() {
		Plot p = plot();
		p.addBuilding(new Building("FIRM_BANKING_HOUSE"));
		assertEquals(1, p.buildings().size());
		assertTrue(p.hasBuilding("FIRM_BANKING_HOUSE"));
		assertFalse(p.hasBuilding("BUILDING_GRANARY"));
	}

	@Test
	void theBuildingsViewIsUnmodifiable() {
		Plot p = plot();
		assertThrows(UnsupportedOperationException.class,
				() -> p.buildings().add(new Building("X")));
	}

	@Test
	void trackingABuildingDoesNotChangeYields() {
		// the Phase-1 byte-identical guard: a building is not a yield leg (its effect is
		// deferred), so adding one must leave the plot's yields exactly as they were
		Plot p = plot();
		int[] before = p.yields();
		p.addBuilding(new Building("FIRM_BANKING_HOUSE"));
		assertArrayEquals(before, p.yields());
	}

	@Test
	void aBuildingIdMustBePresentAndAddedBuildingNonNull() {
		assertThrows(IllegalArgumentException.class, () -> new Building(" "));
		assertThrows(IllegalArgumentException.class, () -> new Building(null));
		assertThrows(IllegalArgumentException.class, () -> plot().addBuilding(null));
	}
}
