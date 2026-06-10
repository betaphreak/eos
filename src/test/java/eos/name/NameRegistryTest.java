package eos.name;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import eos.util.Rng;

/**
 * Verifies the name subsystem: heads get a non-blank male given name and a
 * dynasty surname, surnames are unique within a session (enough for the default
 * 450 laborers), and naming is reproducible for a given seed.
 */
class NameRegistryTest {

	@Test
	void headsAreNamedAndDynastySurnamesAreUnique() {
		NameRegistry names = new NameRegistry(new Rng(42));
		Set<String> surnames = new HashSet<>();
		for (int i = 0; i < 450; i++) {
			Person head = names.nextHead();
			assertNotNull(head.givenName());
			assertFalse(head.givenName().isBlank(), "blank given name");
			assertFalse(head.surname().isBlank(), "blank surname");
			assertTrue(surnames.add(head.surname()),
					"duplicate surname: " + head.surname());
		}
	}

	@Test
	void sameSeedYieldsSameNames() {
		NameRegistry a = new NameRegistry(new Rng(7));
		NameRegistry b = new NameRegistry(new Rng(7));
		for (int i = 0; i < 200; i++)
			assertEquals(a.nextHead().fullName(), b.nextHead().fullName());
	}

	@Test
	void maleGivenNamesMayRepeat() {
		// with replacement, so over many draws at least one repeats
		NameRegistry names = new NameRegistry(new Rng(1));
		Set<String> seen = new HashSet<>();
		boolean repeated = false;
		for (int i = 0; i < 500 && !repeated; i++)
			repeated = !seen.add(names.nextMaleName());
		assertTrue(repeated, "expected a repeated male given name");
	}
}
