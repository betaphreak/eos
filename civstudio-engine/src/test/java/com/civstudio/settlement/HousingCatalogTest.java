package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link HousingCatalog} / the {@link HousingBuilding} cost semantics (build-queue B2).
 * Loads through the suite-wide world-bundle fixture; cost semantics are unit-tested on
 * hand-built rungs so they hold regardless of the fixture's vintage.
 */
class HousingCatalogTest {

	@Test
	void catalogLoadsTheLadderFromFixture() {
		HousingCatalog catalog = HousingCatalog.get();
		assertFalse(catalog.all().isEmpty(), "fixture should carry the housing ladder");
		HousingBuilding barkHuts = catalog.byType("BUILDING_HOUSING_BARK_HUTS");
		assertNotNull(barkHuts, "the bark-huts rung is a stable early ladder entry");
		assertEquals("TECH_BARK_WORKING", barkHuts.prereqTech());
		assertFalse(barkHuts.replacements().isEmpty(), "the upgrade chain is imported");
		assertNotNull(barkHuts.name(), "rungs carry GameText display names since B2");
		assertEquals(15, barkHuts.effectiveCost(), "the hand-priced default build cost");
	}

	@Test
	void effectiveCostPrefersAuthoredOverHandPriced() {
		HousingBuilding authored = rung("BUILDING_HOUSING_X", 15, 40);
		assertEquals(40, authored.effectiveCost());
		assertTrue(authored.buildable());

		HousingBuilding handPriced = rung("BUILDING_HOUSING_Y", 15, null);
		assertEquals(15, handPriced.effectiveCost());
		assertTrue(handPriced.buildable());
	}

	@Test
	void costlessRungIsUnbuildable() {
		// the HOMELESS marker and past-horizon rungs carry no cost at all
		HousingBuilding homeless = rung("BUILDING_HOUSING_HOMELESS", null, null);
		assertNull(homeless.effectiveCost());
		assertFalse(homeless.buildable());
	}

	@Test
	void unknownTypeResolvesNull() {
		assertNull(HousingCatalog.get().byType("BUILDING_HOUSING_NO_SUCH"));
	}

	private static HousingBuilding rung(String type, Integer cost, Integer authoredCost) {
		return new HousingBuilding(type, null, cost, authoredCost, "TECH_A", null, null, 0, false,
				true, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				0, 0, new int[3], new int[4]);
	}
}
