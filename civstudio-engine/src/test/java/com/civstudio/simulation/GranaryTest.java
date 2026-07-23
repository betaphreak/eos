package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Granary;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.laborer.LaborerConfig;
import com.civstudio.good.Necessity;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.name.Person;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.SkillTracker;

/**
 * The ever-normal {@link Granary} ({@code docs/granary.md}) on the
 * standard colony: the ruler-run food buffer that stabilizes the necessity price by
 * buying gluts at the floor and selling into scarcity at the ceiling. Phase-1 coverage
 * over a short pre-collapse horizon — that the granary is part of the standard founding,
 * that it actually trades (banks the early food surplus), that its cash P&L is
 * reconciled into the ruler's treasury (it hoards no idle money), and that it banks in
 * copper without adding a fourth bank.
 */
class GranaryTest {

	@Test
	void granaryBanksTheEarlyFoodSurplusAndReconcilesToTheRuler() {
		// a standard colony on a short horizon (it would not collapse this soon); the
		// founding food sector over-supplies early, so the necessity price runs below the
		// granary's floor and the granary buys the surplus into its reserve
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				// this test exercises the PURE-MARKET (granary-relief) mechanism, which the
				// 2026-07-23 default-flip retired as a default — opt out explicitly
				.homePlots(false).buildEconomy(false)
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony();

		Granary granary = h.getGranary();
		assertNotNull(granary, "a standard colony is founded with a granary");

		h.run();

		// the granary intervened: it bought the early glut into its strategic reserve
		assertTrue(granary.getTotalBought() > 0,
				"the granary should have bought the early food surplus, got "
						+ granary.getTotalBought());
		assertTrue(granary.getStock() > 0,
				"the granary should be holding a food reserve");

		// its cash P&L is the crown's: each step it reconciles its account to ~0 against
		// the gold treasury, so it hoards no idle money (the buy-low/sell-high value lands
		// in the ruler's treasury instead)
		assertEquals(0, granary.getCash(), 1.0,
				"the granary reconciles its cash to the ruler, holding ~0 itself");

		// the necessity price stayed bounded over the (healthy) horizon — no crash, no
		// skyrocket
		ConsumerGoodMarket nMkt = h.getNecessityMkt();
		double price = nMkt.getLastMktPrice();
		assertTrue(price > 0 && price < 10 * nMkt.getInitialPrice(),
				"the necessity price should stay in a sane band, was " + price);

		// the granary banks in copper — it adds no fourth bank to the three-currency
		// hierarchy (commoners copper, nobles silver, ruler gold)
		assertEquals(3, h.getBanks().size(),
				"expected three banks (copper, silver, gold)");
	}

	/**
	 * The pool-relief backstop ({@code docs/granary.md} §4.8): when the peasant pool's
	 * larder runs dry, it draws its ration from the granary's reserve <b>before any
	 * peasant starves</b> — and that internal draw is tallied as relief, not misread as a
	 * market sale (§4.7).
	 */
	@Test
	void poolDrawsReliefFromTheGranaryBeforeStarving() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				// this test exercises the PURE-MARKET (granary-relief) mechanism, which the
				// 2026-07-23 default-flip retired as a default — opt out explicitly
				.homePlots(false).buildEconomy(false)
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony();
		Retinue retinue = h.getRetinue();
		Granary granary = h.getGranary();
		assertNotNull(retinue);
		assertNotNull(granary);

		// give the granary a reserve and empty the pool's larder, so the pool's next feed
		// must come from the granary rather than its own store
		granary.getGood("Necessity").increase(5000);
		retinue.drawPromotionStock(retinue.getLarder());
		assertEquals(0, retinue.getLarder(), 1.0, "the pool's larder is drained");

		double granaryBefore = granary.getStock();
		retinue.act();

		// the pool ate from the granary (its reserve fell) and no peasant starved for food
		// the granary could supply
		assertTrue(granary.getStock() < granaryBefore,
				"the pool should have drawn relief from the granary, stock "
						+ granaryBefore + " -> " + granary.getStock());
		assertEquals(0, retinue.getLastStarved(),
				"no peasant should starve while the granary holds stock");

