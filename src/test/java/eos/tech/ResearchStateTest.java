package eos.tech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import eos.era.Era;
import eos.settlement.GameSession;
import eos.settlement.Settlement;

/**
 * Unit tests for {@link ResearchState}'s mechanics on a real (but un-run) colony:
 * the monthly pick chooses the cheapest researchable tech, intellectual labor
 * accrues toward it, and completing it applies its effects to the colony and leaves
 * it focus-less until the next review. Uses a tree loaded with the sample effect
 * overlay (the shipped overlay is empty) so a completion has an observable effect.
 */
class ResearchStateTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private static Settlement bareColony() {
		return new GameSession(42).newSettlement("Test", START, 35, 26, 5, 2,
				51.5074, -0.1278);
	}

	// the cheapest researchable tech from a Medieval-complete start is the single
	// Renaissance entry node, which the sample overlay gives an EXPORT +20% effect
	private static final String ENTRY = "TECH_RENAISSANCE_LIFESTYLE";

	@Test
	void reviewPicksCheapestResearchable() {
		TechTree tree = TechTree.load("/tech-effects-sample.json");
		ResearchState rs = new ResearchState(tree, bareColony(), Era.MEDIEVAL, 1.0);
		assertNull(rs.getFocus(), "no focus until the first review");
		rs.review();
		assertEquals(ENTRY, rs.getFocus().type());
		// the entry tech is Renaissance, so its cost is scaled by that era's
		// researchPercent (300%) — effectiveCost = cost × 3.0 at costScale 1.0
		double expected = tree.get(ENTRY).cost()
				* Era.RENAISSANCE.modifiers().researchPercent() / 100.0;
		assertEquals(expected, rs.effectiveCost(), 1e-9);
	}

	@Test
	void completingAFocusAppliesItsEffectsAndClearsFocus() {
		TechTree tree = TechTree.load("/tech-effects-sample.json");
		Settlement colony = bareColony();
		ResearchState rs = new ResearchState(tree, colony, Era.MEDIEVAL, 1.0);
		rs.review();
		int knownBefore = rs.getKnownCount();
		assertEquals(1.0, colony.getTechMultiplier(Sector.EXPORT));

		// deliver more than enough research points to complete the (era-scaled) focus
		rs.accrue(rs.effectiveCost() + 1);

		assertEquals(1, rs.getCompletedCount());
		assertEquals(knownBefore + 1, rs.getKnownCount());
		assertTrue(rs.getKnown().contains(ENTRY));
		// the entry tech's EXPORT +20% effect was applied to the colony
		assertEquals(1.2, colony.getTechMultiplier(Sector.EXPORT), 1e-12);
		// focus cleared; the next monthly review will pick a successor
		assertNull(rs.getFocus());
	}

	@Test
	void costScaleSlowsCompletion() {
		TechTree tree = TechTree.load("/tech-effects-sample.json");
		// 10x cost: an amount that would complete at scale 1.0 is not enough
		ResearchState rs = new ResearchState(tree, bareColony(), Era.MEDIEVAL, 10.0);
		rs.review();
		double scale1Cost = tree.get(ENTRY).cost()
				* Era.RENAISSANCE.modifiers().researchPercent() / 100.0;
		rs.accrue(scale1Cost + 1); // enough at scale 1, not at scale 10
		assertEquals(0, rs.getCompletedCount(), "10x cost should not be met yet");
		assertEquals(ENTRY, rs.getFocus().type(), "still researching the same focus");
	}

	@Test
	void rpBuffersWithoutAFocusAndCarriesToTheNextOne() {
		TechTree tree = TechTree.load("/tech-effects-sample.json");
		ResearchState rs = new ResearchState(tree, bareColony(), Era.MEDIEVAL, 1.0);
		// before any review there is no focus, but RP still accumulates (buffers)
		rs.accrue(500);
		assertEquals(500, rs.getProgress());
		assertEquals(0, rs.getCompletedCount());
		// the ruler's review picks a focus; the buffered 500 carries onto it
		rs.review();
		assertEquals(ENTRY, rs.getFocus().type());
		assertEquals(500, rs.getProgress(), "buffered RP is kept, not reset");
		// topping up past the cost completes it, and the overflow carries forward
		double ec = rs.effectiveCost();
		rs.accrue(ec); // 500 + ec > ec -> completes, carrying ~500 overflow
		assertEquals(1, rs.getCompletedCount());
		assertEquals(500, rs.getProgress(), 1e-9);
		assertNull(rs.getFocus());
	}

	@Test
	void warmStartFocusBeginsPartwayComplete() {
		TechTree tree = TechTree.load("/tech-effects-sample.json");
		ResearchState rs = new ResearchState(tree, bareColony(), Era.MEDIEVAL, 1.0);
		// warm-start at 90% of the entry tech: a small top-up completes it
		rs.seedInitialFocus(ENTRY, 0.9);
		assertEquals(ENTRY, rs.getFocus().type());
		double ec = rs.effectiveCost();
		assertEquals(0.9 * ec, rs.getProgress(), 1e-9);
		rs.accrue(0.2 * ec); // pushes past 100%
		assertEquals(1, rs.getCompletedCount());
	}

	@Test
	void snapshotAndRestoreCarriesTheTechTree() {
		TechTree tree = TechTree.load("/tech-effects-sample.json");
		Settlement first = bareColony();
		ResearchState rs = new ResearchState(tree, first, Era.MEDIEVAL, 1.0);
		rs.review();
		rs.accrue(rs.effectiveCost() + 50); // complete ENTRY, buffer 50
		assertEquals(1, rs.getCompletedCount());

		// a band carries the snapshot to a fresh colony, which restores it
		ResearchSnapshot snap = rs.snapshot();
		Settlement refounded = bareColony();
		ResearchState restored =
				ResearchState.restore(tree, refounded, snap, 1.0);
		assertEquals(1, restored.getCompletedCount());
		assertTrue(restored.getKnown().contains(ENTRY));
		assertEquals(50, restored.getProgress(), 1e-9);
		// the researched tech's EXPORT +20% effect is re-applied to the new colony
		assertEquals(1.2, refounded.getTechMultiplier(Sector.EXPORT), 1e-12);
	}
}
