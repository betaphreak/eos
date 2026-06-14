package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * When the dynamic firm provisioning charters a firm in a ruler-bearing colony
 * that has <b>no noble</b>, it ennobles the ablest laborer (highest head
 * INTELLECTUAL, the youngest breaking a tie) to own it — re-banking that household
 * in silver.
 * <p>
 * No bundled simulation reaches this path (each carries the default export sector,
 * whose nobles always exist), so this test builds a colony with a ruler and a
 * peasant pool but <em>deliberately no strategic sector</em> (hence no nobles),
 * runs it until the first monthly review charters a firm, and asserts a
 * silver-banking noble emerged owning that firm.
 */
class LaborerEnnoblementTest {

	@Test
	void aLaborerIsEnnobledToOwnAFirmWhenNoNobleExists() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		// no strategic sector, so no nobles — createDefaultRuler still installs the
		// dynamic provisioning factory
		h.createDefaultRuler();
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> copper, i -> 15);

		assertEquals(0, countNobles(colony), "colony should start with no nobles");
		assertTrue(countLaborers(colony) > 0, "colony should start with laborers");

		// run past the first day-of-month review (1445-01-01), which charters a firm
		// into a noble-less colony and so ennobles a laborer at end of step
		colony.run(120);

		Noble raised = firstNoble(colony);
		assertNotNull(raised,
				"a laborer should have been ennobled to own a chartered firm");
		assertTrue(raised.isAlive(), "the ennobled noble is alive");
		assertEquals(CurrencyType.SILVER, raised.getBank().getCurrency(),
				"an ennobled household re-banks in silver");
		assertTrue(raised.getFirmCount() >= 1,
				"the new noble owns the firm it was raised to own");
	}

	private static long countNobles(Settlement colony) {
		return colony.getAgents().stream().filter(a -> a instanceof Noble).count();
	}

	private static long countLaborers(Settlement colony) {
		return colony.getAgents().stream()
				.filter(a -> a instanceof eos.agent.laborer.Laborer).count();
	}

	private static Noble firstNoble(Settlement colony) {
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n)
				return n;
		return null;
	}
}
