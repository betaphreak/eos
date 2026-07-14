package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * The Explorer renewal loop, commit 1 (docs/explorer-caravan.md): a returned explorer peasant
 * <b>leaves the pool and founds its own {@link Laborer} household</b> — it "becomes banked", so the
 * finite peasant pool gains a renewal path (a new household that can marry and bear children). The
 * cash seed from selling the haul + the ruler's tax + ennobling the ablest are commit 2; here the
 * new households open on the standard founding stock.
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
}
