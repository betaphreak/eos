package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link SettlementTier} ladder — its declared order and the {@link
 * SettlementTier#atLeast(SettlementTier)} comparison the capability tests key off.
 */
class SettlementTierTest {

	@Test
	void rungsAreOrderedCampToMetropolis() {
		assertEquals(
				java.util.List.of(SettlementTier.CAMP, SettlementTier.COTTAGE,
						SettlementTier.HAMLET, SettlementTier.SMALLHOLDING,
						SettlementTier.TOWN, SettlementTier.METROPOLIS),
				java.util.List.of(SettlementTier.values()),
				"the ladder runs CAMP < COTTAGE < HAMLET < SMALLHOLDING < TOWN < METROPOLIS");
	}

	@Test
	void suburbsIsNotALinearRung() {
		for (SettlementTier t : SettlementTier.values())
			assertFalse(t.name().equals("SUBURBS"),
					"SUBURBS is a future province-merge op, not an ordered rung");
	}

	@Test
	void atLeastIsReflexive() {
		for (SettlementTier t : SettlementTier.values())
			assertTrue(t.atLeast(t), t + " is at least itself");
	}

	@Test
	void atLeastFollowsTheOrder() {
		assertTrue(SettlementTier.METROPOLIS.atLeast(SettlementTier.TOWN));
		assertTrue(SettlementTier.TOWN.atLeast(SettlementTier.SMALLHOLDING));
		assertFalse(SettlementTier.SMALLHOLDING.atLeast(SettlementTier.TOWN));
		assertFalse(SettlementTier.CAMP.atLeast(SettlementTier.COTTAGE));
	}

	@Test
	void nextWalksUpAndEmptyAtTheTop() {
		assertEquals(SettlementTier.COTTAGE, SettlementTier.CAMP.next().orElseThrow());
		assertEquals(SettlementTier.METROPOLIS, SettlementTier.TOWN.next().orElseThrow());
		assertTrue(SettlementTier.METROPOLIS.next().isEmpty(), "METROPOLIS is the top rung");
	}

	@Test
	void upgradeDaysFollowTheC2cChainAndMetropolisIsTerminal() {
		assertEquals(10, SettlementTier.CAMP.upgradeDays());
		assertEquals(10, SettlementTier.COTTAGE.upgradeDays());
		assertEquals(20, SettlementTier.HAMLET.upgradeDays());
		assertEquals(30, SettlementTier.SMALLHOLDING.upgradeDays());
		assertEquals(40, SettlementTier.TOWN.upgradeDays());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				SettlementTier.METROPOLIS::upgradeDays, "the terminal rung has no upgrade cost");
	}

	@Test
	void districtsAndPermanenceBeginAtTown() {
		// the thresholds the flattened Settlement derives its capabilities from
		assertTrue(SettlementTier.TOWN.atLeast(SettlementTier.TOWN), "a Town has districts");
		assertTrue(SettlementTier.METROPOLIS.atLeast(SettlementTier.TOWN),
				"a Metropolis has districts");
		assertFalse(SettlementTier.SMALLHOLDING.atLeast(SettlementTier.TOWN),
				"a Smallholding has only its centre");
	}
}
