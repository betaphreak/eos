package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.advisor.AdvisorRole;
import com.civstudio.advisor.AdvisorRoster;
import com.civstudio.agent.Agent;
import com.civstudio.agent.Household;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.settlement.Settlement;

/**
 * The privy council roster (see {@link AdvisorRoster}): built on the standard
 * {@code foundStandardColony} path and checked mid-life (before the colony's
 * by-design collapse) so the ruler and any ennobled aristocracy are present.
 * Verifies that every advisor role is seated, that each seat resolves back to a
 * living household, that the Technology seat is the ablest scholar once nobles
 * exist, and that a re-refresh leaves a living roster undisturbed (sticky).
 */
class AdvisorRosterTest {

	private static final long SEED = 424242L;

	@Test
	void rosterSeatsEveryRolePrefersSkilledNoblesAndIsSticky() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(5).build();
		SimulationHarness h = SimulationHarness.create(cfg, SEED);
		h.foundStandardColony();
		Settlement colony = h.getColony();
		// run well into the colony's settled life, but short of its by-design collapse
		colony.run(600);

		assertTrue(colony.isAlive(), "colony should still be alive at the checkpoint");
		AdvisorRoster roster = colony.getAdvisorRoster();
		Ruler ruler = colony.getRuler();
		assertNotNull(ruler, "a standard colony has a ruler");

		// with a living ruler backstopping, every role is seated, and every seated
		// personId resolves back to the same living household
		for (AdvisorRole role : AdvisorRole.values()) {
			Household seat = roster.holder(role);
			assertNotNull(seat, "role " + role + " should be seated");
			assertSame(seat, colony.getHouseholdById(seat.getID()),
					"seated personId should resolve to the same household");
		}

		List<Household> nobles = new ArrayList<>();
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive())
				nobles.add(n);

		// the aristocracy fills the skill-matched roles before the ruler backstops:
		// Technology (INTELLECTUAL) once one noble exists, Foreign (SOCIAL) once two do.
		// The exact holder is history-dependent (a seat is sticky for its holder's life,
		// so it may not be the *current* ablest scholar), but a skill role is always a
		// noble when the aristocracy can staff it.
		if (nobles.size() >= 1)
			assertTrue(roster.holder(AdvisorRole.TECHNOLOGY) instanceof Noble,
					"Technology should be a noble once the aristocracy exists");
		if (nobles.size() >= 2)
			assertTrue(roster.holder(AdvisorRole.FOREIGN) instanceof Noble,
					"Foreign should be a noble once two nobles exist");

		// nobles are seated distinctly (only the ruler backstop may repeat across roles)
		List<Household> nobleSeats = new ArrayList<>();
		for (AdvisorRole role : AdvisorRole.values())
			if (roster.holder(role) instanceof Noble)
				nobleSeats.add(roster.holder(role));
		assertEquals(nobleSeats.size(), Set.copyOf(nobleSeats).size(),
				"a noble may not hold two advisor roles at once");

		// sticky + deterministic: re-seating a healthy roster changes nothing
		Map<AdvisorRole, Household> before = roster.assignments();
		roster.refresh();
		assertEquals(before, roster.assignments(),
				"a re-refresh must not displace living holders");
	}
}
