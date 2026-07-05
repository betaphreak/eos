package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.civstudio.good.Good;
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

	// per-head food to provision the larder with — ~the observed journey length (the band
	// arrives in ~800 days over the all-land route, marching over Civ4 per-plot movement
	// penalties) times the daily wandering ration (0.1/day), with a small margin. Sized so
	// eating just drains the provision, leaving the foraged food as the surplus at Wexkeep
	// (see the test body).
	private static final double JOURNEY_RATION_PER_PERSON = 82.0;

	// set a retinue's larder to an exact quantity
	private static void setLarder(Retinue following, double target) {
		Good larder = following.getGood("Necessity");
		double have = larder.getQuantity();
		if (target > have)
			larder.increase(target - have);
		else
			larder.decrease(have - target);
	}

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
	void techStateGatesWhichResourcesTheBandCanIdentify() {
		// Dhenijansar carries food resources a medieval band forages (see the main test);
		// a band that knows *no* techs can identify none of them — it neither reports nor
		// forages a resource locked behind a tech it lacks
		GameSession session = new GameSession(4242);
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		Retinue following = new Retinue(50, bank, muster);
		Member leader = following.promoteHighestSkilled();
		MigrantCaravan band = new MigrantCaravan(leader, following, 1000, DHENIJANSAR, session);
		band.setCampingEnabled(true);
		band.setDestination(WEXKEEP);
		band.setKnownTechs(java.util.Set.of()); // a band that knows nothing

		Rng rng = session.getBandRng();
		double totalForaged = 0, totalGathered = 0;
		boolean anyBonusReported = false;
		for (int i = 0; i < 15 && !band.isReadyToSettle(); i++) {
			MarchReport report = band.tick(LocalDate.of(1445, 6, 1).plusDays(i), rng);
			if (report != null) {
				totalForaged += report.foraged();
				totalGathered += report.gathered();
				if (!"-".equals(report.bonuses()))
					anyBonusReported = true;
			}
		}
		assertFalse(anyBonusReported,
				"a band that knows no techs identifies no resources (all bonuses hidden)");
		assertEquals(0.0, totalForaged, 1e-9,
				"and forages nothing, since it can identify no food resource");
		assertEquals(0.0, totalGathered, 1e-9,
				"and gathers nothing, since it can identify no non-food resource either");
		assertTrue(band.getCargo().isEmpty(), "its cargo stays empty");
	}

	@Test
	void aDirectedBandMarchesAllTheWayToWexkeep() throws Exception {
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
		int people = following.size();
		// Provision the larder for exactly the journey's consumption, scaled to the band:
		// ~the observed trip length (JOURNEY_RATION_PER_PERSON days of ration per head).
		// Since eating exactly drains this provision, whatever larder remains at Wexkeep is
		// what the band foraged from the land along the way.
		double provision = JOURNEY_RATION_PER_PERSON * people;
		setLarder(following, provision);
		MigrantCaravan band = new MigrantCaravan(leader, following, 100_000, DHENIJANSAR, session);
		band.setCampingEnabled(true);
		band.setDestination(WEXKEEP);

		// march until it arrives, journalling each marched day and tallying food eaten/foraged
		CaravanMarchPrinter journal = new CaravanMarchPrinter("output/" + seed);
		Rng rng = session.getBandRng();
		LocalDate start = LocalDate.of(1445, 6, 1);
		Set<Integer> visited = new HashSet<>();
		double totalAte = 0, totalForaged = 0;
		int totalGathered = 0;
		int maxDays = 365 * 25;
		int day = 0;
		for (; day < maxDays && !band.isReadyToSettle(); day++) {
			MarchReport report = band.tick(start.plusDays(day), rng);
			visited.add(band.getProvinceId());
			totalAte += following.getLastConsumed();
			if (report != null) {
				journal.record(report);
				totalForaged += report.foraged();
				totalGathered += report.gathered();
			}
		}
		journal.close();
		double larderAtWexkeep = following.getLarder();
		System.out.printf("Dhenijansar->Wexkeep: arrived day %d (~%dy), %d provinces; "
				+ "ate=%.0f foraged=%.0f larderAtWexkeep=%.0f gathered=%d cargo=[%s]%n",
				day, day / 365, visited.size(), totalAte, totalForaged, larderAtWexkeep,
				totalGathered, band.getCargo().manifest(10));

		// it reached Wexkeep, having crossed the whole route
		assertTrue(band.isReadyToSettle(),
				"the band reached Wexkeep within 25 years (stopped on day " + day
						+ " at province " + band.getProvinceId() + ")");
		assertEquals(WEXKEEP, band.getProvinceId(), "the band arrived at Wexkeep");
		assertTrue(visited.size() >= 20,
				"it crossed the whole continent (visited=" + visited.size() + " provinces)");
		// the larder is provisioned to the trip's consumption, so eating drains it and the
		// remainder at Wexkeep is what was foraged: larder = provision - ate + foraged
		assertTrue(totalForaged > 0, "the band foraged food on the way");
		assertEquals(provision - totalAte + totalForaged, larderAtWexkeep, 1.0,
				"larder = provision - eaten + foraged (accounting)");
		assertEquals(totalForaged, larderAtWexkeep, provision * 0.1,
				"the larder at Wexkeep reflects what was foraged (provision covered the eating)");
		// the band gathered the non-food resources it crossed (ores, gems, luxuries...)
		// into its cargo — the per-good inventory the future trade caravan trades from —
		// in whole units (discrete goods: no fractional elephants)
		assertTrue(totalGathered > 0, "the band gathered non-food goods on the way");
		assertEquals(totalGathered, band.getCargo().total(),
				"the cargo holds exactly what was gathered (nothing drawn, nothing lost)");
		assertTrue(band.getCargo().goods().size() > 1,
				"the long route crossed more than one kind of gatherable resource");
		assertTrue(band.getCargo().total() <= people
				* com.civstudio.agent.march.MarchConfig.DEFAULT.cargoCapacityPerHead(),
				"the cargo respects the band's carrying capacity");
		// the journal was written (named by the journey, source-destination) with the
		// Bonuses and cargo columns
		File marchFile = new File("output/" + seed + "/by-caravan/"
				+ "Dhenijansar-Wexkeep-CaravanMarch.csv");
		assertTrue(marchFile.exists(), "the march journal was written: " + marchFile);
		String header = Files.readAllLines(marchFile.toPath()).get(0);
		assertTrue(header.contains("Bonuses"), "the journal reports encountered bonuses");
		assertTrue(header.contains("Gathered") && header.contains("Carrying"),
				"the journal reports the day's gathering and the cargo manifest");
	}
}
