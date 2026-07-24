package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IdentityHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.Settlement;

/**
 * An <b>empty (unbuilt) plot holds a single household</b> (user ruling 2026-07-24): empty ground is
 * that family's own farm and house site, while only <b>developed</b> ground (a plot with a regular
 * building — {@link Plot#hasRegularBuilding()}) stacks households as dense housing. The home-plot
 * allocator ({@code PlotField.claimHomePlot}) enforces this — it seats one household per empty plot
 * and stacks only on built plots. This grows a colony a couple of settled years and asserts no
 * unbuilt farm plot ever carries two households.
 */
class SingleHouseholdPlotTest {

	@Test
	void unbuiltFarmPlotsHoldAtMostOneHousehold() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).homePlots(true).buildEconomy(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(60).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.start();

		// boot the ruler economy, then run a couple of settled years so the founding wave lands many
		// households and the allocator is exercised past fresh land
		for (int day = 0; day < 200 && c.getRuler() == null && c.isAlive(); day++)
			c.newDay();
		for (int day = 0; day < 730 && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.isAlive(), "the colony survives the run");

		// tally living households per home plot
		Map<Plot, Integer> load = new IdentityHashMap<>();
		for (Agent a : c.getAgents())
			if (a.isAlive() && a instanceof Laborer l && l.getHomePlot() != null)
				load.merge(l.getHomePlot(), 1, Integer::sum);
		assertFalse(load.isEmpty(), "the settled colony has landed households");

		// the invariant: every UNBUILT (farm) home plot carries a single household; only developed
		// ground stacks
		for (Map.Entry<Plot, Integer> e : load.entrySet())
			if (!e.getKey().hasRegularBuilding())
				assertTrue(e.getValue() <= 1,
						"an unbuilt farm plot must hold a single household, held " + e.getValue()
								+ " at (" + e.getKey().x() + ", " + e.getKey().y() + ")");
	}
}
