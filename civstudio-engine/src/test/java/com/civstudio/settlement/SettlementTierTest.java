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
	void nextAndPreviousWalkTheLadderAndAreEmptyAtTheEnds() {
		assertEquals(SettlementTier.COTTAGE, SettlementTier.CAMP.next().orElseThrow());
		assertEquals(SettlementTier.METROPOLIS, SettlementTier.TOWN.next().orElseThrow());
		assertTrue(SettlementTier.METROPOLIS.next().isEmpty(), "METROPOLIS is the top rung");
		assertEquals(SettlementTier.TOWN, SettlementTier.METROPOLIS.previous().orElseThrow());
		assertTrue(SettlementTier.CAMP.previous().isEmpty(), "CAMP is the foot");
	}

	@Test
	void sizeIsOneBasedAndHouseholdFloorIsSizeSquared() {
		assertEquals(1, SettlementTier.CAMP.size());
		assertEquals(6, SettlementTier.METROPOLIS.size());
		assertEquals(1, SettlementTier.CAMP.minHouseholds());
		assertEquals(4, SettlementTier.COTTAGE.minHouseholds());
		assertEquals(9, SettlementTier.HAMLET.minHouseholds());
		assertEquals(16, SettlementTier.SMALLHOLDING.minHouseholds());
		assertEquals(25, SettlementTier.TOWN.minHouseholds());
		assertEquals(36, SettlementTier.METROPOLIS.minHouseholds());
	}

	@Test
	void foodToChangeFollowsTheC2cThresholdCurve() {
		// C2C CvPlayer::getGrowthThreshold: BASE 130 + (size-1) * 25
		assertEquals(130, SettlementTier.CAMP.foodToChange());
		assertEquals(155, SettlementTier.COTTAGE.foodToChange());
		assertEquals(180, SettlementTier.HAMLET.foodToChange());
		assertEquals(205, SettlementTier.SMALLHOLDING.foodToChange());
		assertEquals(230, SettlementTier.TOWN.foodToChange());
		assertEquals(255, SettlementTier.METROPOLIS.foodToChange());
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
