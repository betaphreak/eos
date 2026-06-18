package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Caravan;
import eos.agent.Household;
import eos.agent.Member;
import eos.agent.Retinue;
import eos.bank.Bank;
import eos.settlement.Settlement;

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

		Caravan band = Caravan.dissolve(colony);

		// money conserved: the whole stock is now the band's hoard, the banks drained
		assertEquals(moneyBefore, band.getHoard(), 1e-6,
				"the hoard conserves the colony's circulating money");
		assertEquals(0,
				colony.getBanks().stream().mapToDouble(Bank::getTotalMoney).sum(),
				1e-6, "the banks are drained into the hoard");

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
