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
 * <p>Since phase 3 the economy itself belongs to the colony, not the run: what these pin is that
 * {@code defaultFor} still resolves the right (era, race) cell, and that a scenario's own tweaks —
 * now made through {@code SimulationHarness.tuneEconomy} — layer on top of that cell per colony
 * rather than across the whole run.
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
	void aScenariosOwnTweaksLayerOnTopOfTheColonysOwnEconomy() {
		// the ElvenEconomy shape, at its post-phase-3 home: the economy belongs to the COLONY, so a
		// scenario's deliberate overrides go through tuneEconomy rather than the run config. They must
		// still WIN over the race base, and must not disturb the fields they don't name.
		SimulationConfig cfg = SimulationConfig.defaultFor(Race.ELVEN).toBuilder()
				.settlementName("Aelvar")
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, 31415927L);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(200).promotionRatio(0.45).build());

		Era.Economy econ = h.getColony().getEconomy();
		assertEquals("Aelvar", cfg.settlementName());
		assertEquals(200, econ.retinueSize(), "an explicit pool size must survive the race base");
		assertEquals(0.45, econ.promotionRatio());
		// untouched economy fields still come from the colony's own cell
		assertEquals(Era.MEDIEVAL.economy(Race.ELVEN).laborShare(), econ.laborShare());
	}

	@Test
	void theEconomyNoLongerRidesTheRunConfig() {
		// the point of phase 3: two colonies in one run can differ economically, which a per-RUN
		// config cannot express. Same cfg, two colonies, two tunings — they must not alias.
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness a = SimulationHarness.create(cfg, 1L);
		SimulationHarness b = SimulationHarness.create(cfg, 2L);
		a.tuneEconomy(e -> e.toBuilder().retinueSize(120).build());
		b.tuneEconomy(e -> e.toBuilder().retinueSize(640).build());

		assertEquals(120, a.getColony().getEconomy().retinueSize());
		assertEquals(640, b.getColony().getEconomy().retinueSize(),
				"tuning one colony must not reach the other — that was the phase-2 bridge's flaw");
	}

	@Test
	void anUncalibratedEraIsRefusedRatherThanFoundedOnNulls() {
		// INDUSTRIAL has no Economy; building a config from it would hand the colony null tuning
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SimulationConfig.defaultFor(Era.INDUSTRIAL, Race.HUMAN));
		assertTrue(e.getMessage().contains("INDUSTRIAL"), "the message should name the era: " + e.getMessage());
	}
}
