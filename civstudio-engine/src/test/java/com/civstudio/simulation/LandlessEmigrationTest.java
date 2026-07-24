package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.SettlerCaravan;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * The <b>landless are the source of caravans</b> (user ruling 2026-07-24): a colony that can give a
 * household no ground of its own ({@code homePlot == null} — no empty plot to farm and every built
 * plot's housing full) sheds it, with its fellows, as a wandering {@link SettlerCaravan settler
 * band} seeking land ({@link com.civstudio.agent.LandlessProvisioner}). Under the building-signal
 * land model true landlessness is rare (built plots stack housing generously), so this test strands
 * a few households directly — the {@code homePlot == null} state the allocator hands out once a
 * colony genuinely runs out of both farm and housing room — and asserts the colony musters them
 * into a settler band that leaves: the outlet, not a collapse.
 */
class LandlessEmigrationTest {

	@Test
	void aColonyShedsItsLandlessAsASettlerBand() {
		// found at full tier (a district-bearing City) so the landless provisioner is installed and
		// a ruler is seated from day one
		SimulationConfig cfg = SimulationConfig.DEFAULT; // foundAtCamp false, homePlots + buildEconomy on
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(60).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		GameSession session = c.getSession();
		c.start();

		// settle a while so the founding wave lands households on plots
		for (int day = 0; day < 200 && c.isAlive(); day++)
			c.newDay();

		// strand a handful of landed households — the land-ceiling state (homePlot == null) the
		// allocator produces once a colony runs out of farm and housing room; here we induce it
		Set<Integer> stranded = new HashSet<>();
		List<Laborer> landed = new ArrayList<>();
		for (Agent a : c.getAgents())
			if (a.isAlive() && a instanceof Laborer l && l.isWorkforce() && l.getHomePlot() != null)
				landed.add(l);
		assertTrue(landed.size() >= 4, "the settled colony has landed households to strand");
		for (int i = 0; i < 4; i++) {
			landed.get(i).setHomePlot(null);
			stranded.add(landed.get(i).getID());
		}

		// run past the provisioner's next firing (it musters once the landless reach the min band)
		int departedBefore = h.getLandlessDeparted();
		for (int day = 0; day < 40 && h.getLandlessDeparted() == departedBefore && c.isAlive(); day++)
			c.newDay();

		assertTrue(c.isAlive(), "the colony survives shedding its landless — an outlet, not a collapse");
		assertTrue(h.getLandlessDeparted() > departedBefore,
				"the colony shed its landless as a settler band (departed=" + h.getLandlessDeparted() + ")");

		// a settler band is now wandering the session
		long settlers = session.getCaravans().stream().filter(b -> b instanceof SettlerCaravan).count();
		assertTrue(settlers > 0, "a settler band is wandering the session (" + settlers + ")");

		// and the stranded households have left the colony (they are the band now)
		for (Agent a : c.getAgents())
			if (a instanceof Laborer l && a.isAlive())
				assertFalse(stranded.contains(l.getID()),
						"a stranded household left with the band, still present: " + l.getID());
	}
}
