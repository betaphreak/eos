package eos.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import eos.util.Rng;

/**
 * Verifies how a {@link GameSession} provisions the colonies it creates: each
 * gets its own independent economic generator (so several colonies in one
 * session don't interleave their random streams), the first colony stays
 * byte-identical to a single shared generator, and the name pool is shared so
 * dynasty surnames are unique across every colony in the session.
 */
class GameSessionTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private Settlement newColony(GameSession s) {
		return s.newSettlement(START, 35, 26, 5);
	}

	@Test
	void firstColonyUsesTheBareSeed() {
		// guards byte-identical single-colony runs: colony 0's economic stream
		// must match a generator seeded with the plain session seed
		GameSession s = new GameSession(999);
		Settlement first = newColony(s);
		Rng reference = new Rng(999);
		for (int i = 0; i < 32; i++)
			assertEquals(reference.uniform(), first.getRng().uniform(),
					"colony 0 should draw the bare-seed sequence");
	}

	@Test
	void coloniesGetIndependentEconomicStreams() {
		GameSession s = new GameSession(123);
		Settlement a = newColony(s);
		Settlement b = newColony(s);
		assertNotSame(a.getRng(), b.getRng(), "each colony needs its own Rng");

		// the two streams should not be the same sequence
		boolean differs = false;
		for (int i = 0; i < 64 && !differs; i++)
			differs = a.getRng().uniform() != b.getRng().uniform();
		assertTrue(differs, "colonies must run on independent economic streams");
	}

	@Test
	void surnamesAreUniqueAcrossColoniesInOneSession() {
		GameSession s = new GameSession(42);
		Settlement a = newColony(s);
		Settlement b = newColony(s);
		Set<String> surnames = new HashSet<>();
		for (int i = 0; i < 200; i++)
			assertTrue(surnames.add(a.getNames().nextHead().surname()),
					"duplicate surname within colony A");
		for (int i = 0; i < 200; i++)
			assertTrue(surnames.add(b.getNames().nextHead().surname()),
					"surname in colony B collided with one already in use");
	}
}
