package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.geo.ProvinceType;
import com.civstudio.geo.WorldMap;

/**
 * Verifies a colony can be founded <b>into a province</b>: the province supplies
 * the colony's latitude/longitude (its climate, via the solar system) and its
 * {@code plots} hard-cap the settlement's size (build slots are plots). See
 * {@code docs/geography.md} (Phase 2).
 */
class SettlementProvinceTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private static Settlement found(GameSession s, Province p) {
		return s.newSettlement("Test", START, 30, 26, 5, 2, p);
	}

	@Test
	void takesItsGeographyFromTheProvince() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = found(s, dh);

		assertSame(dh, c.getProvince());
		assertEquals(23.16, c.getLatitude(), 1e-6);
		assertEquals(76.43, c.getLongitude(), 1e-6);
	}

	@Test
	void plotsCapTheSettlementSize() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = found(s, dh);

		// 74 plots: the cap is the province's plot count directly (build slots are plots)
		assertEquals(74, c.getMaxPlots());

		// founding seats firms until the cap, then refuses to grow past it: the 74
		// plots fill, and the 75th occupant cannot be seated.
		for (int i = 0; i < 74; i++)
			c.claimPlot(new PlotOccupant() {
			});
		assertEquals(74, c.getPlotCount());
		assertThrows(IllegalStateException.class,
				() -> c.claimPlot(new PlotOccupant() {
				}));
	}

	@Test
	void tropicalLatitudeHasAMilderDaylightSwingThanLondon() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement tropical = found(s, dh);
		// a London-latitude colony founded the old way (bare coordinates)
		Settlement london = s.newSettlement("London", START, 30, 26, 5, 2,
				51.5074, -0.1278);

		double tropicalDaylight = tropical.getDaylightHours();
		double londonDaylight = london.getDaylightHours();
		// near the winter solstice the tropics keep far more daylight than London
		assertTrue(tropicalDaylight > 10 && tropicalDaylight < 12,
				"tropical winter daylight ~10.7h, was " + tropicalDaylight);
		assertTrue(tropicalDaylight > londonDaylight,
				"tropics should outlast London in winter: " + tropicalDaylight
						+ " vs " + londonDaylight);
	}

	@Test
	void coordinateFoundedColonyIsUncapped() {
		GameSession s = new GameSession(42);
		Settlement c = s.newSettlement("Bare", START, 30, 26, 5, 2, 51.5074, -0.1278);
		assertNull(c.getProvince());
		assertEquals(Settlement.PROVINCE_LESS_PLOT_CAP, c.getMaxPlots());
	}

	@Test
	void climateScalesTheAgricultureMultiplier() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = found(s, dh);
		// Dhenijansar: temperate (1.0) x no winter (1.0) x normal monsoon (1.10)
		assertEquals(1.10, c.getAgricultureClimateMultiplier(), 1e-9);

		// a coordinate-founded colony has no province, so the multiplier is neutral
		Settlement bare = s.newSettlement("Bare", START, 30, 26, 5, 2, 51.5074, -0.1278);
		assertEquals(1.0, bare.getAgricultureClimateMultiplier(), 1e-9);
	}

	@Test
	void provinceTooSmallForTheFoundingFloorIsRejected() {
		GameSession s = new GameSession(42);
		// a synthetic province with fewer plots than size 3 needs (total 28)
		Province tiny = new Province(99999, "Tiny", 0.0, 0.0, 10, 0,
				ProvinceType.LAND, null, null, null, null, null, null, List.of());
		assertThrows(IllegalArgumentException.class, () -> found(s, tiny));
	}

	@Test
	void worldMapProvinceRoundTripsThroughTheSession() {
		// the founding province really comes from the shared world map
		GameSession s = new GameSession(7);
		WorldMap map = s.getWorldMap();
		Province dh = map.findByName("Dhenijansar").orElseThrow();
		Settlement c = found(s, dh);
		assertSame(map.province(4411), c.getProvince());
	}
}
