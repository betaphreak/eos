package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.civstudio.era.Era;
import com.civstudio.race.Race;

/**
 * A colony carries its <b>own</b> economy, resolved from the {@code (era, race)} cell for whoever
 * founded it (see {@code docs/studio-control-plane-plan.md} §A1).
 *
 * <p>Per colony, not per run: a session can seat colonies of different races — a Timeline picks a
 * different province per seat, and the province's culture decides who lives there — so reading the
 * numbers off a run-level config gave every seat the human economy regardless of its founders.
 */
class ColonyEconomyTest {

	private static final int RUBYHOLD = 62;      // dwarven
	private static final int DHENIJANSAR = 4411; // no race authored -> human

	@Test
	void aColonyResolvesTheCellForItsOwnFoundingRace() {
		GameSession session = new GameSession(7654321L);
		Settlement dwarven = session.newSettlement("Rubyhold", java.time.LocalDate.of(1444, 12, 11),
				35, 26, 7, 5, session.getWorldMap().province(RUBYHOLD));

		assertSame(Race.DWARVEN, dwarven.getFoundingRace());
		assertNotNull(dwarven.getEconomy(), "a founded colony always has numbers to run on");
		assertSame(Era.MEDIEVAL.economy(Race.DWARVEN), dwarven.getEconomy());
	}

	@Test
	void twoColoniesOfDifferentRacesInOneSessionEachGetTheirOwn() {
		// the case a run-level config could not express, and the reason this moved onto the colony
		GameSession session = new GameSession(7654321L);
		Settlement dwarven = session.newSettlement("Rubyhold", java.time.LocalDate.of(1444, 12, 11),
				35, 26, 7, 5, session.getWorldMap().province(RUBYHOLD));
		Settlement human = session.newSettlement("Dhenijansar", java.time.LocalDate.of(1444, 12, 11),
				35, 26, 7, 5, session.getWorldMap().province(DHENIJANSAR));

		assertSame(Race.DWARVEN, dwarven.getFoundingRace());
		assertSame(Race.HUMAN, human.getFoundingRace());
		// today both cells resolve to the human column (no dwarven economy is authored yet), so this
		// asserts the WIRING — each colony asks for its own race — not a difference in the numbers
		assertSame(Era.MEDIEVAL.economy(Race.DWARVEN), dwarven.getEconomy());
		assertSame(Era.MEDIEVAL.economy(Race.HUMAN), human.getEconomy());
	}

	@Test
	void aScenarioCanOverrideTheNumbersOutLoud() {
		// option (a): one value, one home. A scenario that wants different numbers says so, rather
		// than a run-level config carrying fields and the engine guessing which were deliberate.
		GameSession session = new GameSession(7654321L);
		Settlement c = session.newSettlement("Aelvar", java.time.LocalDate.of(1444, 12, 11),
				35, 26, 7, 5, session.getWorldMap().province(DHENIJANSAR));

		Era.Economy tuned = c.getEconomy().toBuilder().retinueSize(200).promotionRatio(0.45).build();
		c.setEconomy(tuned);

		assertEquals(200, c.getEconomy().retinueSize());
		assertEquals(0.45, c.getEconomy().promotionRatio());
		assertNotSame(Era.MEDIEVAL.economy(Race.HUMAN), c.getEconomy());
		// untouched fields still come from the cell it started on
		assertEquals(Era.MEDIEVAL.economy(Race.HUMAN).laborShare(), c.getEconomy().laborShare());
	}

	@Test
	void anOverrideMustBeRealRatherThanNull() {
		GameSession session = new GameSession(7654321L);
		Settlement c = session.newSettlement("Dhenijansar", java.time.LocalDate.of(1444, 12, 11),
				35, 26, 7, 5, session.getWorldMap().province(DHENIJANSAR));
		assertThrows(NullPointerException.class, () -> c.setEconomy(null));
	}
}
