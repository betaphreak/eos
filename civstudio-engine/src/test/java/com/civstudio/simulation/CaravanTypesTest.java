package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.CaravanRole;
import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.MilitaryCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.SettlerCaravan;
import com.civstudio.agent.WorkerCaravan;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * The four {@link CaravanRole caravan roles} — settler, worker, explorer, military
 * (grounded in the C2C {@code UnitAI} families) — all share the {@link MarchingCaravan}
 * journey: they march the graph and forage/consume their larder identically; only their
 * arrival mission and order-of-march column differ. This exercises that shared machinery on
 * the three non-settler flavors (whose missions are scaffolds) and checks the military band
 * fields the fuller column.
 */
class CaravanTypesTest {

	// the known-reachable long land route reused from the caravan-journey tests
	private static final int DHENIJANSAR = 4411;
	private static final int WEXKEEP = 306;

	// build an on-graph band of the given role, hosted on a throwaway (never-run) muster
	// colony just to mint its following
	private static MarchingCaravan bandOf(CaravanRole role, GameSession session,
			int provinceId, int followers) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		Retinue following = new Retinue(followers, bank, muster);
		Member leader = following.promoteHighestSkilled();
		return switch (role) {
			case SETTLER -> new SettlerCaravan(leader, following, 1000, provinceId, session);
			case WORKER -> new WorkerCaravan(leader, following, 1000, provinceId, session);
			case MILITARY -> new MilitaryCaravan(leader, following, 1000, provinceId, session);
			// the explorer is no longer a generic marcher — it is a food-import levy mustered
			// from a home colony (ExplorerCaravan.muster; see ExplorerForagingTest)
			case EXPLORER -> throw new UnsupportedOperationException(
					"explorer bands are mustered, not built here");
		};
	}

	@Test
	void everyNonSettlerFlavorMarchesAndConsumesItsLarderLikeASettler() {
		LocalDate summer = LocalDate.of(1445, 6, 21);
		for (CaravanRole role : new CaravanRole[] { CaravanRole.WORKER, CaravanRole.MILITARY }) {
			GameSession session = new GameSession(role.ordinal() + 1);
			MarchingCaravan band = bandOf(role, session, DHENIJANSAR, 50);
			band.setDestination(WEXKEEP);
			band.setCampingEnabled(true);
			Rng rng = session.getBandRng();

			assertEquals(role, band.role(), "the band reports its role");
			double larder0 = band.getFollowing().getLarder();

			MarchReport marched = null;
			for (int i = 0; i < 20; i++) {
				band.tick(summer.plusDays(i), rng);
				if (band.getLastReport() != null && band.getLastReport().day().marches())
					marched = band.getLastReport();
			}

			assertNotNull(marched, role + ": a marching day produced a report");
			assertTrue(band.getProvinceId() != DHENIJANSAR,
					role + ": the band marched off its origin province");
			assertTrue(band.getFollowing().getLarder() < larder0,
					role + ": the band ate from its larder while marching (shared forage/consume)");
		}
	}

	@Test
	void aMilitaryBandFieldsAFullerColumnThanASettler() {
		LocalDate summer = LocalDate.of(1445, 6, 21);
		MarchReport settler = firstMarch(CaravanRole.SETTLER, summer);
		MarchReport military = firstMarch(CaravanRole.MILITARY, summer);
		// settler fields a lean admin column (vanguard + main body + baggage); a military
		// band fields the full order of march (scouts, surveyors, command, guards too) — so
		// its HH:mm timetable has strictly more stages (docs/caravan-march.md §5)
		assertTrue(military.day().stages().size() > settler.day().stages().size(),
				"military stages " + military.day().stages().size()
						+ " should exceed settler stages " + settler.day().stages().size());
	}

	// the first marching day's report for a band of the given role on the Dhenijansar route
	private static MarchReport firstMarch(CaravanRole role, LocalDate summer) {
		GameSession session = new GameSession(42);
		MarchingCaravan band = bandOf(role, session, DHENIJANSAR, 50);
		band.setDestination(WEXKEEP);
		Rng rng = session.getBandRng();
		for (int i = 0; i < 20; i++) {
			band.tick(summer.plusDays(i), rng);
			if (band.getLastReport() != null && band.getLastReport().day().marches())
				return band.getLastReport();
		}
		throw new AssertionError(role + ": no marching day within the window");
	}
}
