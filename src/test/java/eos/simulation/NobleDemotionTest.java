package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.Household;
import eos.agent.laborer.Laborer;
import eos.agent.noble.Noble;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * Phase 3 of the rank ladder (see {@code docs/rank-ladder.md}): the reverse of
 * ennoblement. A {@link Noble} ({@link eos.agent.Rank#HOLDING}) demoted down the
 * ladder is reformed into a copper-banking {@link Laborer}
 * ({@link eos.agent.Rank#HOUSEHOLD}) — skipping the unrealized {@code RETINUE}
 * rung — adopting the same head and carrying its balances over, so the colony's
 * money is conserved across the re-bank.
 * <p>
 * Demotion has no automatic trigger yet, so this test raises a noble through the
 * normal ennoblement path (a strategic sector with no initial nobles, so a
 * chartered firm's want of an owner ennobles the ablest laborer) and then calls
 * {@link SimulationHarness#demote} on it directly.
 */
class NobleDemotionTest {

	@Test
	void aDemotedNobleReBanksInCopperWithMoneyConserved() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		// a strategic export sector with no initial nobles, so the first chartered
		// firm ennobles the ablest laborer (silver-banking) to own it
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, eos.agent.firm.StrategicFirmConfig.DEFAULT);
		h.primeNobleLabor();
		h.createDefaultRuler();
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> copper, i -> 15);

		// run until a noble has been raised by ennoblement
		colony.run(150);
		Noble noble = firstNoble(colony);
		assertNotNull(noble, "a laborer should have been ennobled to set up the test");
		assertEquals(CurrencyType.SILVER, noble.getBank().getCurrency(),
				"the raised noble banks in silver");

		// snapshot the noble's (copper-denominated) balances before demotion
		Bank silver = noble.getBank();
		double checkingBefore = silver.getChecking(noble.getID());
		double savingsBefore = silver.getSavings(noble.getID());

		// demote it: HOLDING -> HOUSEHOLD (skipping the unrealized RETINUE rung)
		Household reformed = h.demote(noble);

		assertNotNull(reformed, "demoting a HOLDING noble should reform it to HOUSEHOLD");
		assertInstanceOf(Laborer.class, reformed,
				"a demoted noble becomes a laborer");
		assertNotSame(noble, reformed, "the reform produces a new household agent");
		Laborer laborer = (Laborer) reformed;

		// it re-banks in copper, the head carried across unchanged
		assertEquals(CurrencyType.COPPER, laborer.getBank().getCurrency(),
				"a demoted noble re-banks in copper");
		assertEquals(noble.getHead(), laborer.getHead(),
				"the head (and so the dynasty) is carried across the demotion");

		// money conserved: the carried balances reopen the copper account unchanged
		assertEquals(checkingBefore, copper.getChecking(laborer.getID()), 1e-9,
				"checking balance carried over unchanged (money conserved)");
		assertEquals(savingsBefore, copper.getSavings(laborer.getID()), 1e-9,
				"savings balance carried over unchanged (money conserved)");
	}

	private static Noble firstNoble(Settlement colony) {
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive())
				return n;
		return null;
	}
}
