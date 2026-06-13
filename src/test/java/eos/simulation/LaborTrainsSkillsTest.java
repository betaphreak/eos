package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.laborer.Laborer;
import eos.skill.Skill;
import eos.skill.SkillTracker;

/**
 * Verifies that performing labor trains the worker's skills. After a full run,
 * the laborers who staff the necessity firms (subsistence agriculture) have
 * accumulated {@link Skill#PLANTS} experience, while a skill no firm in the run
 * trains ({@link Skill#MEDICINE}) stays near its birth level — confirming the
 * {@link eos.market.LaborMarket} grants per-skill XP for the labor performed.
 */
class LaborTrainsSkillsTest {

	@Test
	void laborTrainsTheFirmsSkillButNotUntrainedSkills() {
		SimulationHarness h = assertDoesNotThrow(HomogeneousEconomy::run);

		double plants = 0, medicine = 0;
		int n = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer laborer) {
				SkillTracker skills = laborer.getHead().skills();
				plants += skills.getSkill(Skill.PLANTS).getLevel();
				medicine += skills.getSkill(Skill.MEDICINE).getLevel();
				n++;
			}

		assertTrue(n > 0, "expected living laborers");
		// PLANTS is trained by the necessity firms the laborers staff; MEDICINE is
		// trained by no firm in this run, so it stays at its birth level
		assertTrue(plants > medicine,
				"expected trained PLANTS (mean " + plants / n
						+ ") to exceed untrained MEDICINE (mean " + medicine / n + ")");
	}
}
