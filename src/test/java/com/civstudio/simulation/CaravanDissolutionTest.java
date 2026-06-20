package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Caravan;
import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.settlement.Settlement;

/**
 * The {@code HOLDING → CARAVAN} <b>dissolution</b> ({@code docs/caravan.md}): a settled
 * colony crosses the hinge into a wandering band. This covers the operation in
 * isolation (not its trigger, which is the later collapse-as-decline wiring): money is
 * conserved into the band's hoard, the surviving households collapse into the following,
 * and the sovereign leads the band out as its Captain.
 */
class CaravanDissolutionTest {

	@Test
	void dissolvingAColonyConservesMoneyAndFoldsHouseholdsIntoTheBand() {
		// a standard colony on a short, pre-collapse horizon
		SimulationConfig cfg =
				SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		h.run();

		Settlement colony = h.getColony();
		Retinue retinue = h.getRetinue();
		assertNotNull(retinue, "a standard colony has a pool to become the following");

		// snapshot the circulating money and the band's makeup before dissolution
		double moneyBefore =
				colony.getBanks().stream().mapToDouble(Bank::getTotalMoney).sum();
		assertTrue(moneyBefore > 0, "a live colony holds money");
		int followersBefore = retinue.size();
		Member rulerHead = colony.getRuler().getHead();
		long householdMembers = colony.getAgents().stream()
				.filter(a -> a instanceof Household && a.isAlive())
				.mapToLong(a -> ((Household) a).getMemberCount()).sum();

		// total necessity in the colony before dissolution: the pool's larder, plus
		// every household's larder, plus every necessity firm's unsold stock — all of
		// which should fold into the band's larder (the food travels with the band)
		double foodBefore = retinue.getLarder();
		for (Agent a : colony.getAgents()) {
			if (!a.isAlive())
				continue;
			if (a instanceof Household) {
				Good food = a.getGood("Necessity");
				if (food != null)
					foodBefore += food.getQuantity();
			} else if (a instanceof NFirm f) {
				foodBefore += f.getStock();
			}
		}

		Caravan band = Caravan.dissolve(colony);

		// money conserved: the whole stock is now the band's hoard, the banks drained
		assertEquals(moneyBefore, band.getHoard(), 1e-6,
				"the hoard conserves the colony's circulating money");
		assertEquals(0,
				colony.getBanks().stream().mapToDouble(Bank::getTotalMoney).sum(),
				1e-6, "the banks are drained into the hoard");

		// food conserved: the household larders and the abandoned necessity firms' stock
		// fold into the band's carried larder (nothing is lost)
		assertEquals(foodBefore, band.getFollowing().getLarder(), 1e-6,
				"the colony's food is conserved into the band's larder");

		// the sovereign leads the band; every other household member joins the following
		assertSame(rulerHead, band.getLeader(), "the ruler leads the band as its Captain");
		assertSame(retinue, band.getFollowing(),
				"the band's following is the colony's own retinue");
		assertTrue(band.getFollowing().isWandering(),
				"a dissolved band's following wanders");
		assertEquals(followersBefore + householdMembers - 1, band.getFollowing().size(),
				"every household member but the leader disbands into the following");
	}
}
