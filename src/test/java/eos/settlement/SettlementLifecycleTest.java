package eos.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import eos.util.Rng;

/**
 * Verifies the colony lifecycle on {@link Settlement}: a colony is neither alive
 * nor dead until it {@link Settlement#start() starts}, and a started colony with
 * no laborers left dies (terminally) when its lifecycle is updated, recording
 * the death date.
 */
class SettlementLifecycleTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private Settlement bareColony() {
		// no name/demography services needed for the lifecycle state machine
		return new Settlement("Test Colony", START, new Rng(1L), null, null, 35, 26,
				5, 51.5074, -0.1278);
	}

	@Test
	void unstartedColonyIsNeitherAliveNorDead() {
		Settlement colony = bareColony();
		assertFalse(colony.isAlive(), "an unstarted colony is not alive");
		assertFalse(colony.isDead(), "an unstarted colony has not died");
	}

	@Test
	void startedColonyIsAlive() {
		Settlement colony = bareColony();
		colony.start();
		assertTrue(colony.isAlive());
		assertFalse(colony.isDead());
	}

	@Test
	void startedColonyDiesWhenItHasNoLaborers() {
		Settlement colony = bareColony();
		colony.start();
		colony.updateLifecycle(); // no laborers were ever added -> the colony dies
		assertTrue(colony.isDead(), "a started colony with no laborers should die");
		assertFalse(colony.isAlive());
		assertEquals(START, colony.getDeathDate(), "death recorded on step-0 date");
	}

	@Test
	void anUnstartedColonyDoesNotDie() {
		Settlement colony = bareColony();
		colony.updateLifecycle(); // never started, so cannot die
		assertFalse(colony.isDead());
	}
}
