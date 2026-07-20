package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Captain;
import com.civstudio.agent.Rank;
import com.civstudio.agent.Retinue;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * Drives the found-at-Camp lifecycle (docs/settlement-tier-ladder-plan.md Phase D): a colony that
 * opts in ({@link SimulationConfig#foundAtCamp()}) is founded as a {@link Captain}-led foraging
 * {@link SettlementTier#CAMP camp} with no ruler economy, climbs the tier ladder on its foraged
 * food surplus, and <b>boots the full ruler economy</b> (ruler, firms, laborers promoted from the
 * pool) when it reaches {@link SettlementTier#SMALLHOLDING}.
 */
class SettlementCampFoundingTest {

	private static final int EARGATE = 2; // an ordinary (single-urban-plot) site: caps at SMALLHOLDING

	private static SimulationHarness campHarness(int provinceId) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().foundAtCamp(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, provinceId);
		h.foundStandardColony();
		return h;
	}

	private static Captain captainOf(Settlement c) {
		for (Agent a : c.getAgents())
			if (a instanceof Captain cap && cap.isAlive())
				return cap;
		return null;
	}

	private static Retinue poolOf(Settlement c) {
		for (Agent a : c.getAgents())
			if (a instanceof Retinue r)
				return r;
		return null;
	}

	@Test
	void foundsAsACaptainLedCampWithNoRulerEconomy() {
		Settlement c = campHarness(EARGATE).getColony();
		c.start();

		assertEquals(SettlementTier.CAMP, c.getTier(), "a foundAtCamp colony is founded at CAMP");
		assertNull(c.getRuler(), "a camp has no ruler yet");
		Captain captain = captainOf(c);
		assertNotNull(captain, "a camp is led by a Captain");
		assertEquals(Rank.CARAVAN, captain.rank(), "the captain commands a CARAVAN");
		Retinue pool = poolOf(c);
		assertNotNull(pool, "a camp has a foraging pool");
		assertTrue(pool.isCamped(), "the pool forages in camp mode");
		assertEquals(pool, captain.getFollowing(), "the captain holds the pool as its asset");
		assertFalse(c.hasDistricts(), "a camp is sub-TOWN (no districts)");
	}

	@Test
	void climbsFromCampAndBootsTheRulerEconomyAtSmallholding() {
		Settlement c = campHarness(EARGATE).getColony();
		c.start();
		assertEquals(SettlementTier.CAMP, c.getTier());

		// prime the food box so a single day's growth climbs the whole way to SMALLHOLDING; the
		// climb stops there and schedules the ruler-economy boot for end of this same step.
		c.setFoodBox(10_000_000);
		c.newDay();

		assertEquals(SettlementTier.SMALLHOLDING, c.getTier(),
				"the well-fed camp climbs to its SMALLHOLDING ceiling");
		assertNotNull(c.getRuler(), "the ruler economy booted at SMALLHOLDING");
		assertTrue(c.getRuler().isAlive(), "the booted ruler is alive");
		assertNull(captainOf(c), "the captain was reformed into the ruler (retired)");
		assertTrue(c.householdCount() > 1,
				"laborer households were promoted from the pool at the boot");
		assertNotNull(c.getMarket("Necessity"), "the necessity market exists after the boot");
	}

	private static final int DHENIJANSAR = 4411; // a real Anbennar site (has a forage plot to work)

	@Test
	void aCampBuildsAForageImprovementAndClimbsOnItsYield() {
		// a small band founds a camp at a real site and climbs on its FORAGED food alone (no food-box
		// priming) — proving Phase G's site-scaled forage + the HUNTING_CAMP it builds actually feed
		// the climb. Before the improvement the forage sits near parity; building it lifts the site
		// food and the surplus carries the camp up the ladder.
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, DHENIJANSAR);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(60).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.start();
		assertEquals(SettlementTier.CAMP, c.getTier());
		double bareSiteFood = c.campPlotFood(); // wild terrain (+ feature), no improvement yet

		// drive it on real forage; the well-sited band builds its HUNTING_CAMP and climbs
		for (int i = 0; i < 400 && c.getTier() == SettlementTier.CAMP; i++)
			c.newDay();

		assertTrue(c.campPlotFood() > bareSiteFood,
				"building the HUNTING_CAMP lifts the forage plot's food yield");
		assertTrue(c.getTier().atLeast(SettlementTier.COTTAGE),
				"the camp climbed past CAMP on its foraged surplus (site-scaled forage feeds growth)");
	}

	@Test
	void aSubViableBandStaysACampAndDoesNotBoot() {
		// a band too small to found a self-sustaining colony (#2, the minimum-viable-founding gate)
		// must NOT climb to SMALLHOLDING and boot a doomed ruler economy — it stays a foraging camp.
		// A 25-forager band is below MIN_VIABLE_BOOT_POPULATION (40).
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, DHENIJANSAR);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(25).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.start();

		// drive it for a few years on real forage; it can climb the low camp rungs but the viability
		// gate holds it below SMALLHOLDING, so it never boots a ruler economy
		for (int i = 0; i < 1100 && c.isAlive(); i++)
			c.newDay();

		assertNull(c.getRuler(), "a sub-viable band never boots a ruler economy");
		assertFalse(c.getTier().atLeast(SettlementTier.SMALLHOLDING),
				"the viability gate holds a too-small band below SMALLHOLDING (it stays a camp)");
	}

	@Test
	void aStarvingCampStrikesAndDepartsAsACaravan() {
		// a small band on POOR forage (below CAMP_RATION) cannot feed itself: once its opening
		// larder is spent it starves and, unable to sustain the band on this ground, strikes camp
		// and departs as a wandering caravan led by its captain (the CAMP -> caravan hand-off, E).
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, EARGATE);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(40).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.setCampForagePerForager(0.02); // well below CAMP_RATION (0.1) — the band cannot feed itself
		c.start();
		assertEquals(SettlementTier.CAMP, c.getTier());
		assertNull(c.getRuler(), "a starving camp never boots a ruler economy");

		c.run(3650); // up to 10 years; it ends far sooner, when the band starves out

		assertFalse(c.isAlive(), "a camp that cannot feed its band ends its settled life");
		assertNotNull(c.getDepartedBand(),
				"it strikes camp and departs as a wandering caravan (led by its captain)");
		assertTrue(c.getDepartedBand().getFollowing().size() > 0,
				"the survivors take to the road as the band's following");
	}
}
