package com.civstudio.era;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.civstudio.race.Race;

/**
 * The era × race economy matrix (see {@code docs/studio-control-plane-plan.md} §A). Economy is
 * authored on two axes — an era sets the epoch, a race sets who lives through it — and only the
 * human column exists today, so every race must fall back to it rather than get nothing.
 */
class EraEconomyTest {

	@Test
	void everyRaceFallsBackToTheHumanColumnUntilItsOwnIsAuthored() {
		Era.Economy human = Era.MEDIEVAL.economy(Race.HUMAN);
		assertNotNull(human, "MEDIEVAL is the calibrated era");
		for (Race race : Race.values())
			assertSame(human, Era.MEDIEVAL.economy(race),
					race + " has no authored economy yet, so it founds on the human one");
	}

	@Test
	void theEraWideAccessorIsTheHumanColumn() {
		// the no-arg accessor predates race being a lever; it must keep meaning exactly "human"
		assertSame(Era.MEDIEVAL.economy(Race.HUMAN), Era.MEDIEVAL.economy());
	}

	@Test
	void anUncalibratedEraHasNoEconomyForAnyRace() {
		// a race fallback must not invent numbers for an era nobody has tuned
		assertNull(Era.INDUSTRIAL.economy(), "INDUSTRIAL is beyond the modeled span");
		assertNull(Era.INDUSTRIAL.economy(Race.ELVEN));
	}

	@Test
	void aNullRaceReadsAsHuman() {
		assertSame(Era.MEDIEVAL.economy(Race.HUMAN), Era.MEDIEVAL.economy(null));
	}
}
