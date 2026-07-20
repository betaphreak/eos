package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.CFirm;
import com.civstudio.agent.firm.EFirm;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;
import com.civstudio.tech.Sector;
import com.civstudio.tech.TechEffect;

/**
 * Phase 2 runtime check: a {@link TechEffect.SectorProductivity} applied to a colony
 * raises exactly its sector's firms' output (via the firms' effective-A production),
 * cumulatively, leaving other sectors untouched; and the colony records {@link
 * TechEffect.Unlock}/{@link TechEffect.SocialGate} tokens. Builds the firms through
 * {@link SimulationHarness} but does <b>not</b> run the colony — the effect is applied
 * directly, exactly the seam research will drive in Phase 3.
 */
class TechProductivityTest {

	// build a default colony's firms (markets + firms) without running it
	private static SimulationHarness firms() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 13579);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> h.getColony().getEconomy().eFirm().savings(),
				i -> h.getColony().getEconomy().nFirm().savings());
		return h;
	}

	private static <T> T first(Settlement colony, Class<T> type) {
		for (Agent a : colony.getAgents())
			if (type.isInstance(a))
				return type.cast(a);
		return null;
	}

	@Test
	void sectorProductivityScalesThatSectorsOutput() {
		SimulationHarness h = firms();
		Settlement colony = h.getColony();
		NFirm nf = first(colony, NFirm.class);
		EFirm ef = first(colony, EFirm.class);
		assertNotNull(nf);
		assertNotNull(ef);

		double L = 10, K = 5;
		// default: no tech applied, multiplier 1.0
		assertEquals(1.0, colony.getTechMultiplier(Sector.NECESSITY));
		double nBefore = nf.convertToProduct(L, K);
		double eBefore = ef.convertToProduct(L, K);

		colony.applyTechEffect(
				new TechEffect.SectorProductivity(Sector.NECESSITY, 1.5));

		// necessity output scales by exactly 1.5; enjoyment (other sector) is untouched
		assertEquals(1.5, colony.getTechMultiplier(Sector.NECESSITY), 1e-12);
		assertEquals(1.5 * nBefore, nf.convertToProduct(L, K), 1e-9);
		assertEquals(eBefore, ef.convertToProduct(L, K), 1e-12);
	}

	@Test
	void sectorProductivityIsCumulative() {
		SimulationHarness h = firms();
		Settlement colony = h.getColony();
		CFirm cf = first(colony, CFirm.class);
		assertNotNull(cf);

		double before = cf.convertToProduct(8);
		colony.applyTechEffect(
				new TechEffect.SectorProductivity(Sector.CAPITAL, 1.5));
		colony.applyTechEffect(
				new TechEffect.SectorProductivity(Sector.CAPITAL, 2.0));
		// 1.5 * 2.0 = 3.0 cumulative
		assertEquals(3.0, colony.getTechMultiplier(Sector.CAPITAL), 1e-12);
		assertEquals(3.0 * before, cf.convertToProduct(8), 1e-6);
	}

	@Test
	void unlockAndGateEffectsRecordTokens() {
		SimulationHarness h = firms();
		Settlement colony = h.getColony();
		assertTrue(colony.getGrantedTechTokens().isEmpty());

		colony.applyTechEffect(new TechEffect.Unlock("GOOD_PAPER"));
		colony.applyTechEffect(new TechEffect.SocialGate("CLASS_BURGHER"));

		assertTrue(colony.getGrantedTechTokens().contains("GOOD_PAPER"));
		assertTrue(colony.getGrantedTechTokens().contains("CLASS_BURGHER"));
		// these are inert today (no productivity change)
		assertEquals(1.0, colony.getTechMultiplier(Sector.CAPITAL));
	}
}
