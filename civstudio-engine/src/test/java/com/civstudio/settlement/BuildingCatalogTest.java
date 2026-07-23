package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link BuildingCatalog} / {@link BuildingInfo} — the runtime building-catalog reader
 * (build-queue B2). Loads through the suite-wide world-bundle fixture; the
 * cost/kind/obsolescence semantics are unit-tested on hand-built rows so they hold
 * regardless of the fixture's vintage.
 */
class BuildingCatalogTest {

	@Test
	void catalogLoadsFromFixture() {
		BuildingCatalog catalog = BuildingCatalog.get();
		assertFalse(catalog.all().isEmpty(), "fixture should carry the imported building catalog");
		// a known early building must resolve by id with its C2C cost
		BuildingInfo row = catalog.all().get(0);
		assertNotNull(row.id());
		assertEquals(row, catalog.byId(row.id()));
	}

	@Test
	void housingLineIsIndexed() {
		BuildingCatalog catalog = BuildingCatalog.get();
		assertFalse(catalog.housing().isEmpty(), "the fixture carries the in-horizon housing line");
		for (BuildingInfo b : catalog.housing()) {
			assertTrue(b.housing());
			assertTrue(b.id().startsWith("BUILDING_HOUSING_"));
			assertEquals(b, catalog.byId(b.id()));
		}
		// a stable early rung with its B2 fields: kind, obsolescence, the replacement chain
		BuildingInfo barkHuts = catalog.byId("BUILDING_HOUSING_BARK_HUTS");
		assertNotNull(barkHuts);
		assertTrue(barkHuts.housing());
		assertEquals("TECH_SURVEYING", barkHuts.obsoleteTech());
		assertNotNull(barkHuts.replacedBy());
		assertFalse(barkHuts.replacedBy().isEmpty());
	}

	@Test
	void flavorsRideTheFixture() {
		// the C2C <Flavors> AI weights are the B4 brain's ordering signal — at least one
		// imported row must carry a positive flavor sum
		assertTrue(BuildingCatalog.get().all().stream().anyMatch(b -> b.flavorSum() > 0),
				"some building should carry C2C flavors");
	}

	@Test
	void effectiveCostPrefersAuthored() {
		BuildingInfo authored = row("BUILDING_X", 60, 25, null);
		assertEquals(25, authored.effectiveCost());
		assertTrue(authored.buildable());

		BuildingInfo imported = row("BUILDING_Y", 60, null, null);
		assertEquals(60, imported.effectiveCost());
		assertTrue(imported.buildable());
	}

	@Test
	void costlessRowIsUnbuildable() {
		// the C2C bookkeeping autobuilds (civics, pests, resources) ship no iCost — they
		// must be unbuildable without special-casing
		BuildingInfo bookkeeping = row("BUILDING_PESTS_RATS", null, null, null);
		assertNull(bookkeeping.effectiveCost());
		assertFalse(bookkeeping.buildable());
	}

	@Test
	void housingKindAndFlavorSum() {
		BuildingInfo hut = row("BUILDING_HOUSING_BARK_HUTS", null, 20, "housing");
		assertTrue(hut.housing());
		assertTrue(hut.buildable());

		BuildingInfo flavored = new BuildingInfo("BUILDING_Z", null, null, "TECH_A", null,
				40, null, null, null, Map.of("FLAVOR_GROWTH", 5, "FLAVOR_GOLD", 2), null);
		assertEquals(7, flavored.flavorSum());
		assertEquals(0, row("BUILDING_W", 10, null, null).flavorSum());
	}

	@Test
	void unknownIdResolvesNull() {
		assertNull(BuildingCatalog.get().byId("BUILDING_NO_SUCH"));
	}

	private static BuildingInfo row(String id, Integer cost, Integer authoredCost, String kind) {
		return new BuildingInfo(id, null, null, "TECH_A", null, cost, authoredCost, null, kind,
				null, (List<String>) null);
	}
}