		// the granary's own act() must classify that draw as relief, not a market sale
		// (the draw also shrank the stock) — the no-double-counting guard of §4.7
		double soldBefore = granary.getTotalSold();
		granary.act();
		assertEquals(soldBefore, granary.getTotalSold(), 1e-6,
				"a relief draw must not be counted as a market sale");
		assertTrue(granary.getTotalReliefDrawn() > 0,
				"the relief draw should be tallied as relief drawn");
	}

	/**
	 * Child relief ({@code docs/granary.md} §5.2): when a household's larder cannot feed a
	 * child member, the child draws its ration from the granary before starving — so the
	 * next generation survives a lean spell instead of being culled (the measured reason
	 * fission never fires).
	 */
	@Test
	void anUnfedChildDrawsItsRationFromTheGranary() {
		SimulationHarness h = standardColony();
		Granary granary = h.getGranary();
		Laborer parent = firstLivingLaborer(h);

		// give the parent a newborn child, then starve its larder down to exactly the
		// head's ration — so the larder feeds the head but cannot feed the child
		Settlement colony = h.getColony();
		Member child = colony.getDemography().newChild(parent.getHead().surname(),
				parent.getHead().race(), colony, parent.getHead(), parent.getHead());
		parent.addMember(child);
		Necessity larder = (Necessity) parent.getGood("Necessity");
		larder.decrease(larder.getQuantity());
		larder.increase(LaborerConfig.DEFAULT.eatAmt()); // exactly the head's ration
		granary.getGood("Necessity").increase(100);

		int membersBefore = parent.getMemberCount();
		double granaryBefore = granary.getStock();
		parent.act();

		assertEquals(membersBefore, parent.getMemberCount(),
				"the child should be kept alive by granary relief, not starved off");
		assertTrue(granary.getStock() < granaryBefore,
				"the child's ration should have been drawn from the granary");
	}

	/**
	 * Granary-funded fission ({@code docs/granary.md} §5.3): a grown, colony-born child
	 * leaves to found its own household, dowered from the granary's reserve — and the
	 * split is <b>gated on the granary</b> being able to fund the dowry, not on the
	 * parent's larder (the second gate that kept fission from firing).
	 */
	@Test
	void grownChildFissionsWhenTheGranaryCanDowerIt() {
		// gate: with no strategic store, a grown child does NOT fission
		SimulationHarness empty = standardColonyWithoutGranaryStock();
		Laborer p0 = firstLivingLaborer(empty);
		p0.addMember(grownChildOf(p0, empty.getColony()));
		((Necessity) p0.getGood("Necessity")).increase(50);
		long f0 = empty.getFissionCount();
		empty.getColony().run(8); // spans a Monday's fission review
		assertEquals(f0, empty.getFissionCount(),
				"fission should wait while the granary cannot dower a new household");

		// funded: with a stocked granary, the grown child fissions into a new household
		SimulationHarness h = standardColony();
		Laborer parent = firstLivingLaborer(h);
		parent.addMember(grownChildOf(parent, h.getColony()));
		((Necessity) parent.getGood("Necessity")).increase(50);
		h.getGranary().getGood("Necessity").increase(1000);
		long before = h.getFissionCount();
		h.getColony().run(8);
		assertTrue(h.getFissionCount() > before,
				"a grown child should fission into a new household funded by the granary");
	}

	// --- helpers ---

	private static SimulationHarness standardColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				// this test exercises the PURE-MARKET (granary-relief) mechanism, which the
				// 2026-07-23 default-flip retired as a default — opt out explicitly
				.homePlots(false).buildEconomy(false)
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony();
		return h;
	}

	// a standard colony whose granary never accumulates stock (target 0), so the fission
	// gate sees an empty strategic store
	private static SimulationHarness standardColonyWithoutGranaryStock() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				// this test exercises the PURE-MARKET (granary-relief) mechanism, which the
				// 2026-07-23 default-flip retired as a default — opt out explicitly
				.homePlots(false).buildEconomy(false)
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.setGranaryConfig(com.civstudio.agent.GranaryConfig.DEFAULT.toBuilder()
				.targetDays(0).build());
		h.foundStandardColony();
		return h;
	}

	private static Laborer firstLivingLaborer(SimulationHarness h) {
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer l && l.isAlive())
				return l;
		throw new AssertionError("no living laborer in the colony");
	}

	// a grown (working-age), colony-born child for `parent`: an adult member with known
	// parentage, eligible to be emancipated by fission
	private static Member grownChildOf(Laborer parent, Settlement colony) {
		Member head = parent.getHead();
		SkillTracker skills =
				colony.getDemography().newSkillTracker(colony.getMeanSkill(head.gender()));
		Person p = new Person("Heir", head.surname(), head.gender(), skills, head.race());
		LocalDate birth = colony.getDate().minusYears(20);
		return new Member(p, birth, head, head);
	}
}
