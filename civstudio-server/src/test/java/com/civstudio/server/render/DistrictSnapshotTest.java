package com.civstudio.server.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.PlotOccupant;
import com.civstudio.settlement.Settlement;
import com.civstudio.tech.TechEffect;

/**
 * Verifies the Phase D3 district projection ({@code docs/district-buildout.md}): a colony's
 * {@link ColonyView} carries its starting district count, its culture, and the buildings
 * auto-build placed on its district plots, all the way through {@link Snapshots#of}.
 */
class DistrictSnapshotTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private static ColonyView project(Settlement c) {
		return Snapshots.of("t", 42, "test", "RUNNING", null, 0, List.of(c), null,
				List.of(), List.of()).colonies().get(0);
	}

	@Test
	void carriesStartingDistrictsAndCulture() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);

		ColonyView view = project(c);
		assertEquals(c.getStartingDistrictCount(), view.startingDistricts());
		assertTrue(view.startingDistricts() > 0, "the demo province carries EU4 development");
		assertNotNull(view.culture(), "a province-founded colony reports its culture");
		assertTrue(view.districts().isEmpty(), "no buildings placed yet → no district rows");
	}

	@Test
	void carriesTheCityCenterPlotNotTheProvinceAnchor() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);
		c.claimPlot(new PlotOccupant() {
		}); // lay plot 0 (the village center)

		ColonyView view = project(c);
		Plot center = c.getCityCenter();
		assertNotNull(center, "the colony laid its center plot");
		assertEquals(center.x(), view.centerX(), "the view reports the center plot's raster x");
		assertEquals(center.y(), view.centerY(), "the view reports the center plot's raster y");
		// the whole point of the field: lat/lon are the PROVINCE's anchor, so a client deriving the
		// centre from them can land on the wrong plot (docs/urban-plots.md). The centre is the
		// water-first pick, which need not be the plot under the province point.
		assertTrue(center.river() || center.coast() != 0 || center.riverAdj() != 0,
				"the water-first centre sits on or beside water");
	}

	@Test
	void reportsNoCenterBeforeAPlotIsLaid() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);

		ColonyView view = project(c);
		assertNull(view.centerX(), "no plot laid → no centre to report");
		assertNull(view.centerY());
	}

	@Test
	void projectsAutoBuiltBuildingsOntoTheCenterDistrict() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);
		c.claimPlot(new PlotOccupant() {
		}); // lay plot 0 (the village center)
		c.setAutoBuildDistricts(true);
		c.applyTechEffect(new TechEffect.Unlock("BUILDING_ORCHARD"));

		ColonyView view = project(c);
		assertFalse(view.districts().isEmpty(), "the auto-built building shows in the feed");
		DistrictView center = view.districts().get(0);
		assertEquals(0, center.index(), "it lands at the village center, plot 0");
		assertTrue(center.buildings().contains("BUILDING_ORCHARD"));
	}
}
