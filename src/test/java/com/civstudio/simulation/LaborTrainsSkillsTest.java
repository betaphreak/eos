package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.civstudio.good.RationSize;
import com.civstudio.market.LaborMarket;
import com.civstudio.market.WeddingConfig;
import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.bank.Bank;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillTracker;

/**
 * Verifies that performing labor trains the worker's skills. After a few years,
 * the laborers who staff the necessity firms (subsistence agriculture) have
 * accumulated {@link Skill#PLANTS} experience, while a skill no firm in the run
 * trains ({@link Skill#MEDICINE}) stays near its birth level — confirming the
 * {@link LaborMarket} grants per-skill XP for the labor performed.
 * <p>
 * Runs a {@link HomogeneousEconomy}-style <b>pool colony</b> over a <b>short
 * horizon</b> (so it is inspected while its labor force is still alive, before the
 * finite pool drains and the colony collapses) and made deliberately
 * <b>necessity-heavy</b> (few enjoyment firms, many necessity firms) so most
 * laborers farm. Both choices serve the same end: at the modest {@link
 * RationSize#FINE} ration a worker eats, necessity is otherwise too small a
 * sector to move the population's {@code PLANTS} above the noise of an untrained
 * skill — concentrating labor on farming makes the training signal clear. (The
 * production default firm mix is unchanged; this colony is configured only to
 * exercise the training mechanism.)
 */
class LaborTrainsSkillsTest {

	@Test
	void laborTrainsTheFirmsSkillButNotUntrainedSkills() {
		SimulationHarness h = assertDoesNotThrow(LaborTrainsSkillsTest::runShort);

		double plants = 0, medicine = 0;
		int n = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer laborer) {
				SkillTracker skills = laborer.getHead().skills();
				plants += skills.getSkill(Skill.PLANTS).getLevel();
				medicine += skills.getSkill(Skill.MEDICINE).getLevel();
				n++;
			}

		assertTrue(n > 0, "expected living laborers (the short run ends before collapse)");
		// PLANTS is trained by the necessity firms the laborers staff; MEDICINE is
		// trained by no firm in this run, so it stays at its birth level
		assertTrue(plants > medicine,
				"expected trained PLANTS (mean " + plants / n
						+ ") to exceed untrained MEDICINE (mean " + medicine / n + ")");
	}

	/**
	 * Build the {@link HomogeneousEconomy} pool colony over a short horizon — long
	 * enough to train skills, short enough that the labor force is still alive (the
	 * pool has not yet drained) — with no printers.
	 */
	private static SimulationHarness runShort() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(8).numEFirms(1).numNFirms(30).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		// weddings are orthogonal to skill training and only add noise here (female
		// ex-spouses becoming heads via widowhood with short training histories), so
		// disable them to isolate the training mechanism this test measures
		h.setWeddingConfig(WeddingConfig.DEFAULT.toBuilder()
				.capacity(0).build());
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.run();
		return h;
	}
}
