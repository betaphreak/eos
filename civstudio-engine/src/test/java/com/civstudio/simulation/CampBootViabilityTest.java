package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.settlement.Settlement;

/**
 * Guards the <b>subsistence-floor</b> fix (the small-booted-colony viability, {@code
 * docs/settlement-tier-ladder-plan.md} / rank-ladder discussion): a small found-at-Camp colony that
 * boots its ruler economy at SMALLHOLDING must <b>not</b> die in the demand-deficient boot transient.
 * <p>
 * The pathology it pins: at the boot every buyer opens well-stocked, so market demand is ~0 → the
 * sole necessity farm's price crashes to 0 → its revenue, wage budget and output collapse → the
 * colony is left with <b>no food supply</b>, and once the laborers eat down their founding stock
 * (~30 days) they mass-starve — the colony dissolves within a month. The fix floors the <b>sole</b>
 * food farm's wage/output (people farm to eat, not only for profit) and the price, so the farm keeps
 * producing, the laborers restock the now-cheap food, and the colony lives. This test drives that
 * window directly and asserts the farm keeps feeding the colony and the laborers stay alive and fed.
 */
class CampBootViabilityTest {

	@Test
	void aSmallBootedColonySurvivesTheDemandDeficientTransient() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).retinueSize(60).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement c = h.getColony();
		c.start();

		// climb the camp to the boot
		for (int day = 0; day < 200 && c.getRuler() == null && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.getRuler() != null, "the camp booted its ruler economy at SMALLHOLDING");

		// drive well past the ~30-day starvation cliff of the old death spiral
		double minAvgNStock = Double.MAX_VALUE;
		double maxFarmProduction = 0;
		for (int day = 0; day < 90 && c.isAlive(); day++) {
			c.newDay();
			int laborers = 0;
			double nStockSum = 0, farmStock = 0;
			for (Agent a : c.getAgents()) {
				if (!a.isAlive())
					continue;
				if (a instanceof Laborer l) {
					laborers++;
					var g = l.getGood("Necessity");
					if (g != null)
						nStockSum += g.getQuantity();
				} else if (a instanceof NFirm f) {
					var g = f.getGood("Necessity");
					if (g != null)
						farmStock += g.getQuantity();
				}
			}
			if (laborers > 0)
				minAvgNStock = Math.min(minAvgNStock, nStockSum / laborers);
			maxFarmProduction = Math.max(maxFarmProduction, farmStock);
		}

		assertTrue(c.isAlive(), "the small booted colony survives the demand-deficient transient");
		// the laborers never starve down to empty — the sole farm keeps producing, so they restock the
		// cheap food (the deflationary death spiral is broken)
		assertTrue(minAvgNStock > 1.0,
				"laborers stay fed through the transient (min avg necessity stock " + minAvgNStock
						+ " stayed above starvation)");
		// the sole food farm actually holds/produces stock (it did not collapse to zero output)
		assertTrue(maxFarmProduction > 0.0,
				"the sole necessity farm keeps producing food (its stock did not collapse to zero)");
		// the necessity price recovered above the crash floor rather than staying pinned at ~0
		ConsumerGoodMarket nMkt = (ConsumerGoodMarket) c.getMarket("Necessity");
		assertTrue(nMkt.getLastMktPrice() > 0.0, "the necessity price is no longer pinned at 0");
	}
}
