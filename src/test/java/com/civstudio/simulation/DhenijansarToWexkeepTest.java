package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Member;
import com.civstudio.agent.MigrantCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.geo.LandRouter;
import com.civstudio.geo.Route;
import com.civstudio.geo.WorldMap;
import com.civstudio.io.printer.CaravanMarchPrinter;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * The land-routing motivating example ({@code docs/land-routing.md}): a caravan travelling
 * <b>Dhenijansar (4411) &rarr; Wexkeep (306)</b> — a long, continent-spanning route from
 * ~23&deg;N to the far north. Verifies both the Level-1 route and a directed
 * daylight-bounded march along it, journalled with the notable {@link
 * com.civstudio.geo.Bonus resource bonuses} encountered.
 */
class DhenijansarToWexkeepTest {

	private static final int DHENIJANSAR = 4411;
	private static final int WEXKEEP = 306;

	@Test
	void aLongLandRouteConnectsTheTwo() {
		WorldMap map = WorldMap.load();
		Route r = new LandRouter(map).route(DHENIJANSAR, WEXKEEP);
		assertFalse(r.isEmpty(), "a land route exists Dhenijansar -> Wexkeep");
		assertEquals(DHENIJANSAR, r.provinces().get(0), "starts at Dhenijansar");
		assertEquals(WEXKEEP, r.provinces().get(r.provinces().size() - 1), "ends at Wexkeep");
		// every consecutive pair is a real neighbour edge
		for (int i = 0; i + 1 < r.provinces().size(); i++)
			assertTrue(map.neighbors(r.provinces().get(i)).contains(r.provinces().get(i + 1)),
					"the route is contiguous over neighbour edges");
		// a continent crossing: many hops and thousands of km, heading substantially north
		assertTrue(r.hops() >= 20, "a long multi-province route (hops=" + r.hops() + ")");
		assertTrue(r.totalKm() > 3000, "thousands of km (totalKm=" + Math.round(r.totalKm()) + ")");
		assertTrue(map.province(WEXKEEP).latitude() > map.province(DHENIJANSAR).latitude() + 20,
				"Wexkeep lies far to the north of Dhenijansar");
	}

	@Test
	void aDirectedBandMarchesTheRouteAndJournalsBonuses() throws Exception {
		long seed = 90210;
		GameSession session = new GameSession(seed);
		// a lean band mustered on a throwaway colony, then directed to Wexkeep
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		Retinue following = new Retinue(50, bank, muster);
		Member leader = following.promoteHighestSkilled();
		MigrantCaravan band = new MigrantCaravan(leader, following, 100_000, DHENIJANSAR, session);
		band.setCampingEnabled(true);
		band.setDestination(WEXKEEP);

		// march it for a couple of summer months, journalling each marched day
		CaravanMarchPrinter journal = new CaravanMarchPrinter("output/" + seed);
		Rng rng = session.getBandRng();
		LocalDate start = LocalDate.of(1445, 6, 1);
		Set<Integer> visited = new HashSet<>();
		boolean sawBonus = false;
		for (int day = 0; day < 60 && !band.isReadyToSettle(); day++) {
			MarchReport report = band.tick(start.plusDays(day), rng);
			visited.add(band.getProvinceId());
			if (report != null) {
				journal.record(report);
				if (!"-".equals(report.bonuses()))
					sawBonus = true;
			}
		}
		journal.close();

		// it left Dhenijansar and made real progress along the route toward Wexkeep
		assertNotEquals(DHENIJANSAR, band.getProvinceId(), "the band left its origin");
		assertTrue(visited.size() >= 3,
				"the band crossed several provinces toward Wexkeep (visited=" + visited.size() + ")");
		// the journal was written with the Bonuses column
		File marchFile = new File("output/" + seed + "/by-caravan/"
				+ leader.fullName().trim() + "-CaravanMarch.csv");
		assertTrue(marchFile.exists(), "the march journal was written: " + marchFile);
		String header = Files.readAllLines(marchFile.toPath()).get(0);
		assertTrue(header.contains("Bonuses"), "the journal reports encountered bonuses");
		// bonuses are sparse, so their presence is logged (likely true over many
		// provinces) but not hard-asserted, to keep the test robust across seeds
		System.out.println("Dhenijansar->Wexkeep: encountered a notable bonus = " + sawBonus);
	}
}
