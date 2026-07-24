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
 * A <b>non-urban plot holds a single household</b> (user ruling 2026-07-24): it is that family's
 * own ground to farm and build its house on; only the dense urban core stacks households. The home
 * plot allocator ({@code PlotField.claimHomePlot}) enforces this — it crowds only {@link
 * Plot#urban() urban} plots, and a colony that outgrows its land spills to landlessness rather than
 * doubling up a farm plot. This grows a colony well past its non-urban plot supply (retinue 60, a
 * province cap in the tens) and asserts no non-urban plot ever carries two households.
 */
class SingleHouseholdPlotTest {

	@Test
	void nonUrbanPlotsHoldAtMostOneHousehold() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).homePlots(true).buildEconomy(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(60).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.start();

		// boot the ruler economy, then run a couple of settled years so the founding wave lands
		// many households and the allocator is forced past fresh land into its crowding branch
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

		// the invariant: every NON-urban home plot carries a single household
		for (Map.Entry<Plot, Integer> e : load.entrySet())
			if (!e.getKey().urban())
				assertTrue(e.getValue() <= 1,
						"a non-urban home plot must hold a single household, held " + e.getValue()
								+ " at (" + e.getKey().x() + ", " + e.getKey().y() + ")");
	}
}
