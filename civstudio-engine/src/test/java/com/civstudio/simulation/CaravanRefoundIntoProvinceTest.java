package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.MigrantCaravan;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Province-anchored re-founding (caravan-trade Phase A, A3): a band born from a
 * colony founded <b>into a province</b> is {@linkplain MigrantCaravan#onGraph()
 * on-graph}, and re-founds <b>into that province</b> — so the reborn colony inherits
 * the province's coordinates <em>and</em> its {@code plots} cap on settlement size,
 * not just bare coordinates. (An off-graph band re-founding at raw coordinates is
 * covered by {@link CaravanRefoundTest}.)
 */
class CaravanRefoundIntoProvinceTest {

	// the default founding province: Dhenijansar (a small coastal LAND province whose
	// plots cap the settlement well below the province-less plot ceiling)
	private static final int DHENIJANSAR = 4411;

	@Test
	void anOnGraphBandReFoundsIntoItsProvince() {
		// short, pre-collapse horizon: dissolve manually after a brief run
		SimulationConfig cfg =
				SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();

		SimulationHarness h0 = SimulationHarness.create(cfg, 24680, DHENIJANSAR);
		h0.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		Settlement origin = h0.getColony();
		assertNotNull(origin.getProvince(),
				"the origin colony was founded into a province");
		h0.run();

		MigrantCaravan band = MigrantCaravan.dissolve(origin);
		assertTrue(band.onGraph(),
				"a band from a province-founded colony is anchored on the graph");
		assertEquals(origin.getProvince().id(), band.getProvinceId(),
				"the band stands in the province its colony occupied");

		// the band re-founds into the province it is standing in (not bare coords)
		GameSession session = origin.getSession();
		Settlement reborn = session.newSettlement(band, "New Dhenijansar",
				cfg.startDate(), cfg.meanInitAgeYears(), cfg.targetNStock(),
				cfg.meanSkillMale(), cfg.meanSkillFemale());

		assertNotNull(reborn.getProvince(),
				"the re-founded colony is seated in a province");
		assertEquals(origin.getProvince().id(), reborn.getProvince().id(),
				"the band re-founds into the province it is standing in");
		assertEquals(origin.getMaxPlots(), reborn.getMaxPlots(),
				"the re-founded colony inherits the province's plots cap");
		assertEquals(origin.getProvince().latitude(), reborn.getLatitude(),
				"the re-founded colony takes the province's latitude");
	}
}
