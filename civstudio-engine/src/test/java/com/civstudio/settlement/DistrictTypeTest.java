package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.tech.Advisor;

/**
 * Covers the Phase D1 district contract ({@code docs/district-buildout.md}): the
 * {@link DistrictType} fold from the building {@link Advisor} taxonomy, the {@link
 * ArtEra} research-progress projection, and the colony's starting district count
 * (province EU4 development, capped at the province plots). Pure metadata — none of
 * this touches the firm economy.
 */
class DistrictTypeTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	@Test
	void everyAdvisorCategoryFoldsToADistrict() {
		assertEquals(DistrictType.CAMPUS, DistrictType.fromCategory(Advisor.SCIENCE));
		assertEquals(DistrictType.HOLY_SITE, DistrictType.fromCategory(Advisor.RELIGION));
		assertEquals(DistrictType.ENCAMPMENT, DistrictType.fromCategory(Advisor.MILITARY));
		assertEquals(DistrictType.COMMERCIAL_HUB, DistrictType.fromCategory(Advisor.ECONOMY));
		assertEquals(DistrictType.THEATER, DistrictType.fromCategory(Advisor.CULTURE));
		assertEquals(DistrictType.NEIGHBORHOOD, DistrictType.fromCategory(Advisor.GROWTH));
	}

	@Test
	void anUncategorizedBuildingContributesNoDistrict() {
		assertEquals(Optional.empty(), DistrictType.fromCategory(Optional.empty()));
		assertEquals(Optional.of(DistrictType.CAMPUS),
				DistrictType.fromCategory(Optional.of(Advisor.SCIENCE)));
	}

	@Test
	void artEraTracksResearchProgress() {
		// no research → the earliest era; a full tree → the latest (Renaissance cap)
		assertEquals(ArtEra.ANCIENT, ArtEra.fromProgress(0, 339));
		assertEquals(ArtEra.ANCIENT, ArtEra.fromProgress(10, 339));
		assertEquals(ArtEra.RENAISSANCE, ArtEra.fromProgress(339, 339));
		// the fraction buckets evenly across the four eras
		assertEquals(ArtEra.CLASSICAL, ArtEra.fromProgress(100, 339)); // ~0.29 → bucket 1
		assertEquals(ArtEra.MEDIEVAL, ArtEra.fromProgress(200, 339));  // ~0.59 → bucket 2
		// degenerate inputs are safe
		assertEquals(ArtEra.ANCIENT, ArtEra.fromProgress(5, 0));
	}

	@Test
	void startingDistrictCountIsProvinceDevelopmentCappedAtPlots() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);

		assertTrue(dh.development() > 0, "the demo province carries EU4 1444 development");
		assertEquals(Math.min(dh.development(), c.getMaxPlots()), c.getStartingDistrictCount());
		assertTrue(c.getStartingDistrictCount() <= c.getMaxPlots(),
				"districts never exceed the province plot cap");
	}

	@Test
	void provinceLessColonyHasNoStartingDistricts() {
		GameSession s = new GameSession(42);
		Settlement bare = s.newSettlement("Bare", START, 30, 26, 5, 2, 51.5074, -0.1278);
		assertEquals(0, bare.getStartingDistrictCount());
	}

	@Test
	void startingDistrictCountIsCappedByTier() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);
		int full = Math.min(dh.development(), c.getMaxPlots());

		c.setTier(SettlementTier.METROPOLIS);
		assertEquals(full, c.getStartingDistrictCount(),
				"a METROPOLIS fills the province's urban capacity");

		c.setTier(SettlementTier.CAMP);
		assertEquals(0, c.getStartingDistrictCount(), "a CAMP has no built centre");

		c.setTier(SettlementTier.SMALLHOLDING);
		assertEquals(Math.min(1, full), c.getStartingDistrictCount(),
				"a sub-TOWN settlement shows only its single city centre");

		c.setTier(SettlementTier.TOWN);
		int town = c.getStartingDistrictCount();
		assertTrue(town >= 1 && town <= full,
				"a TOWN's districts are population-capped, within the site's capacity");
	}
}
