package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.noble.Noble;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * The <b>hamlet</b> grouping (city-of-hamlets V0, {@code docs/city-of-hamlets-plan.md}): {@link
 * Settlement#hamlets()} projects one {@link Hamlet} per plot with resident households, led by the
 * plot's fief-holder (or the Crown), named for the plot, and tier-rated by its household count —
 * excluding the city center. A pure read-only grouping over the shipped vassalage state, so it must
 * simply agree with the plots and households behind it, changing nothing.
 */
class HamletTest {

	// the tier a hamlet of N households sits at — CAMP 1..3, COTTAGE 4..8, HAMLET 9+ (capped)
	@Test
	void tierIsDerivedFromHouseholdCountAndCappedAtHamlet() {
		assertEquals(SettlementTier.CAMP, Hamlet.tierFor(1));
		assertEquals(SettlementTier.CAMP, Hamlet.tierFor(3));
		assertEquals(SettlementTier.COTTAGE, Hamlet.tierFor(4));
		assertEquals(SettlementTier.COTTAGE, Hamlet.tierFor(8));
		assertEquals(SettlementTier.HAMLET, Hamlet.tierFor(9));
		// capped: a dependent cell never climbs into the self-governing SMALLHOLDING band, however
		// many households it holds
		assertEquals(SettlementTier.HAMLET, Hamlet.tierFor(50));
	}

	// a colony with landed households and an ennobled fief-holder (the FiefTest setup) — then the
	// grouping must agree with the plots and households behind it
	@Test
	void hamletsGroupHouseholdsByHomePlotUnderTheirFiefLord() {
		SimulationConfig cfg = SimulationConfig.DEFAULT; // homePlots + buildEconomy on
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		h.createNobleLaborMarket();
		Era.Economy econ = colony.getEconomy();
		h.createFirms(copper, i -> copper, i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		h.primeNobleLabor();
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);
		colony.run(150); // past the aristocracy top-up, so a laborer is ennobled onto a fief

		List<Hamlet> hamlets = colony.hamlets();
		assertFalse(hamlets.isEmpty(), "a settled colony with landed households has hamlets");

		Plot center = colony.getDistrictPlots().get(0);
		int grouped = 0;
		for (Hamlet ham : hamlets) {
			assertFalse(ham.households().isEmpty(), "a hamlet is never empty (only plots with residents)");
			assertNotSame(center, ham.seat(), "the city center is the civic core, never a hamlet");
			assertEquals(ham.seat().placeName(), ham.name(), "a hamlet is named for its seat plot");
			assertEquals(ham.seat().ownerId(), ham.leaderId(), "a hamlet's leader is its seat's fief-holder");
			assertFalse(ham.tier().atLeast(SettlementTier.SMALLHOLDING),
					"a hamlet's tier is capped at HAMLET (never into the self-governing band)");
			for (Laborer l : ham.households())
				assertSame(ham.seat(), l.getHomePlot(),
						"every household in a hamlet lives on that hamlet's seat plot");
			grouped += ham.households().size();
		}

		// every landed household off the center is in exactly one hamlet (the grouping partitions them)
		long landedOffCenter = colony.getAgents().stream()
				.filter(a -> a.isAlive() && a instanceof Laborer)
				.map(a -> ((Laborer) a).getHomePlot())
				.filter(p -> p != null && p != center)
				.count();
		assertEquals(landedOffCenter, grouped,
				"every landed household off the center belongs to exactly one hamlet");
	}

	// at least one hamlet should sit under an ennobled fief-holder (a noble-led hamlet), the P3 fief
	// made a place; the rest are Crown demesne (leaderId null)
	@Test
	void aNobleHeldPlotYieldsANobleLedHamlet() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		h.createNobleLaborMarket();
		Era.Economy econ = colony.getEconomy();
		h.createFirms(copper, i -> copper, i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		h.primeNobleLabor();
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);
		colony.run(150);

		Noble noble = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive() && n.getFief() != null) {
				noble = n;
				break;
			}
		assertNotNull(noble, "a laborer was ennobled onto a fief");

		// if that fief has residents it is a noble-led hamlet; either way, no hamlet may name a
		// leader that is not its seat's holder
		boolean anyNobleLed = false;
		for (Hamlet ham : colony.hamlets())
			if (ham.leaderId() != null) {
				anyNobleLed = true;
				assertFalse(ham.crownDemesne(), "a hamlet with a leader id is not Crown demesne");
			} else {
				assertTrue(ham.crownDemesne(), "a hamlet with no leader is Crown demesne");
			}
		// the noble's own fief, if peopled, is a noble-led hamlet
		if (colony.householdsByHomePlot().containsKey(noble.getFief()))
			assertTrue(anyNobleLed, "the peopled noble fief is a noble-led hamlet");
	}
}
