package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.era.Era;
import com.civstudio.race.Race;

/**
 * {@link SimulationConfig#defaultFor(Era, Race)} — the (era, race) base a scenario builds on
 * (see {@code docs/studio-control-plane-plan.md} §A1).
 *
 * <p>The ordering is what these pin: the economy is the FLOOR, and a scenario's own tweaks sit on
 * top of it. Resolving the race economy later would overwrite deliberate overrides, and several
 * scenarios override economy-derived fields.
 */
class SimulationConfigDefaultForTest {

	@Test
	void theCanonicalDefaultIsTheMedievalHumanCell() {
		assertEquals(SimulationConfig.defaultFor(Era.MEDIEVAL, Race.HUMAN), SimulationConfig.DEFAULT,
				"DEFAULT must stay exactly the human column — every scenario and the server start here");
		assertEquals(SimulationConfig.DEFAULT, SimulationConfig.defaultFor(Race.HUMAN));
	}

	@Test
	void everyRaceGetsTheHumanNumbersUntilItsOwnColumnIsAuthored() {
		// behaviour-neutral today: only (MEDIEVAL, HUMAN) exists, so a race base is the human base
		for (Race race : Race.values())
			assertEquals(SimulationConfig.DEFAULT, SimulationConfig.defaultFor(race),
					race + " should fall back to the human economy, not to nothing");
	}

	@Test
	void aScenariosOwnTweaksLayerOnTopOfTheRaceBase() {
		// the ElvenEconomy shape: economy-derived fields deliberately overridden. These must WIN over
		// the base — resolving the race economy after the fact would silently undo them.
		SimulationConfig cfg = SimulationConfig.defaultFor(Race.ELVEN).toBuilder()
				.settlementName("Aelvar")
				.retinueSize(200)
				.promotionRatio(0.45)
				.build();
		assertEquals("Aelvar", cfg.settlementName());
		assertEquals(200, cfg.retinueSize(), "an explicit pool size must survive the race base");
		assertEquals(0.45, cfg.promotionRatio());
		// untouched economy fields still come from the cell
		assertEquals(SimulationConfig.DEFAULT.laborShare(), cfg.laborShare());
	}

	@Test
	void anUncalibratedEraIsRefusedRatherThanFoundedOnNulls() {
		// INDUSTRIAL has no Economy; building a config from it would hand the colony null tuning
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SimulationConfig.defaultFor(Era.INDUSTRIAL, Race.HUMAN));
		assertTrue(e.getMessage().contains("INDUSTRIAL"), "the message should name the era: " + e.getMessage());
	}
}
