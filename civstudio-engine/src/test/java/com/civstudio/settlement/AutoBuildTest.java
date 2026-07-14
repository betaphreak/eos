package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.tech.TechEffect;

/**
 * Covers Phase D2 auto-build ({@code docs/district-buildout.md}): a building-unlocking
 * tech places its building onto the colony's district plots, but only when auto-build
 * is enabled (off by default, so runs stay byte-identical). Placement is render-only —
 * buildings carry no yield.
 */
class AutoBuildTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	// a colony with one laid plot (the village center, plot 0)
	private static Settlement centeredColony() {
		GameSession s = new GameSession(42);
		Province dh = s.getWorldMap().findByName("Dhenijansar").orElseThrow();
		Settlement c = s.newSettlement("Test", START, 30, 26, 5, 2, dh);
		c.claimPlot(new PlotOccupant() {
		}); // lay plot 0
		return c;
	}

	@Test
	void enabledAutoBuildPlacesTheBuildingAtTheCenter() {
		Settlement c = centeredColony();
		c.setAutoBuildDistricts(true);

		c.applyTechEffect(new TechEffect.Unlock("BUILDING_ORCHARD"));

		assertTrue(c.getDistrictPlots().get(0).hasBuilding("BUILDING_ORCHARD"),
				"the unlocked building lands on the village center");
	}

	@Test
	void disabledByDefaultPlacesNothing() {
		Settlement c = centeredColony();
		// no setAutoBuildDistricts — the default is off
		assertFalse(c.isAutoBuildDistricts());

		c.applyTechEffect(new TechEffect.Unlock("BUILDING_ORCHARD"));

		assertTrue(c.getDistrictPlots().get(0).buildings().isEmpty(),
				"with auto-build off, researching places no building");
		// the token is still granted (existing behavior, unaffected)
		assertTrue(c.getGrantedTechTokens().contains("BUILDING_ORCHARD"));
	}

	@Test
	void reapplyingTheSameUnlockIsIdempotent() {
		Settlement c = centeredColony();
		c.setAutoBuildDistricts(true);

		c.applyTechEffect(new TechEffect.Unlock("BUILDING_ORCHARD"));
		c.applyTechEffect(new TechEffect.Unlock("BUILDING_ORCHARD"));

		long count = c.getDistrictPlots().get(0).buildings().stream()
				.filter(b -> b.id().equals("BUILDING_ORCHARD")).count();
		assertEquals(1, count, "a re-researched unlock does not double-place");
	}

	@Test
	void nonBuildingTokensAreNotPlaced() {
		Settlement c = centeredColony();
		c.setAutoBuildDistricts(true);

		c.applyTechEffect(new TechEffect.Unlock("GOOD_PAPER"));

		assertTrue(c.getDistrictPlots().get(0).buildings().isEmpty(),
				"a non-BUILDING_ unlock token places no building");
	}
}
