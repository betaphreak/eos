package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.noble.Noble;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * The <b>village firms</b> (city-of-hamlets V3, {@code docs/city-of-hamlets-plan.md}): each necessity
 * farm belongs to a hamlet — owned by that village's leader, hiring its own residents first, and
 * filling its village's {@link Larder} before offering the surplus on the shared market. These cover
 * the three consequences of belonging to a village (assignment + ownership, larder-first delivery with
 * a live surplus market, labor affinity) and that a colony running on them survives.
 */
class VillageFirmTest {

	// the VillageLarderTest manual assembly — a settled colony that grows landed households, farms and
	// a noble fief once run, so colony.hamlets() and its necessity farms are both non-empty
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

	private static SimulationConfig villageFirmCfg() { // the default, made explicit
		return SimulationConfig.DEFAULT.toBuilder().villageLarder(true).villageFirms(true).build();
	}

	private static List<NFirm> farmsOf(Settlement colony) {
		List<NFirm> farms = new ArrayList<>();
		for (Agent a : colony.getAgents())
			if (a.isAlive() && a instanceof NFirm f)
				farms.add(f);
		return farms;
	}

	@Test
	void villageFirmsAreOnByDefaultAndOptOutCleanly() {
		assertTrue(SimulationConfig.DEFAULT.villageFirms(),
				"the flag is ON by default (the 2026-07-24 flip)");
		assertNotNull(foundColony(SimulationConfig.DEFAULT).getVillageFirms(),
				"a default colony farms through its villages");
		// a run can still opt OUT — its farms stay city farms, selling their whole output on the
		// shared market and drawing from the whole workforce
		assertNull(foundColony(SimulationConfig.DEFAULT.toBuilder().villageFirms(false).build())
				.getVillageFirms(), "a flag-off colony builds no village-firm subsystem");
		// V3 rides the larders — a farm delivers into one — so a run that drops the larders is not
		// left half-wired: the village firms go with them
		assertNull(foundColony(SimulationConfig.DEFAULT.toBuilder()
				.villageLarder(false).villageFirms(true).build()).getVillageFirms(),
				"village firms need the village larders");
	}

	@Test
	void aCityFarmKeepsNoVillageAndSellsEverything() {
		Settlement colony = foundColony(
				SimulationConfig.DEFAULT.toBuilder().villageFirms(false).build());
		colony.run(200);
		for (NFirm farm : farmsOf(colony)) {
			assertNull(farm.getVillage(), "a flag-off colony's farms belong to no village");
			assertNull(farm.laborAffinity(), "so they claim no labor affinity either");
		}
	}

	@Test
	void everyFarmBelongsToARealVillage() {
		Settlement colony = foundColony(villageFirmCfg());
		colony.run(365);
		List<Hamlet> hamlets = colony.hamlets();
		assertFalse(hamlets.isEmpty(), "the colony has villages to farm for");
		List<NFirm> farms = farmsOf(colony);
		assertFalse(farms.isEmpty(), "the colony has necessity farms");

		Map<Plot, Hamlet> bySeat = new IdentityHashMap<>();
		for (Hamlet ham : hamlets)
			bySeat.put(ham.seat(), ham);
		for (NFirm farm : farms) {
			Plot village = farm.getVillage();
			assertNotNull(village, "every farm belongs to a village once there are villages");
			assertNotNull(bySeat.get(village), "and the village it belongs to is a real hamlet");
			assertEquals(village, farm.laborAffinity(), "its labor affinity IS its village");
		}
	}

	// ownership follows the FIEF: grant a village to a noble and its farm moves into that noble's
	// hands on the next day's pass — the lord's fields, not the crown's, once the crown has granted
	// them away. Targeted rather than statistical: only a handful of the colony's many villages are
	// enfeoffed at any time, so this grants the one that matters and watches the holding move.
	@Test
	void grantingAVillageMovesItsFarmToItsNewLord() {
		Settlement colony = foundColony(villageFirmCfg());
		colony.run(365);
		NFirm farm = farmsOf(colony).get(0);
		Plot village = farm.getVillage();
		assertNotNull(village, "the farm belongs to a village");

		Noble lord = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive())
				lord = n;
		assertNotNull(lord, "the colony has raised an aristocracy to grant to");
		assertFalse(lord.owns(farm), "the lord does not hold the farm before the grant");

