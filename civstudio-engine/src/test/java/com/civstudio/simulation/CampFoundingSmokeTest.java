package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the <b>found-at-Camp</b> lifecycle (docs/settlement-tier-ladder-plan.md Phase D/G):
 * {@link CampFoundingEconomy} founds a small caravan band as a {@link
 * com.civstudio.settlement.SettlementTier#CAMP camp}, forages, climbs the tier ladder, and boots the
 * full ruler economy at {@link com.civstudio.settlement.SettlementTier#SMALLHOLDING}.
 * <p>
 * Since the subsistence-floor fix (the sole food farm no longer collapses under a demand-deficient
 * boot transient — see {@code CampBootViabilityTest}), the booted small colony is <b>viable</b>: it
 * survives the full run, feeding itself and regenerating through births rather than starving out
 * within a month. So this asserts the durable outcome — the run completes under {@code -ea}, the
 * ruler economy booted (its gold + silver banks appear alongside the camp's copper bank), and the
 * colony is <b>still alive</b> at the end.
 */
@Tag("full-run")
class CampFoundingSmokeTest {

	@Test
	void foundsAtCampClimbsBootsAndSurvives() {
		SimulationHarness h = assertDoesNotThrow(CampFoundingEconomy::run);
		// 3 banks: the camp's copper bank, plus the gold (ruler) and silver (ennobled nobles) banks
		// minted when the camp booted its ruler economy at SMALLHOLDING
		assertEquals(3, h.getBanks().size(), "the boot minted the ruler (gold) and noble (silver) banks");
		assertNotNull(h.getColony().getRuler(), "the ruler economy booted (a Captain-led camp gained a ruler)");
		// the fix: the small booted colony is viable — it survives the whole run instead of collapsing
		assertTrue(h.getColony().isAlive(),
				"the booted small colony survives the run (subsistence floor keeps it fed; it regenerates via births)");
	}
}
