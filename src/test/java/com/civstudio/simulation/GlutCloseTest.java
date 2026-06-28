package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.laborer.FertilityConfig;
import com.civstudio.settlement.Settlement;

/**
 * Covers the <b>price/profit-aware close rule</b> of the dynamic firm provisioning
 * (see {@code Ruler.reviewSector} and {@code docs/food-balance.md}). A high-mean-skill
 * colony over-produces against ration-capped necessity demand and crashes its own
 * price (a deflationary glut); the necessity firms then run a <b>loss</b> while still
 * at high capacity utilization. The utilization-only close rule was blind to this and
 * <b>never fired</b>; the added "unprofitable glut" trigger (negative sector profit
 * with no unmet-demand pressure) now contracts the over-supplied sector. We assert the
 * necessity firm count <b>rises and then falls</b> over the run — i.e. firms were
 * dissolved, which only the provisioning's close path does.
 */
class GlutCloseTest {

	@Test
	void unprofitableGlutContractsTheNecessitySector() {
		// a high founding mean skill makes workers hyper-productive, so the necessity
		// sector over-supplies the ration-capped market and crashes its price into a loss
		FertilityConfig noBirths =
				FertilityConfig.DEFAULT.toBuilder().dailyBirthProb(0).build();
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(5).fertility(noBirths)
				// found with the single seed necessity firm (disable founding
				// provisioning): this test exercises the glut-CLOSE rule, so it needs the
				// sector to charter up and then contract; founding it already full would
				// collapse the high-skill colony before the close rule can fire
				.foundingLaborersPerNFirm(0)
				.meanSkillMale(12).meanSkillFemale(9).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		Settlement colony = h.getColony();

		// track the necessity firm count across the run: it should grow past 1 and then
		// shrink as the loss-making glut is contracted (firms only ever leave the
		// population by the provisioning dissolving them)
		int[] peak = { 0 };
		boolean[] contracted = { false };
		colony.addStepAction(() -> {
			if (colony.getTimeStep() % 30 != 0)
				return;
			int n = 0;
			for (Agent a : colony.getAgents())
				if (a instanceof NFirm f && f.isAlive())
					n++;
			if (n > peak[0])
				peak[0] = n;
			else if (n < peak[0])
				contracted[0] = true; // count fell below an earlier peak — a dissolution
		});
		assertDoesNotThrow(h::run);

		assertTrue(peak[0] >= 2,
				"the necessity sector should have chartered past one firm, peak=" + peak[0]);
		assertTrue(contracted[0],
				"the price/profit-aware close rule should dissolve a loss-making "
						+ "necessity firm in a deflationary glut (the sector contracts)");
	}
}
