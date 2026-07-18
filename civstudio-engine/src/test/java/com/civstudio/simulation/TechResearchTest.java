package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.bank.Bank;
import com.civstudio.tech.ResearchState;

/**
 * Phase 3 end-to-end check: a standard ruler+export colony actually researches — its
 * strategic sector's intellectual labor accrues research points, the ruler picks a
 * monthly focus, and at least one tech completes before the colony collapses. As those
 * techs complete, their building-unlock {@link com.civstudio.tech.TechEffect.Unlock}
 * effects (the generated {@code /building-unlocks.json} overlay, merged in by
 * {@code TechTree.load()}) are applied to the colony, granting {@code BUILDING_*} and
 * {@code UNIT_*} tokens — so this also checks the research&rarr;token seam end to end,
 * across both merged overlays. (The unlock tokens are read by nothing yet, so the economy
 * is still unchanged; per-effect application is covered by {@code eos.tech.ResearchStateTest}.)
 */
class TechResearchTest {

	@Test
	void aStandardColonyCompletesATechBeforeCollapse() {
		var colony = runStandard().getColony();
		ResearchState research = colony.getResearch();
		assertNotNull(research, "a standard ruler+export colony has research enabled");
		assertTrue(research.getCompletedCount() >= 1,
				"expected at least one tech completed before collapse, got "
						+ research.getCompletedCount());
		// known set grew past the pre-known Classical-complete baseline (229 techs)
		assertTrue(research.getKnownCount() > 229,
				"known techs should grow as research completes");
		// research→token seam: completing those techs granted their UNLOCK tokens (the
		// Prehistoric→Renaissance frontier a Classical-complete colony researches unlocks
		// buildings AND units — docs/c2c-building-import.md, docs/c2c-unit-import.md), each a
		// BUILDING_* or UNIT_* id.
		Set<String> tokens = colony.getGrantedTechTokens();
		assertFalse(tokens.isEmpty(),
				"completing techs should grant their unlock tokens");
		assertTrue(tokens.stream().allMatch(t -> t.startsWith("BUILDING_") || t.startsWith("UNIT_")),
				"every granted token is a BUILDING_* or UNIT_* unlock, got " + tokens);
		// both overlays merged in: buildings and units each land at least one token
		assertTrue(tokens.stream().anyMatch(t -> t.startsWith("BUILDING_")),
				"expected building unlock tokens, got " + tokens);
		assertTrue(tokens.stream().anyMatch(t -> t.startsWith("UNIT_")),
				"expected unit unlock tokens (the merged unit-unlocks overlay), got " + tokens);
	}

	/** Build and run a standard HomogeneousEconomy-style colony with no printers. */
	private static SimulationHarness runStandard() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 24680);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank, i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.run();
		return h;
	}
}
