package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link UnitBundle#iconRect} — the per-unit icon-rect lookup the render layer stamps onto a band's
 * embodied unit so the live map can blit its button art. Backed by the committed {@code units-meta.json}.
 */
class UnitBundleTest {

	@Test
	void iconRectReturnsAFourIntRectForABakedUnit() {
		// UNIT_GATHERER resolves a real C2C button, so it has a baked icon cell
		int[] rect = UnitBundle.iconRect("UNIT_GATHERER");
		assertNotNull(rect, "a unit with baked art has an icon rect");
		assertEquals(4, rect.length, "the rect is [x,y,w,h]");
		assertEquals(64, rect[2], "cells are 64 wide");
		assertEquals(64, rect[3], "cells are 64 tall");
		assertTrue(rect[0] >= 0 && rect[1] >= 0, "non-negative sheet offset");
	}

	@Test
	void iconRectIsNullForAnUnknownOrMissingUnit() {
		assertNull(UnitBundle.iconRect("UNIT_DEFINITELY_NOT_A_REAL_ID"), "unknown id → no rect");
		assertNull(UnitBundle.iconRect(null), "null id → no rect");
	}
}
