package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.noble.Noble;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.Skill;
import com.civstudio.util.Rng;

/**
 * The Explorer renewal loop (docs/explorer-caravan.md): a returned explorer peasant <b>leaves the
 * pool and founds its own {@link Laborer} household</b> — it "becomes banked", so the finite
 * peasant pool gains a renewal path (a new household that can marry and bear children). Commit 1
 * covers the structural core (the pool-release + household founding); commit 2 adds the cash
 * reward — the gathered haul sold as an Enjoyment supply dump, the crown's tax, the ablest returnee
 * ennobled, and every household seeded with its share of the proceeds.
 */
class ExplorerRenewalTest {

	private static final int DHENIJANSAR = 4411;

	@Test
	void aReturnedExplorerPeasantLeavesThePoolAndFoundsAHousehold() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement colony = h.getColony();
		GameSession session = colony.getSession();
		Retinue pool = h.getRetinue();
		LocalDate today = colony.getDate();

		// draft a handful of adult peasants
		List<Member> draftees = new ArrayList<>();
		for (Member m : pool.getMembers()) {
			if (m.isAdult(today)) {
				draftees.add(m);
				if (draftees.size() == 5)
					break;
			}
		}
		assertEquals(5, draftees.size(), "the founded pool has adults to draft");

		long laborersBefore = colony.getAgents().stream()
				.filter(a -> a instanceof Laborer l && l.isAlive()).count();

		// muster with the colony's renewal reward wired on (as the provisioner does in a real run)
		double larder = draftees.size() * MarchingCaravan.WANDERING_RATION.perDay() * 70;
		ExplorerCaravan band = ExplorerCaravan.muster(colony, draftees, larder);
		band.setExpeditionReturn(h.expeditionRewardHandler());
		band.setCampingEnabled(false); // no foraging needed for commit 1 (no cash seed yet)
		band.setTripLimits(1e9, 20, 1); // turn home after 20 days out

		// drive the band out and home (summer — fastest march)
		Rng rng = session.getBandRng();
		LocalDate day = LocalDate.of(1445, 6, 21);
		for (int t = 0; t < 200 && !band.hasArrived(); t++) {
			band.tick(day, rng);
			day = day.plusDays(1);
		}
		assertTrue(band.hasArrived(), "the expedition returns home");

		// on return the reward scheduled an end-of-step founding per returnee; drive a few colony
		// newDays so applyScheduledAgentChanges runs them (the founding is deferred, like fission)
		for (int i = 0; i < 3; i++)
			colony.newDay();

		// every returned peasant left the pool (released by the reward to head its own household)
		for (Member m : draftees)
			assertFalse(pool.getMembers().contains(m),
					"a returned peasant leaves the pool to found a household");
		// and new laborer households appeared — the returnees are now banked household heads
		long laborersAfter = colony.getAgents().stream()
				.filter(a -> a instanceof Laborer l && l.isAlive()).count();
		assertTrue(laborersAfter > laborersBefore,
				"the returned peasants founded new laborer households (became banked)");
	}

	@Test
	void aReturningHaulEnnoblesTheAblestAndCashSeedsTheRest() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1)
				.externalInflowPerStep(0) // closed colony, so total money is conserved
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement colony = h.getColony();
		Retinue pool = h.getRetinue();
		LocalDate today = colony.getDate();

		// draft five adult peasants and mark them away, as a real muster does
		List<Member> draftees = new ArrayList<>();
		for (Member m : pool.getMembers()) {
			if (m.isAdult(today)) {
				m.setDrafted(true);
				draftees.add(m);
				if (draftees.size() == 5)
					break;
			}
		}
		assertEquals(5, draftees.size(), "the founded pool has adults to draft");

		// the ablest returnee (highest SOCIAL) is the one that gets ennobled
		Member ablest = draftees.stream()
				.max((a, b) -> Integer.compare(a.skills().level(Skill.SOCIAL),
						b.skills().level(Skill.SOCIAL)))
				.orElseThrow();

		// value the haul the same way the reward does (pre-first-clear → the market's founding
		// price), so we can predict the distributable proceeds
		ConsumerGoodMarket eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		int cargoUnits = 200;
		double price = eMkt.getLastMktPrice();
		if (!Double.isFinite(price) || price <= 0)
			price = eMkt.getInitialPrice();
		double distributable = cargoUnits * price * (1 - cfg.expeditionTaxRate());

		Set<Agent> laborersBefore = agentsOfType(colony, Laborer.class);
		Set<Agent> noblesBefore = agentsOfType(colony, Noble.class);

		// reward the return: dump the haul (a persisting sell offer) and schedule the distribution
		h.expeditionRewardHandler().rewardReturn(colony, draftees, cargoUnits);

		// one day: the dump clears (crediting the crown) and applyScheduledAgentChanges founds the
		// new households — which have not yet acted, so their checking is exactly the seed
		colony.newDay();

		// the returnees left the pool
		for (Member m : draftees)
			assertFalse(pool.getMembers().contains(m),
					"a returned peasant leaves the pool");

		// the ablest returnee was ennobled into a brand-new noble, keeping its given name
		Set<Agent> freshNobles = added(agentsOfType(colony, Noble.class), noblesBefore);
		assertEquals(1, freshNobles.size(), "the ablest returnee is ennobled");
		Noble noble = (Noble) freshNobles.iterator().next();
		assertEquals(ablest.person().givenName(), noble.getHead().person().givenName(),
				"the ablest returnee (highest SOCIAL) is the one ennobled");
		assertTrue(noble.getWealth() > 0, "the new noble is cash-seeded from the haul");

		// the other four returnees founded cash-seeded laborer households
		Set<Agent> freshLaborers = added(agentsOfType(colony, Laborer.class), laborersBefore);
		assertEquals(4, freshLaborers.size(), "the other returnees found laborer households");
		double seeded = noble.getWealth();
		for (Agent a : freshLaborers) {
			Laborer lab = (Laborer) a;
			assertTrue(lab.getWealth() > 0, "each founded household is cash-seeded");
			seeded += lab.getWealth();
		}

		// the seeds sum to the taxed-net proceeds — the crown paid them out of its treasury (which
		// recoups the cash via the dumped haul), so money is conserved
		assertEquals(distributable, seeded, distributable * 1e-6 + 1e-6,
				"the household seeds sum to the taxed-net haul proceeds");
	}

	// every living agent of `type` in the colony, as an identity set
	private static Set<Agent> agentsOfType(Settlement colony, Class<?> type) {
		Set<Agent> set = new HashSet<>();
		for (Agent a : colony.getAgents())
			if (type.isInstance(a) && a.isAlive())
				set.add(a);
		return set;
	}

	// the members of `now` not present in `before`
	private static Set<Agent> added(Set<Agent> now, Set<Agent> before) {
		Set<Agent> fresh = new HashSet<>(now);
		fresh.removeAll(before);
		return fresh;
	}
}
