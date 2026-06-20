package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Smoke test for the two-colony {@link HanseaticEconomy}, the worked example of
 * several colonies coexisting in one {@code GameSession} and now running
 * <b>concurrently</b> — one thread per settlement, coordinated by {@link
 * SessionRunner}'s lockstep day-barrier. Exercising it end-to-end covers the
 * whole parallel path: per-colony name/demography isolation, the thread-safe
 * session state, the per-thread log binding, and the Phaser barrier coping with
 * the two colonies dissolving at (independently drawn) different times.
 * <p>
 * The two colonies are founded into two <b>neighbouring world-map provinces</b>
 * (Withacen and Hopespeak; see {@code docs/geography.md}). Like the other
 * ruler-bearing colonies, each founds and replaces its labor force from a finite
 * peasant pool, so it runs clean (no {@code -ea} invariant tripped on either
 * thread — {@link SessionRunner} rethrows any thread failure) and then <b>departs
 * as a Caravan</b> once its reserve drains. The run returns the Withacen harness
 * (Hopespeak is also built and run on its own thread); we assert Withacen
 * dissolved, carries the default three banks (commoner copper, the ennobled
 * export nobles' silver, the ruler's gold), and was founded into its province.
 */
class HanseaticEconomyTest {

	@Test
	void bothColoniesRunConcurrentlyToCompletionThenDepart() {
		SimulationHarness withacen = assertDoesNotThrow(HanseaticEconomy::run);
		assertEquals(3, withacen.getBanks().size(),
				"Withacen should carry copper, silver and gold banks");
		SimulationAssertions.assertDepartedAsCaravan(withacen);

		// the returned colony was founded into the Withacen province (its climate)
		Settlement colony = withacen.getColony();
		assertEquals("Withacen", colony.getProvince().name());
		assertEquals(55.97, colony.getLatitude(), 1e-6);
	}

	@Test
	void theTwoColoniesFoundIntoAdjacentProvinces() {
		// the substrate: the two founding provinces are direct neighbours, so the
		// travel network (WorldMap.path) connects them in a single step — what the
		// future caravan trade will route over. No economy needed.
		WorldMap map = new GameSession(HanseaticEconomy.SEED).getWorldMap();
		int a = HanseaticEconomy.WITHACEN_PROVINCE_ID;
		int b = HanseaticEconomy.HOPESPEAK_PROVINCE_ID;

		assertEquals(55.97, map.province(a).latitude(), 1e-6);
		assertEquals(54.83, map.province(b).latitude(), 1e-6);
		assertTrue(map.neighbors(a).contains(b), "the two provinces must be adjacent");
		assertEquals(List.of(a, b), map.path(a, b),
				"a direct neighbour is a one-step path");
	}
}