		colony.grantFief(village, lord);
		colony.run(1);
		assertTrue(lord.owns(farm),
				"the village's farm passes to the noble the village was granted to");
	}

	// the local sale that makes a village self-feeding: the farm's produce goes into its OWN village's
	// larder, bought by the village's leader at the going market price — food moves one way, money the
	// other, so the farm keeps the revenue that pays its workers.
	@Test
	void aVillageFarmSellsItsProduceIntoItsOwnLarder() {
		Settlement colony = foundColony(villageFirmCfg());
		colony.run(365);
		NFirm farm = farmsOf(colony).get(0);
		Plot village = farm.getVillage();
		Larder larder = colony.getVillageLarders().larderIfPresent(village);
		assertNotNull(larder, "the village has a larder");

		// enfeoff the village so it has a solvent lord to provision it. (A Crown-demesne village is
		// provisioned by the treasury, which on a young colony is deep in debt — a broke lord buys
		// nothing, by design: the purse cap is what stops the provisioning duty borrowing without
		// bound. That is the same rule, exercised at its other end.)
		Noble lord = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive())
				lord = n;
		assertNotNull(lord, "the colony has an aristocracy");
		colony.grantFief(village, lord);

		// drain the larder below its floor so the village actually wants food (a village already at
		// its floor buys nothing — it exports instead, which is the other half of the rule)
		larder.good().decrease(larder.available());
		double lordBefore = lord.getBank().getAcct(lord.getID()).getChecking();
		double farmBefore = farm.getBank().getAcct(farm.getID()).getChecking();

		double moved = colony.stockVillageLarder(farm, village, 1000);
		assertTrue(moved > 0, "the empty village takes food off its own farm");
		assertEquals(moved, larder.available(), 1e-9, "and it lands in that village's larder");
		assertTrue(farm.getBank().getAcct(farm.getID()).getChecking() > farmBefore,
				"the farm is paid for it — a sale, not a levy");
		assertTrue(lord.getBank().getAcct(lord.getID()).getChecking() < lordBefore,
				"and the village's leader is the one who paid");

		// the larder takes only up to its floor: a full village exports the rest to the shared market
		assertEquals(0, colony.stockVillageLarder(farm, village, 1000), 1e-9,
				"a village at its floor takes no more — the surplus goes to market");
	}

	@Test
	void theSurplusStillReachesTheSharedMarket() {
		Settlement colony = foundColony(villageFirmCfg());
		colony.run(365);
		// a village fills its larder to the floor and sells the rest, so food price discovery keeps
		// running on real volume rather than seizing up behind the village walls. Measured over a
		// month, not on one day: the farms are closed on rest days and a village that happens to be
		// short absorbs a whole day's output, so a single day's supply is noise.
		ConsumerGoodMarket necessity = (ConsumerGoodMarket) colony.getMarket("Necessity");
		double supply = 0;
		for (int day = 0; day < 30; day++) {
			colony.run(1);
			supply += necessity.getLastMktSupply();
		}
		assertTrue(supply > 0, "the villages export their surplus to the shared market");
		assertTrue(necessity.getLastMktPrice() > 0, "so the food price is still discovered");
	}

	// THE survival test: V3 moves the whole food sector under the villages (production into the
	// larders, ownership to the lords, hiring to the residents). A colony running on it must still
	// feed itself over a multi-year run.
	@Test
	void aVillageFarmedColonySurvives() {
		Settlement colony = foundColony(villageFirmCfg());
		colony.run(3 * 365);

		assertTrue(colony.isAlive(), "the village-farmed colony survives a multi-year run");
		assertFalse(colony.hamlets().isEmpty(), "it still holds peopled villages");
		assertFalse(farmsOf(colony).isEmpty(), "and it still holds farms working for them");
	}
}
