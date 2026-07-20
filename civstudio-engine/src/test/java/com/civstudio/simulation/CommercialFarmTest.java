package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.settlement.Settlement;

/**
 * P3 — the necessity farm re-roles as a <b>commercial / surplus</b> producer once the colony runs the
 * home-plot subsistence economy ({@code docs/plot-working-plan.md} P3). With landed households
 * self-feeding from their plots, the food market's demand is thin, so the sole necessity farm is free
 * to scale <b>down</b>: the subsistence floor (#1) that would otherwise keep it hiring labour and
 * producing food through the thin-demand transient is <b>retired</b> for a home-plots colony ({@code
 * Settlement.hasHomePlots()}). The colony survives regardless, because survival now rests on the home
 * plots, not on the farm.
 * <p>
 * The test proves the floor's retirement by contrast: it founds the <em>same</em> small band twice —
 * once without home plots (the floor binds, and is exactly what keeps that flag-off booted colony
 * alive — cf. {@link CampBootViabilityTest}) and once with — and shows the sole farm hires plenty of
 * labour in the first case and scales to a dormant role in the second, while <b>both</b> colonies
 * survive.
 */
class CommercialFarmTest {

	@Test
	void homePlotsRetireTheSubsistenceFloorSoTheFoodSectorScalesDown() {
		double flooredLabour = peakSoleFarmLabour(false);
		double retiredLabour = peakSoleFarmLabour(true);

		assertTrue(flooredLabour > 1.0,
				"without home plots the subsistence floor keeps the sole food farm hiring labour "
						+ "(peak " + flooredLabour + ")");
		assertTrue(retiredLabour < 0.1 * flooredLabour,
				"with home plots the floor is retired and the food sector scales down to a dormant "
						+ "surplus role (peak labour " + retiredLabour + " vs floored " + flooredLabour + ")");
	}

	// found the small found-at-camp band (with or without home plots), climb it to the boot, then run two
	// years and return the sole necessity farm's peak labour over the run — asserting the colony survives
	// (it does either way: via the floor when flag-off, via the home plots when flag-on).
	private double peakSoleFarmLabour(boolean homePlots) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).homePlots(homePlots).retinueSize(60).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.start();

		for (int day = 0; day < 200 && c.getRuler() == null && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.getRuler() != null, "the camp booted its ruler economy (homePlots=" + homePlots + ")");
		assertTrue(c.hasHomePlots() == homePlots,
				"hasHomePlots() reflects the home-plot economy (homePlots=" + homePlots + ")");

		double maxLabour = 0;
		for (int day = 0; day < 730 && c.isAlive(); day++) {
			c.newDay();
			// the sole food farm's labour (its real activity — getOutput() reads stale when a firm hires
			// no labour, so labour is the honest signal)
			int farms = 0;
			double soleLabour = 0;
			for (Agent a : c.getAgents())
				if (a.isAlive() && a instanceof NFirm f) {
					farms++;
					soleLabour = f.getLabor();
				}
			if (farms == 1)
				maxLabour = Math.max(maxLabour, soleLabour);
		}

		assertTrue(c.isAlive(), "the colony survives the run (homePlots=" + homePlots + ")");
		return maxLabour;
	}
}
