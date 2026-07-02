package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Member;
import com.civstudio.agent.MigrantCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.geo.LandRouter;
import com.civstudio.geo.WorldMap;
import com.civstudio.good.Good;
import com.civstudio.io.printer.CaravanMarchPrinter;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * <b>Parallel directed caravans</b> — six bands mustered at Dhenijansar (Rahen) and
 * marched <b>concurrently, one thread each</b>, to six cities across the map: Marrhold,
 * North Castonath, Nathalaire, Damescrown, Vertesk and South Viswall.
 * <p>
 * This is the thread-safety test for off-session band ticking: the bands share the
 * session's world map (read-only), tech tree and per-province {@code ProvincePlotPool}s
 * (synchronized; the nightly camp claim is an atomic {@code Plot.tryOccupy}, exercised
 * hard here since all six bands start in the <i>same</i> province and camp along
 * overlapping route prefixes). Each thread gets its own {@link Rng} (a directed band
 * draws no site-choice randomness anyway) and its own journal writer — journals are
 * named by <b>journey</b> ({@code Dhenijansar-<Destination>-CaravanMarch.csv}), so the
 * six files never collide. Band construction (which draws names/skills from the muster
 * colony's registries) stays single-threaded; only the ticking is parallel.
 */
class ParallelCaravansTest {

	private static final int DHENIJANSAR = 4411;

	// the six destinations (province id + name, as committed in map/provinces.json);
	// Castonath and Viswall are split provinces on the map — the largest part stands
	// for the city
	private static final int[] DEST_IDS = { 4097, 833, 451, 234, 216, 63 };
	private static final String[] DEST_NAMES = { "Marrhold", "North Castonath",
			"Nathalaire", "Damescrown", "Vertesk", "South Viswall" };

	// per-head larder provision: generous (thousands of days at the SNACK wandering
	// ration) so no band starves short of a distant destination — arrival, not food
	// accounting, is under test here (DhenijansarToWexkeepTest covers the larder)
	private static final double PROVISION_PER_PERSON = 300.0;

	private static void setLarder(Retinue following, double target) {
		Good larder = following.getGood("Necessity");
		double have = larder.getQuantity();
		if (target > have)
			larder.increase(target - have);
		else
			larder.decrease(have - target);
	}

	@Test
	void sixDirectedBandsMarchConcurrentlyToTheirCities() throws Exception {
		long seed = 24601;
		GameSession session = new GameSession(seed);
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		WorldMap map = session.getWorldMap();

		// fail fast if any destination is unreachable by land
		LandRouter router = new LandRouter(map);
		for (int i = 0; i < DEST_IDS.length; i++)
			assertFalse(router.route(DHENIJANSAR, DEST_IDS[i]).isEmpty(),
					"a land route exists Dhenijansar -> " + DEST_NAMES[i]);

		// muster all six bands on the main thread (naming/skill draws are colony-level)
		List<MigrantCaravan> bands = new ArrayList<>();
		for (int i = 0; i < DEST_IDS.length; i++) {
			Retinue following = new Retinue(50, bank, muster);
			Member leader = following.promoteHighestSkilled();
			setLarder(following, PROVISION_PER_PERSON * following.size());
			MigrantCaravan band = new MigrantCaravan(leader, following, 100_000,
					DHENIJANSAR, session);
			band.setCampingEnabled(true);
			band.setDestination(DEST_IDS[i]);
			bands.add(band);
		}

		// one thread per band; each with its own Rng and its own journal writer
		int maxDays = 365 * 20;
		LocalDate start = LocalDate.of(1445, 6, 1);
		ExecutorService pool = Executors.newFixedThreadPool(bands.size());
		try {
			List<Future<Integer>> marches = new ArrayList<>();
			for (int i = 0; i < bands.size(); i++) {
				MigrantCaravan band = bands.get(i);
				Rng rng = new Rng(seed * 31 + i);
				marches.add(pool.submit(() -> {
					CaravanMarchPrinter journal = new CaravanMarchPrinter("output/" + seed);
					int day = 0;
					try {
						for (; day < maxDays && !band.isReadyToSettle(); day++) {
							MarchReport r = band.tick(start.plusDays(day), rng);
							if (r != null)
								journal.record(r);
						}
					} finally {
						journal.close();
					}
					return day;
				}));
			}
			for (int i = 0; i < bands.size(); i++) {
				int days = marches.get(i).get(); // rethrows any march failure
				MigrantCaravan band = bands.get(i);
				assertTrue(band.isReadyToSettle(), DEST_NAMES[i]
						+ " band arrived within the horizon (stopped day " + days
						+ " at province " + band.getProvinceId() + ")");
				assertEquals(DEST_IDS[i], band.getProvinceId(),
						"the band stands at " + DEST_NAMES[i]);
				assertTrue(band.getFollowing().size() > 0,
						"the band arrived with settlers in hand");
				File journal = new File("output/" + seed + "/by-caravan/Dhenijansar-"
						+ DEST_NAMES[i] + "-CaravanMarch.csv");
				assertTrue(journal.exists(), "journey journal written: " + journal);
				System.out.printf("Dhenijansar -> %-16s arrived day %4d (~%dy), size %d, cargo [%s]%n",
						DEST_NAMES[i], days, days / 365, band.getFollowing().size(),
						band.getCargo().manifest(3));
			}
		} finally {
			pool.shutdownNow();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}
}
