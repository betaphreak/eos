package com.civstudio.good;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The band's per-good {@link Cargo} inventory (see {@code docs/manufactured-bonuses.md},
 * <i>Caravans carry, forage, and trade</i>): whole-unit quantities (discrete goods — no
 * fractional elephants) conserved across {@link Cargo#add add} and {@link Cargo#draw
 * draw}, draws that never go negative, and a compact {@link Cargo#manifest manifest}
 * label for the march journal.
 */
class CargoTest {

	@Test
	void addAccumulatesAndTotals() {
		Cargo cargo = new Cargo();
		assertTrue(cargo.isEmpty());
		cargo.add("BONUS_TIN_ORE", 2);
		cargo.add("BONUS_SPICES", 3);
		cargo.add("BONUS_TIN_ORE", 1);
		assertEquals(3, cargo.quantity("BONUS_TIN_ORE"));
		assertEquals(3, cargo.quantity("BONUS_SPICES"));
		assertEquals(6, cargo.total());
		assertFalse(cargo.isEmpty());
		// insertion order preserved (first-gathered first)
		assertEquals(List.of("BONUS_TIN_ORE", "BONUS_SPICES"),
				List.copyOf(cargo.goods().keySet()));
	}

	@Test
	void drawNeverOverdrawsAndRemovesEmptiedGoods() {
		Cargo cargo = new Cargo();
		cargo.add("BONUS_JADE", 3);
		assertEquals(1, cargo.draw("BONUS_JADE", 1));
		assertEquals(2, cargo.quantity("BONUS_JADE"));
		// a draw beyond the held quantity returns only what is there
		assertEquals(2, cargo.draw("BONUS_JADE", 5));
		assertEquals(0, cargo.quantity("BONUS_JADE"));
		assertTrue(cargo.isEmpty(), "a good drawn to nothing is no longer carried");
		// drawing a good never carried yields nothing
		assertEquals(0, cargo.draw("BONUS_SILK", 1));
	}

	@Test
	void negativeQuantitiesAreRejected() {
		Cargo cargo = new Cargo();
		assertThrows(IllegalArgumentException.class, () -> cargo.add("BONUS_TIN_ORE", -1));
		assertThrows(IllegalArgumentException.class, () -> cargo.draw("BONUS_TIN_ORE", -1));
	}

	@Test
	void manifestListsByQuantityWithShortNames() {
		Cargo cargo = new Cargo();
		assertEquals("-", cargo.manifest(5), "an empty cargo has no manifest");
		cargo.add("BONUS_TIN_ORE", 1);
		cargo.add("BONUS_SAPPHIRES", 12);
		cargo.add("BONUS_SPICES", 4);
		assertEquals("sapphires 12; spices 4; tin_ore 1", cargo.manifest(5));
		// beyond the limit, the rest folds into a count
		assertEquals("sapphires 12 (+2 more)", cargo.manifest(1));
	}
}
