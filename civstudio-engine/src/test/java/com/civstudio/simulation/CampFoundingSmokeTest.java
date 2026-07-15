package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for the <b>found-at-Camp</b> lifecycle (docs/settlement-tier-ladder-plan.md Phase D):
 * {@link CampFoundingEconomy} founds a small caravan band as a {@link
 * com.civstudio.settlement.SettlementTier#CAMP camp}, forages, climbs the tier ladder, and boots
 * the full ruler economy at {@link com.civstudio.settlement.SettlementTier#SMALLHOLDING} — then,
 * once its small pool drains, departs as a wandering band. The whole settle&harr;unsettle cycle
 * runs under the {@code -ea} invariants without tripping one.
 * <p>
 * Note: the booted small colony is short-lived — it collapses quickly, the same food-balance
 * fragility a mature small colony shows (colonies collapse by design; the durable fix is upstream
 * food-economy calibration). The assertion here is on what is <b>robust</b>: the run completes, the
 * ruler economy boots (its gold + silver banks appear alongside the camp's copper bank), and the
 * lifecycle closes by departing as a caravan.
 */
class CampFoundingSmokeTest {

	@Test
	void foundsAtCampClimbsBootsAndDepartsAsCaravan() {
		SimulationHarness h = assertDoesNotThrow(CampFoundingEconomy::run);
		// 3 banks: the camp's copper bank, plus the gold (ruler) and silver (ennobled nobles) banks
		// minted when the camp booted its ruler economy at SMALLHOLDING
		assertEquals(3, h.getBanks().size(), "the boot minted the ruler (gold) and noble (silver) banks");
		// the full found-at-Camp -> climb -> boot -> collapse -> depart cycle completed
		SimulationAssertions.assertDepartedAsCaravan(h);
	}
}
