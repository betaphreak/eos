package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The {@link UnitCatalog} — the engine-side reader of the imported {@code units.json} that a band
 * uses to embody a unit. Covers the load and the {@code pickBest} selection rule (unlock, obsolescence,
 * most-advanced ordering); see {@code docs/c2c-unit-import.md} §Phase 5.
 */
class UnitCatalogTest {

	private static final UnitCatalog CAT = UnitCatalog.get();

	@Test
	void catalogLoadsExplorerUnits() {
		List<UnitInfo> explorers = CAT.forRole(CaravanRole.EXPLORER);
		assertFalse(explorers.isEmpty(), "the imported catalog has EXPLORER units");
		for (UnitInfo u : explorers) {
			assertEquals(CaravanRole.EXPLORER, u.role());
			assertNotNull(u.id());
			assertTrue(u.id().startsWith("UNIT_"), u.id());
		}
	}

	@Test
	void pickBestReturnsAGrantedNonObsoleteUnit() {
		UnitInfo any = CAT.forRole(CaravanRole.EXPLORER).get(0);
		UnitInfo picked = CAT.pickBest(CaravanRole.EXPLORER, Set.of(any.id()), Set.of());
		assertNotNull(picked, "a granted, non-obsolete unit is fielded");
		assertEquals(any.id(), picked.id());
	}

	@Test
	void pickBestReturnsNullWhenNothingGranted() {
		assertNull(CAT.pickBest(CaravanRole.EXPLORER, Set.of(), Set.of()),
				"a colony with no unlocked units of the role fields none");
	}

	@Test
	void pickBestSkipsAnObsoletedUnit() {
		UnitInfo obsoletable = CAT.forRole(CaravanRole.EXPLORER).stream()
				.filter(u -> u.obsoleteTech() != null).findFirst().orElse(null);
		if (obsoletable == null)
			return; // no obsoletable explorer in the horizon — nothing to assert
		assertNull(CAT.pickBest(CaravanRole.EXPLORER, Set.of(obsoletable.id()),
				Set.of(obsoletable.obsoleteTech())),
				"a unit whose obsoleteTech is researched is not fielded");
		assertEquals(obsoletable.id(),
				CAT.pickBest(CaravanRole.EXPLORER, Set.of(obsoletable.id()), Set.of()).id(),
				"…but the same unit IS fielded before its obsoleteTech is known");
	}

	@Test
	void pickBestPrefersTheMoreAdvanced() {
		List<UnitInfo> ex = CAT.forRole(CaravanRole.EXPLORER).stream()
				.filter(u -> u.iCost() != null).sorted(Comparator.comparingInt(UnitInfo::iCost)).toList();
		if (ex.size() < 2 || ex.get(0).iCost().equals(ex.get(ex.size() - 1).iCost()))
			return; // need two explorer units with distinct costs to compare
		UnitInfo lo = ex.get(0), hi = ex.get(ex.size() - 1);
		UnitInfo picked = CAT.pickBest(CaravanRole.EXPLORER, Set.of(lo.id(), hi.id()), Set.of());
		assertEquals(hi.id(), picked.id(), "the higher-iCost (more advanced) unit is fielded");
	}
}
