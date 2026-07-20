package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.civstudio.market.LaborMarket;
import com.civstudio.market.WeddingConfig;
import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillTracker;

/**
 * Verifies that performing labor trains the worker's skills. After a few years,
 * the laborers who staff the necessity firms (subsistence agriculture) have
 * accumulated {@link Skill#SURVIVAL} experience, while a skill no firm in the run
 * trains ({@link Skill#MEDICINE}) stays near its birth level — confirming the
 * {@link LaborMarket} grants per-skill XP for the labor performed.
 * <p>
 * Runs a {@link HomogeneousEconomy}-style <b>pool colony</b> configured so the
 * training signal dominates the population mean rather than riding a thin margin:
 * <b>all-necessity</b> ({@code numEFirms(0)}, many necessity firms) so every
 * employed laborer farms and trains {@code SURVIVAL}, a <b>small labor force</b>
 * (low {@code promotionRatio}) the necessity sector can <b>fully absorb</b> (an
 * unemployed laborer trains nothing and would dilute the mean toward its birth
 * draw), and a <b>wide reserve</b> ({@code retinueSize(300)}) so replacement churn
 * over the horizon stays small. Together these make the trained {@code SURVIVAL}
 * mean clear an untrained skill's birth-draw mean — a comparison that is otherwise
 * confounded by per-skill birth draws (the skill enum re-index of
 * {@code docs/c2c-unit-import.md} permuted them, exposing how thin the old margin
 * was). The production default firm mix is unchanged; this colony is configured
 * only to exercise the training mechanism.
 */
class LaborTrainsSkillsTest {

	@Test
	void laborTrainsTheFirmsSkillButNotUntrainedSkills() {
		SimulationHarness h = assertDoesNotThrow(LaborTrainsSkillsTest::runShort);

		double survival = 0, medicine = 0;
		int n = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer laborer) {
				SkillTracker skills = laborer.getHead().skills();
				survival += skills.getSkill(Skill.SURVIVAL).getLevel();
				medicine += skills.getSkill(Skill.MEDICINE).getLevel();
				n++;
			}

		assertTrue(n > 0, "expected living laborers (the short run ends before collapse)");
		// SURVIVAL is trained by the necessity firms the laborers staff; MEDICINE is
		// trained by no firm in this run, so it stays at its birth level
		assertTrue(survival > medicine,
				"expected trained SURVIVAL (mean " + survival / n
						+ ") to exceed untrained MEDICINE (mean " + medicine / n + ")");
	}

	/**
	 * Build the {@link HomogeneousEconomy} pool colony over a short horizon — long
	 * enough to train skills, short enough that the labor force is still alive (the
	 * pool has not yet drained) — with no printers.
	 */
	private static SimulationHarness runShort() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(10).numEFirms(0).numNFirms(40).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.tuneEconomy(e -> e.toBuilder()
				.retinueSize(300).promotionRatio(0.15).build());
		Era.Economy econ = h.getColony().getEconomy();
		// weddings are orthogonal to skill training and only add noise here (female
		// ex-spouses becoming heads via widowhood with short training histories), so
		// disable them to isolate the training mechanism this test measures
		h.setWeddingConfig(WeddingConfig.DEFAULT.toBuilder()
				.capacity(0).build());
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.run();
		return h;
	}
}
