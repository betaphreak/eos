package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * The <b>village-larder</b> subsystem foundation (city-of-hamlets V2 slice 1, {@code
 * docs/city-of-hamlets-plan.md}): the flag-gated per-hamlet {@link Larder} pools. This slice lands the
 * pools and their lifecycle only — nothing eats from them yet, so a colony is unchanged whether the
 * flag is on or off; the assertions here are the plumbing (pool semantics, flag-gating, one larder per
 * hamlet seat). The provisioned eating + leader-funded imports + survival ride later slices.
 */
class VillageLarderTest {

	// the FiefTest/HamletTest manual assembly — a settled colony that grows landed households + a
	// noble fief once run, so colony.hamlets() is non-empty
	private static Settlement foundColony(SimulationConfig cfg) {
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
		return colony;
	}

	@Test
	void larderStocksAndReportsAsAPool() {
		Larder l = new Larder();
		assertEquals(0, l.available());
		l.stock(10);
		assertEquals(10, l.available(), 1e-9);
		// the eating path touches the pool through good() — increase() is the home-plot drop, decrease()
		// the ration draw — and available() reflects it
		l.good().decrease(4);
		assertEquals(6, l.available(), 1e-9, "available() reflects the pool the households eat from");
		l.stock(-5); // a non-positive stock is a no-op (never goes negative)
		assertEquals(6, l.available(), 1e-9);
	}

	@Test
	void villageLarderIsOffByDefaultAndByteIdentical() {
		assertFalse(SimulationConfig.DEFAULT.villageLarder(), "the flag is OFF by default");
		Settlement colony = foundColony(SimulationConfig.DEFAULT);
		assertNull(colony.getVillageLarders(),
				"a flag-off colony builds no village larders — food keeps its per-household path");
	}

	@Test
	void enablingBuildsAPerHamletLarderSubsystem() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().villageLarder(true).build();
		Settlement colony = foundColony(cfg);
		VillageLarders larders = colony.getVillageLarders();
		assertNotNull(larders, "the flag-on colony has the village-larder subsystem");

		colony.run(150); // grow households + a fief so the colony has hamlets to larder
		assertFalse(colony.hamlets().isEmpty(), "the colony has hamlets to larder");

		for (Hamlet ham : colony.hamlets()) {
			Larder larder = larders.larderFor(ham.seat());
			assertNotNull(larder, "a larder is created for each hamlet seat");
			assertSame(larder, larders.larderFor(ham.seat()), "one larder per hamlet seat (reused)");
			assertSame(larder, larders.larderIfPresent(ham.seat()), "the created larder is the present one");
			// the larder now carries real food (pre-stock + provisioning + eating, slice 2b), so assert
			// the SAME pool is served on every lookup by a delta rather than an absolute
			double before = larder.available();
			larder.stock(5);
			assertEquals(before + 5, larders.larderIfPresent(ham.seat()).available(), 1e-9,
					"the same pool is served on every lookup");
		}
	}

	// THE survival test (slice 2b): the provisioned floor must actually feed the city. A flag-on
	// colony — where peasants eat from their village larder (not their own market purchases) and the
	// leaders import the deficit — must survive a multi-year run with its villages fed, not starve
	// itself the day food stopped being individually bought.
	@Test
	void aProvisionedColonySurvivesAndFeedsItsVillages() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().villageLarder(true).build();
		Settlement colony = foundColony(cfg);
		colony.run(3 * 365); // three years

		assertTrue(colony.isAlive(), "the provisioned colony survives a multi-year run");
		assertFalse(colony.hamlets().isEmpty(), "it still holds peopled villages");

		// the villages' larders hold food — the provisioned floor is fed, not empty
		VillageLarders larders = colony.getVillageLarders();
		double totalFood = 0;
		for (Hamlet ham : colony.hamlets()) {
			Larder l = larders.larderIfPresent(ham.seat());
			if (l != null)
				totalFood += l.available();
		}
		assertTrue(totalFood > 0, "the villages' larders hold food after three years");
	}
}
