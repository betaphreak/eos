package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * When the dynamic firm provisioning charters a firm in a ruler-bearing colony
 * that has <b>no noble</b>, it ennobles the ablest laborer (highest head
 * SOCIAL, the youngest breaking a tie) to own it — re-banking that household
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

	/**
	 * When the colony <em>does</em> have a strategic export firm (but still no
	 * initial nobles), the laborer ennobled to own a chartered firm also <b>works
	 * the strategic firm</b> — it joins the noble-only labor market like any noble
	 * and earns an export wage on top of its dividends.
	 */
	@Test
	void anEnnobledNobleAlsoWorksTheStrategicFirmWhenOneExists() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		// a strategic export sector — the noble-only labor market and the export firm
		// — but deliberately NO initial nobles, so a chartered firm has no owner and
		// the ablest laborer is ennobled to both own it and staff the export firm
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		h.primeNobleLabor();
		h.createDefaultRuler();
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> copper, i -> 15);

		assertEquals(0, countNobles(colony), "colony should start with no nobles");

		colony.run(150);

		Noble raised = firstNoble(colony);
		assertNotNull(raised, "a laborer should have been ennobled");
		assertEquals(CurrencyType.SILVER, raised.getBank().getCurrency(),
				"an ennobled household re-banks in silver");
		assertTrue(raised.getFirmCount() >= 1, "the new noble owns its chartered firm");
		// the payoff: it also works the export sector, so it draws a wage from the
		// strategic firm (it never starves regardless, but here it has export income)
		assertTrue(raised.getWage() > 0,
				"the ennobled noble should earn an export wage from the strategic firm, got "
						+ raised.getWage());
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
