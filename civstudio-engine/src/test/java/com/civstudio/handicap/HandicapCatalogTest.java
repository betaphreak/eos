package com.civstudio.handicap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The committed handicap catalog ({@code /handicaps.json}, baked from {@code CIV4HandicapInfo.xml} by
 * {@link com.civstudio.handicap.export.HandicapInfoExporter}) loads, and difficulty keys resolve the
 * way {@code SessionController} relies on. See {@code docs/session-management.md}.
 */
class HandicapCatalogTest {

	@Test
	void theCommittedCatalogLoadsTheCiv4Ladder() {
		HandicapCatalog cat = HandicapCatalog.DEFAULT;
		// the classic Civ4 rungs are all present (C2C adds a few above Deity)
		for (String key : new String[] { "settler", "chieftain", "noble", "prince", "monarch",
				"emperor", "immortal", "deity" })
			assertTrue(cat.has(key), "missing handicap: " + key);
		assertEquals("noble", cat.defaultKey(), "Noble is Civ4's balanced default");
		assertFalse(cat.all().isEmpty());
		// the scaling table is carried (for the multipliers a later feature applies)
		assertTrue(cat.byKey("noble").orElseThrow().modifiers().containsKey("iGold"));
	}

	@Test
	void resolveIsTolerantOfCaseAndPrefixAndRejectsTheUnknown() {
		HandicapCatalog cat = HandicapCatalog.DEFAULT;
		assertEquals("noble", cat.resolve("noble"));
		assertEquals("noble", cat.resolve("Noble"));
		assertEquals("noble", cat.resolve("HANDICAP_NOBLE"));
		assertEquals("deity", cat.resolve("DEITY"));
		assertNull(cat.resolve(null), "no difficulty means the standard rung, not an error");
		assertNull(cat.resolve("  "), "blank is the same as absent");
		assertThrows(IllegalArgumentException.class, () -> cat.resolve("impossible"),
				"an unknown difficulty is rejected, not silently accepted");
	}
}
