package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.good.RationSize;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.Settlement;

/**
 * End-to-end coverage of the <b>plot-working economy</b> ({@code docs/plot-working-plan.md} P1): a
 * home-plots colony seats its settled laborer households on plots they farm for subsistence food,
 * dropped straight into their larders outside the market. This decouples survival from the market —
 * the market becomes surplus/trade, not whether people eat.
 * <p>
 * The test founds a small band as a {@link com.civstudio.settlement.SettlementTier#CAMP camp} (as
 * {@link CampFoundingEconomy} does) with {@code homePlots} on, climbs it to the boot, and asserts (a)
 * the settled households are actually seated on home plots, (b) a landed household's plot yields it at
 * least a full adult ration — the calibration goal that makes it self-sufficient on average ground —
 * and (c) the colony lives through and past the boot transient. The field-level mechanics are covered
 * by {@code com.civstudio.settlement.HomePlotTest}.
 */
class HomePlotEconomyTest {

	@Test
	void landedHouseholdsSelfFeedFromTheirHomePlots() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).homePlots(true).retinueSize(60).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement c = h.getColony();
		c.start();

		// climb the camp to the boot (the ruler economy and the settled laborer households appear here)
		for (int day = 0; day < 200 && c.getRuler() == null && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.getRuler() != null, "the camp booted its ruler economy at SMALLHOLDING");

		// settle past the boot transient
		for (int day = 0; day < 90 && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.isAlive(), "the home-plots colony survives past the boot transient");

		// the settled households are seated on home plots, and each landed household's plot yields it
		// at least a full adult ration (FINE) — it self-feeds on its own land (the P1 decoupling)
		int landed = 0;
		double totalPlotFood = 0;
		for (Agent a : c.getAgents())
			if (a.isAlive() && a instanceof Laborer l && l.getHomePlot() != null) {
				landed++;
				totalPlotFood += c.homePlotFoodYield(l.getHomePlot());
			}
		assertTrue(landed > 0, "settled laborer households were seated on home plots");
		double avgPlotFood = totalPlotFood / landed;
		assertTrue(avgPlotFood >= RationSize.FINE.perDay(),
				"a landed household's home plot yields it at least a full adult ration (avg plot food "
						+ avgPlotFood + " vs FINE " + RationSize.FINE.perDay() + ")");
	}

	/**
	 * The Malthusian shared-plot model (P2): when a home-plots colony founds more households than its
	 * province has workable plots, the households <b>share plots</b> (a plot's food splits among them)
	 * rather than the overflow being turned landless — there is <b>no hard one-per-plot cap</b>. Founded
	 * with a large pool (so the promoted cohort exceeds the ~74 plots) to force crowding.
	 */
	@Test
	void plotsAreSharedUnderCrowdingWithNoHardCap() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.homePlots(true).retinueSize(300).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement c = h.getColony();
		c.start();

		// right after founding the promoted cohort (~0.45 × 300) far exceeds the province's workable
		// plots, so households necessarily share plots — more landed households than distinct home plots
		Set<Plot> distinctPlots = new HashSet<>();
		int landed = 0;
		for (Agent a : c.getAgents())
			if (a.isAlive() && a instanceof Laborer l && l.getHomePlot() != null) {
				landed++;
				distinctPlots.add(l.getHomePlot());
			}
		assertTrue(landed > 0, "the colony founded landed households");
		assertTrue(landed > distinctPlots.size(),
				"more landed households (" + landed + ") than distinct home plots (" + distinctPlots.size()
						+ ") — plots are shared, there is no hard one-per-plot cap");
	}
}
