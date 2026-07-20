package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.Settlement;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;
import com.civstudio.skill.Skill;
import com.civstudio.tech.TechEffect;

/**
 * An explorer levy <b>embodies</b> the best explorer unit its colony can field (identity/art overlay
 * only, docs/c2c-unit-import.md §Phase 5). Behaviour-neutral: the embodiment feeds the band's display
 * name / art / signature-skill readout, never the march — so it changes no run, only what the band
 * shows. This asserts the muster-time pick + the accessors the render snapshot reads.
 */
class ExplorerEmbodimentTest {

	private static final int DHENIJANSAR = 4411;

	private static SimulationHarness colony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony();
		return h;
	}

	private static ExplorerCaravan levy(SimulationHarness h) {
		Settlement colony = h.getColony();
		LocalDate today = colony.getDate();
		List<Member> draftees = new ArrayList<>();
		for (Member m : h.getRetinue().getMembers())
			if (m.isAdult(today)) {
				draftees.add(m);
				if (draftees.size() == 5)
					break;
			}
		return ExplorerCaravan.muster(colony,
				draftees, draftees.size() * MarchingCaravan.WANDERING_RATION.perDay() * 200);
	}

	@Test
	void aBandEmbodiesTheGrantedExplorerUnit() {
		SimulationHarness h = colony();
		Settlement colony = h.getColony();
		// pick an explorer unit the colony would NOT already have obsoleted (a Classical-complete
		// colony knows the early obsoleting techs, so the earliest explorers are already obsolete —
		// pickBest correctly skips those); grant its unlock token, as researching its prereq would
		var known = colony.getResearch().getKnown();
		UnitInfo explorer = UnitCatalog.get().forRole(CaravanRole.EXPLORER).stream()
				.filter(u -> u.obsoleteTech() == null || !known.contains(u.obsoleteTech()))
				.findFirst().orElseThrow(() -> new AssertionError("no non-obsolete explorer unit"));
		colony.applyTechEffect(new TechEffect.Unlock(explorer.id()));

		ExplorerCaravan band = levy(h);
		assertEquals(explorer.id(), band.getUnitId(), "the band embodies the granted explorer unit");
		assertNotNull(band.getUnitName(), "the embodied unit gives the band a display name");
		assertEquals(Skill.SURVIVAL, band.signatureSkill(), "the explorer role trains SURVIVAL");
		assertTrue(band.leaderSkillLevel() >= 0, "the leader has a (non-negative) SURVIVAL level");
	}

	@Test
	void aBandWithNoUnlockedUnitEmbodiesNothing() {
		SimulationHarness h = colony();
		// no explorer unit token granted → the colony can field none, so the band keeps its default
		boolean anyExplorerUnlocked = UnitCatalog.get().forRole(CaravanRole.EXPLORER).stream()
				.anyMatch(u -> h.getColony().getGrantedTechTokens().contains(u.id()));
		ExplorerCaravan band = levy(h);
		if (anyExplorerUnlocked)
			assertNotNull(band.getUnitId(), "an unlocked explorer unit would be embodied");
		else
			assertNull(band.getUnitId(),
					"with no unlocked explorer unit the band keeps its default identity");
	}
}
