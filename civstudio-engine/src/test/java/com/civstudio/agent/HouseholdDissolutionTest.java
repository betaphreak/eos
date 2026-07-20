package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * A household is never an empty shell — <b>it dissolves instead of emptying</b>, and the colony
 * deletes it the same step.
 * <p>
 * The invariant is real but <b>implicit</b>: it emerges from four independently-written guards in
 * {@link AbstractHousehold} — the {@code removeIf(m -> m != head && !m.isAlive())} that never drops
 * the head, the {@code size() <= 1} guard in {@code removeNonHeadMember}, the {@code member ==
 * getHead()} guard in {@code removeMember}, and the {@code i = 1} loop start in {@code
 * releaseGrownChild}. When the head dies with survivors one is promoted; when it dies alone the
 * household calls {@code dieAndSettleEstate()} and its <i>dead head is deliberately retained</i> as
 * the identity for the death log, succession and surname recycling.
 * <p>
 * So the colony prunes households on {@link Agent#isAlive()}, <b>never on a member count of zero</b>
 * ({@code Settlement.newDay}'s dead sweep) — a count of zero is unreachable. Nothing pinned that,
 * which is what this test is for: it fails loudly if a future change ever lets a roster empty, or
 * lets a dissolved household linger in the agent set.
 * <p>
 * <b>Not</b> asserted here, deliberately: that everything leaving the agent set is dead. A
 * <i>living</i> household leaves too, legitimately — {@code RankLadder.reformTo} schedules the old
 * household's removal and adds its replacement when a laborer is ennobled into a noble. Leaving
 * while alive is a promotion, not a leak.
 */
class HouseholdDissolutionTest {

	/**
	 * Over a real colony's life — with deaths, births, weddings, ennoblement and successors all
	 * running — <b>no household ever holds zero members</b>, on any step.
	 * <p>
	 * Checked every step rather than at the end: an empty roster would be a transient the final
	 * state could not show, since the dead sweep would have removed the agent by then.
	 */
	@Test
	void noHouseholdEverHoldsZeroMembers() {
		SimulationHarness h = poolColony();
		Settlement colony = h.getColony();
		List<String> offenders = new ArrayList<>();

		// step the colony by hand so the roster can be inspected BETWEEN steps
		colony.start();
		for (int i = 0; i < 365 && colony.isAlive(); i++) {
			colony.newDay();
			for (Agent a : colony.getAgents())
				if (a instanceof AbstractHousehold household && household.getMemberCount() == 0)
					offenders.add(household.getName() + " on " + colony.getDate());
		}

		assertTrue(offenders.isEmpty(),
				"a household must dissolve rather than empty — an empty roster means getHead() "
						+ "throws on the next read. Offenders: " + offenders);
	}

	/**
	 * A household whose people are gone is <b>deleted</b>: once it dissolves it is flagged dead and
	 * the colony's dead sweep drops it from {@code getAgents()} — it is not left to be re-acted on
	 * every remaining day. (A successor of the same dynasty may take its place; that is a <i>new</i>
	 * agent, not the corpse.)
	 */
	@Test
	void noDeadHouseholdEverLingersInTheAgentSet() {
		SimulationHarness h = poolColony();
		Settlement colony = h.getColony();

		// A household dies and is swept in the SAME newDay() — act() flags it and the dead sweep
		// drops it before the step returns — so a caller can never catch a dead-flagged household
		// between steps. That IS the property: after any step, nothing in the agent set is a corpse.
		//
		// NB the converse does NOT hold, so don't assert it: a LIVING household also leaves the set,
		// legitimately, when it is reformed onto another rung — RankLadder.reformTo schedules the old
		// household's removal and adds the new one (a laborer ennobled into a noble). Leaving while
		// alive is a promotion, not a leak.
		colony.start();
		int steps = 0;
		for (int i = 0; i < 365 && colony.isAlive(); i++) {
			colony.newDay();
			steps++;
			for (Agent a : colony.getAgents())
				if (a instanceof AbstractHousehold household) {
					assertTrue(household.isAlive(),
							"a dead household lingered in the agent set — it would be re-acted on "
									+ "every remaining day: " + household.getName() + " on "
									+ colony.getDate());
					assertTrue(household.getMemberCount() > 0,
							"a living household always has its head: " + household.getName());
				}
		}
		assertTrue(steps > 0, "the colony should have run at least one step");
	}

	/** A small pool colony with the survival-regime founding skill (cf. BirthsTest). */
	private static SimulationHarness poolColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).numEFirms(2).numNFirms(10)
				.meanSkillMale(5).meanSkillFemale(2).build();
		SimulationHarness h = assertDoesNotThrow(() -> SimulationHarness.create(cfg, 7654321));
		h.tuneEconomy(e -> e.toBuilder()
				.retinueSize(120).promotionRatio(0.4).build());
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> h.getColony().getEconomy().eFirm().savings(),
				i -> h.getColony().getEconomy().nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		return h;
	}
}
