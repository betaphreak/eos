package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Rank;
import com.civstudio.agent.ruler.Mayor;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.settlement.Settlement;

/**
 * R2 of the rank-ladder improvements (see {@code docs/rank-ladder-improvements.md}): the
 * {@code TOWN → METROPOLIS} head crossing reforms the settlement's {@link Ruler}
 * ({@link Rank#VILLAGE}) into a {@link Mayor} ({@link Rank#CITY}) via the targeted
 * {@link com.civstudio.agent.RankLadder#reformTo} — a same-bank (gold&rarr;gold) reform that carries
 * the treasury 1:1, so the colony's money is conserved. Driven here directly (a metropolis's ≥1000
 * population is impractical to accumulate in a unit test), through the end-of-step path so the old
 * ruler is retired cleanly.
 */
class MayorReformTest {

	@Test
	void aRulerUrbanizesIntoAMayorWithTheTreasuryConserved() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement colony = h.getColony();
		colony.start();
		colony.run(60); // let the economy settle and the ruler build a treasury

		Ruler ruler = colony.getRuler();
		assertNotNull(ruler, "the founded colony has a ruler");
		assertEquals(Rank.VILLAGE, ruler.rank(), "a ruler commands a VILLAGE");

		// the TOWN -> METROPOLIS head crossing, fired end-of-step (as the tier callback does) so the
		// reformed-away ruler is removed before it would act again on its closed account. Capture the
		// ruler's treasury in a prior end-of-step action — i.e. right before the reform reads it, so
		// the measurement isn't confused by the day's ordinary spending.
		double[] treasuryBefore = { 0 };
		colony.scheduleEndOfStepAction(() -> treasuryBefore[0] = ruler.getWealth());
		colony.scheduleEndOfStepAction(h::reformRulerToMayor);
		colony.newDay();

		Ruler head = colony.getRuler();
		assertInstanceOf(Mayor.class, head, "the reformed head is a Mayor");
		assertEquals(Rank.CITY, head.rank(), "a mayor commands a CITY");
		assertEquals("Mayor", head.role());
		assertNotSame(ruler, head, "the reform produced a new head agent");
		assertEquals(ruler.getHead(), head.getHead(),
				"the same dynasty leads on (the head is carried across)");

		// the treasury is carried 1:1 across the urbanization (money conserved)
		assertEquals(treasuryBefore[0], head.getWealth(), 1e-6,
				"the mayor inherits the ruler's treasury unchanged");

		// the economy keeps running with the Mayor (a Ruler subclass) — no crash, still a mayor
		colony.run(30);
		assertInstanceOf(Mayor.class, colony.getRuler(), "the head stays a Mayor as the economy runs");
	}

	@Test
	void aMayorShrinksBackIntoARulerWithTheTreasuryConserved() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement colony = h.getColony();
		colony.start();
		colony.run(60);

		// urbanize into a metropolis (R2), then verify the symmetric descent (R4) reverts it
		colony.scheduleEndOfStepAction(h::reformRulerToMayor);
		colony.newDay();
		Ruler mayor = colony.getRuler();
		assertInstanceOf(Mayor.class, mayor, "set-up: the head is a Mayor after urbanizing");

		// METROPOLIS -> TOWN descent: reform the Mayor (CITY) back into a plain Ruler (VILLAGE),
		// capturing the treasury right before the reform reads it (a prior end-of-step action)
		double[] treasuryBefore = { 0 };
		colony.scheduleEndOfStepAction(() -> treasuryBefore[0] = mayor.getWealth());
		colony.scheduleEndOfStepAction(h::reformMayorToRuler);
		colony.newDay();

		Ruler head = colony.getRuler();
		assertInstanceOf(Ruler.class, head, "the reformed head is a Ruler");
		assertFalse(head instanceof Mayor, "it is a plain Ruler again, not a Mayor");
		assertEquals(Rank.VILLAGE, head.rank(), "a ruler commands a VILLAGE");
		assertNotSame(mayor, head, "the reform produced a new head agent");
		assertEquals(treasuryBefore[0], head.getWealth(), 1e-6,
				"the treasury is carried 1:1 back down (money conserved)");

		colony.run(30);
		assertNotNull(colony.getRuler(), "the economy keeps running with the demoted Ruler");
	}
}
