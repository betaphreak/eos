package com.civstudio.name;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.util.Rng;

/**
 * A surname pool must outlast the colony drawing from it.
 *
 * <p>Only {@link com.civstudio.race.Race#HUMAN} has hand-authored surname tables (151k names across
 * 822 tiers). Every other race is imported from Anbennar's {@code anb_cultures.txt} — a couple of
 * hundred surnames — while a standard colony needs ~405 households
 * ({@code retinueSize 900 × promotionRatio 0.45}). Refusing to repeat a surname meant such a colony
 * died mid-founding on "dynasty master pool exhausted"; repeating is both the fix and the realistic
 * outcome, since four hundred medieval households do not hold four hundred distinct surnames.
 */
class DynastyPoolWrapTest {

	// a tiny table standing in for a race with a short authored surname list, written through the
	// REAL loader so the test exercises the same parse path the engine uses
	private static NameTable table(int n) {
		StringBuilder json = new StringBuilder("[{\"percent\":0,\"names\":[],\"size\":0,\"prev\":null},");
		json.append("{\"percent\":100,\"names\":[");
		for (int i = 0; i < n; i++)
			json.append(i > 0 ? "," : "").append('"').append("Name").append(i).append('"');
		json.append("],\"size\":").append(n).append(",\"prev\":0}]");
		try {
			Path f = Files.createTempFile("dynasty-test", ".json");
			f.toFile().deleteOnExit();
			Files.writeString(f, json.toString());
			return NameTable.load(f);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	void aSmallPoolKeepsDealingInsteadOfFailing() {
		DynastyPool pool = new DynastyPool(table(10), new Rng(42));
		assertEquals(10, pool.capacity());

		// far more surnames than the pool holds — the standard-colony-on-a-small-race case
		int dealt = 0;
		for (int i = 0; i < 12; i++)
			dealt += pool.deal(4).names().length;
		assertTrue(dealt >= 40, "the pool must keep dealing past its own size, got " + dealt);
		assertTrue(pool.passes() > 0, "it can only have done that by wrapping");
	}

	@Test
	void aSliceNeverRepeatsASurnameWithinItself() {
		// repetition is allowed ACROSS slices, never inside one — two households in the same slice
		// drawing the identical name would be the pool double-counting, not a shared family name
		DynastyPool pool = new DynastyPool(table(10), new Rng(7));
		for (int i = 0; i < 5; i++) {
			DynastySlice s = pool.deal(4096); // ask for far more than exists
			Set<String> unique = new HashSet<>(Set.of(s.names()));
			assertEquals(s.names().length, unique.size(), "slice " + i + " repeated a surname");
		}
	}

	@Test
	void theFirstFullPassIsStillDisjoint() {
		// the cross-colony uniqueness guarantee must survive for as long as the pool can honour it
		DynastyPool pool = new DynastyPool(table(12), new Rng(3));
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 3; i++)
			for (String n : pool.deal(4).names())
				assertTrue(seen.add(n), n + " was dealt twice inside the first pass");
		assertEquals(0, pool.passes(), "12 names dealt in 4s is exactly one pass");
	}

	@Test
	void anEmptyPoolIsStillAnError() {
		// wrapping cannot conjure names from nothing — that is a broken table, not a small one
		DynastyPool empty = new DynastyPool(table(0), new Rng(1));
		assertThrows(IllegalStateException.class, () -> empty.deal(1));
	}

	@Test
	void aColonyLargerThanItsPoolNamesEveryHouseholdWithRepeats() {
		// the end-to-end shape: draw more surnames than exist, and every draw must yield a name
		DynastyPool pool = new DynastyPool(table(20), new Rng(11));
		NameRegistry reg = new NameRegistry(NameTable.load("/human-names/male.json"),
				NameTable.load("/human-names/female.json"), pool, pool.deal(20), 20, new Rng(5));
		Set<String> distinct = new HashSet<>();
		for (int i = 0; i < 60; i++) {
			String surname = reg.nextDynastyName();
			assertNotNull(surname, "draw " + i + " must yield a surname");
			distinct.add(surname);
		}
		assertTrue(distinct.size() <= 20, "a 20-name pool cannot yield more than 20 distinct surnames");
		assertTrue(distinct.size() > 1, "and it should still use more than one");
	}
}
