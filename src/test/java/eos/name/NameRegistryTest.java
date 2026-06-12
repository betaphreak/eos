package eos.name;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

	@Test
	void releasedSurnameReentersThePool() {
		NameRegistry names = new NameRegistry(new Rng(5));
		// drain the whole dynasty pool
		List<String> drawn = new ArrayList<>();
		assertThrows(IllegalStateException.class, () -> {
			while (true)
				drawn.add(names.nextDynastyName());
		});
		// release exactly one surname; it must be the only one drawable now
		String recycled = drawn.get(drawn.size() / 2);
		names.releaseDynastyName(recycled);
		assertEquals(recycled, names.nextDynastyName(),
				"the recycled surname should be the only one back in the pool");
		// and the pool is exhausted again
		assertThrows(IllegalStateException.class, names::nextDynastyName);
	}

	@Test
	void releasingASurnameNotInUseThrows() {
		NameRegistry names = new NameRegistry(new Rng(9));
		String s = names.nextDynastyName();
		names.releaseDynastyName(s); // ok: it was in use
		// double release: it is no longer in use
		assertThrows(IllegalStateException.class,
				() -> names.releaseDynastyName(s));
		// a surname this registry never handed out
		assertThrows(IllegalStateException.class,
				() -> names.releaseDynastyName("NotARealSurnameXYZ"));
	}

	@Test
	void recyclingKeepsLivingSurnamesUnique() {
		// model a turnover: hold a working set of "living" surnames, and each
		// step retire one (release) and found one (draw); no collision ever.
		NameRegistry names = new NameRegistry(new Rng(3));
		List<String> living = new ArrayList<>();
		for (int i = 0; i < 50; i++)
			living.add(names.nextDynastyName());
		for (int step = 0; step < 1000; step++) {
			String retired = living.remove(step % living.size());
			names.releaseDynastyName(retired);
			String founded = names.nextDynastyName();
			assertFalse(living.contains(founded),
					"a recycled surname collided with a living one");
			living.add(founded);
		}
		assertEquals(50, new HashSet<>(living).size(),
				"living surnames stayed unique across turnover");
	}
}
