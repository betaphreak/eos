package eos.market;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.laborer.Laborer;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.name.Gender;
import eos.simulation.SimulationConfig;
import eos.simulation.SimulationHarness;

/**
 * Verifies the {@link WeddingMarket}: unmarried households take a spouse of the
 * opposite gender out of the peasant pool on weekends. Runs a small pool colony
 * over a short horizon and checks that weddings occurred, that the spouse really
 * is the opposite gender of the head, and that the ruler (who weds first of all)
 * has acquired a spouse. The non-linear bride-price curve is checked directly as
 * a pure function.
 */
class WeddingMarketTest {

	@Test
	void bridePriceIsSuperLinearInSkill() {
		WeddingConfig c = WeddingConfig.DEFAULT;
		// the unskilled floor is the base cost, and the curve is convex: a level-4
		// spouse costs more than twice a level-2 spouse (a linear price would cost
		// exactly twice as much per unit of skill)
		assertEquals(c.baseCost(), c.costFor(0), 1e-9, "level-0 spouse = base cost");
		assertTrue(c.costFor(4) > 2 * c.costFor(2),
				"bride-price should be super-linear in skill");
		// the immigrant recruitment cost is tied to the bride-price scale
		assertEquals(c.immigrantCostFactor() * c.baseCost(), c.immigrantCost(), 1e-9,
				"immigrant cost should be immigrantCostFactor x baseCost");
	}

	@Test
	void unmetWeddingDemandRecruitsImmigrants() {
		SimulationHarness h =
				assertDoesNotThrow(WeddingMarketTest::runHighPromotionColony);
		// a tiny founding reserve is wed out within a couple of weekends; thereafter
		// single households keep seeking but find no opposite-gender candidate, so the
		// colony recruits gold-funded immigrants into the pool
		assertTrue(h.getRetinue().getImmigrantCount() > 0,
				"unmet wedding demand should recruit immigrants into the pool");
	}

	@Test
	void householdsWedOppositeGenderSpousesFromThePool() {
		SimulationHarness h =
				assertDoesNotThrow(WeddingMarketTest::runSmallPoolColony);

		// weddings happened: peasants were wed out of the pool
		assertTrue(h.getRetinue().getMarriedOutCount() > 0,
				"expected peasants to be wed out of the pool");

		// the sovereign weds first of all (free, from its own wards), so after a
		// year it has taken a spouse of the opposite gender
		Ruler ruler = h.getColony().getRuler();
		assertEquals(2, ruler.getMemberCount(), "ruler should have wed a spouse");
		assertEquals(ruler.getHead().gender().opposite(),
				ruler.getMembers().get(1).gender(),
				"ruler's spouse should be the opposite gender");

		// every married household's spouse is the opposite gender of its head
		int married = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer l && l.getMemberCount() == 2) {
				married++;
				Gender head = l.getHead().gender();
				assertEquals(head.opposite(), l.getMembers().get(1).gender(),
						"a laborer's spouse should be the opposite gender of the head");
			}
		assertTrue(married > 0, "expected at least one married laborer alive");
	}

	/**
	 * A small {@link eos.simulation.HomogeneousEconomy}-style pool colony over a
	 * one-year horizon — enough to reach several weekends and wed many households,
	 * short enough that the labor force is still alive — with no printers.
	 */
	private static SimulationHarness runSmallPoolColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).numEFirms(2).numNFirms(10)
				.retinueSize(120).promotionRatio(0.4).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.run();
		return h;
	}

	/**
	 * A small pool colony with a <b>high promotion ratio</b> (so the standing
	 * reserve is tiny and is wed out within a few weekends) over a two-year horizon.
	 * Once the reserve is gone, single households keep seeking spouses with no
	 * candidate to match, triggering gold-funded immigrant recruitment.
	 */
	private static SimulationHarness runHighPromotionColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(2).numEFirms(2).numNFirms(10)
				.retinueSize(60).promotionRatio(0.8).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.run();
		return h;
	}
}
