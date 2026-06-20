package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Caravan;
import com.civstudio.agent.Retinue;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.util.Rng;

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
		return s.newSettlement("Test Colony", START, 35, 26, 5, 2, 51.5074, -0.1278);
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

	@Test
	void aBandReFoundsAtItsOwnPositionTakingTheNextColonyIndex() {
		GameSession s = new GameSession(2024);
		Settlement origin = newColony(s); // colony 0

		// a wandering band positioned away from the origin (its Retinue is built on
		// the origin colony; re-founding raises a fresh colony, not rebinds this one)
		Bank bank = new Bank(BankConfig.DEFAULT, origin);
		Retinue following = new Retinue(2, bank, origin);
		Caravan band = new Caravan(following.promoteHighestSkilled(), following, 0,
				48.85, 2.35);
		s.addCaravan(band);
		assertEquals(List.of(band), s.getCaravans(), "the band should be tracked");

		// re-founding places the new colony at the band's position...
		Settlement reborn =
				s.newSettlement(band, "New Home", START, 35, 26, 5, 2);
		assertEquals(48.85, reborn.getLatitude(),
				"the re-founded colony sits where the band settled");
		assertEquals(2.35, reborn.getLongitude(),
				"the re-founded colony sits where the band settled");

		// ...and takes the next colony index, so it runs on its own economic stream
		assertNotSame(origin.getRng(), reborn.getRng(),
				"a re-founded colony needs its own economic generator");
		boolean differs = false;
		for (int i = 0; i < 64 && !differs; i++)
			differs = origin.getRng().uniform() != reborn.getRng().uniform();
		assertTrue(differs,
				"the re-founded colony must run on an independent stream");
	}
}
