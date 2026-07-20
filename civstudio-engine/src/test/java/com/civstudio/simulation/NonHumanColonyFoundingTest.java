package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import com.civstudio.agent.laborer.Laborer;
import com.civstudio.race.Race;
import com.civstudio.settlement.Settlement;

/**
 * A full-size colony founds in a non-human province — the case that was impossible until the
 * surname pool learned to repeat itself.
 *
 * <p>A settlement takes its race from the province it stands in
 * ({@code WorldMap#raceOf}), and a standard colony needs ~405 households
 * ({@code retinueSize 900 × promotionRatio 0.45}). Only {@link Race#HUMAN} has hand-authored surname
 * tables; the rest are imported from Anbennar's {@code anb_cultures.txt}, a couple of hundred names.
 * While the pool refused to repeat a surname, founding anywhere non-human died partway through on
 * "dynasty master pool exhausted" — so this test is the reason {@code DynastyPool} wraps.
 */
class NonHumanColonyFoundingTest {

	private static final int RUBYHOLD = 62;      // culture ruby_dwarf -> group dwarven
	private static final int EARGATE = 2;        // culture east_damerian -> group anbennarian
	private static final int DHENIJANSAR = 4411; // group south_raheni -> no race authored -> HUMAN

	private static Settlement standardColony(int provinceId) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, provinceId);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement c = h.getColony();
		c.start();
		return c;
	}

	@Test
	@Timeout(180)
	void aDwarvenProvinceFoundsADwarvenColonyAtFullSize() {
		Settlement c = standardColony(RUBYHOLD);
		assertSame(Race.DWARVEN, c.getFoundingRace(), "Rubyhold is dwarven — the map says so");
		// the point: it got all the way through founding, which is where it used to die
		assertTrue(c.householdCount() > 200,
				"a standard colony should found hundreds of households, got " + c.householdCount());
	}

	@Test
	@Timeout(180)
	void anAnbennarianProvinceAlsoFoundsAtFullSize() {
		// Eargate is what SettlementGrowthTest and friends found into; it is anbennarian, which IS an
		// authored race, and it is what first exposed the exhausted pool
		Settlement c = standardColony(EARGATE);
		assertSame(Race.ANBENNARIAN, c.getFoundingRace());
		assertTrue(c.householdCount() > 200);
	}

	@Test
	@Timeout(180)
	void aProvinceWithNoAuthoredRaceStillFoundsHuman() {
		// the default site, and the assertion that every pre-existing scenario is unaffected
		Settlement c = standardColony(DHENIJANSAR);
		assertSame(Race.HUMAN, c.getFoundingRace());
	}

	@Test
	@Timeout(180)
	void householdsShareSurnamesRatherThanRunOut() {
		// 400 medieval households do not hold 400 distinct surnames; the small authored list is
		// spread across them instead of the colony failing to exist
		Settlement c = standardColony(RUBYHOLD);
		List<String> surnames = c.getAgents().stream()
				.filter(Laborer.class::isInstance)
				.map(a -> ((Laborer) a).getHead().surname())
				.filter(s -> s != null)
				.toList();
		assertTrue(surnames.size() > 200, "expected a full colony, got " + surnames.size());
		long distinct = surnames.stream().distinct().count();
		assertTrue(distinct > 1, "a colony should use more than one surname, got " + distinct);
		assertTrue(distinct < surnames.size(),
				"with a short authored list, surnames must repeat across households: "
						+ distinct + " surnames for " + surnames.size() + " households");
	}
}
