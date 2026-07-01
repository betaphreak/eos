package com.civstudio.agent.march;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/**
 * The daylight-bounded march physics ({@link March}/{@link MarchDay}) — the headline
 * couplings of {@code docs/caravan-march.md}: distance scales up with daylight and down
 * with band size, a daylight-starved day halts the band, and the order of march produces
 * a sane HH:mm schedule.
 */
class MarchTest {

	private static final LocalDate DAY = LocalDate.of(1445, 6, 21);
	private static final LocalTime SUNRISE = LocalTime.of(5, 0);
	private static final MarchConfig CFG = MarchConfig.DEFAULT;

	@Test
	void speedIsPaceTimesCadence() {
		// 0.76 m * 100 paces/min = 76 m/min = 4.56 km/h (the regular Roman march)
		assertEquals(4.56, CFG.speedKmh(), 0.01);
	}

	@Test
	void aLeanBandOutmarchesAHugeBandInTheSameDay() {
		MarchDay lean = March.compute(DAY, 200, MarchFlavor.SETTLER, 15.0, SUNRISE, CFG);
		MarchDay huge = March.compute(DAY, 50_000, MarchFlavor.SETTLER, 15.0, SUNRISE, CFG);
		assertTrue(lean.marches(), "a lean band advances");
		assertTrue(lean.netMarchKm() > huge.netMarchKm(),
				"the lean band covers more ground than the huge one (its column is shorter)");
		assertEquals(0.0, huge.netMarchKm(), 1e-9,
				"a 50k-strong column spends the whole day filing out and in — no net progress");
	}

	@Test
	void moreDaylightMeansMoreDistance() {
		MarchDay winter = March.compute(DAY, 500, MarchFlavor.SETTLER, 8.0, SUNRISE, CFG);
		MarchDay summer = March.compute(DAY, 500, MarchFlavor.SETTLER, 15.0, SUNRISE, CFG);
		assertTrue(summer.netMarchKm() > winter.netMarchKm(),
				"a longer day carries the same band further");
	}

	@Test
	void daylightStarvedDayHaltsTheBand() {
		MarchDay polar = March.compute(DAY, 500, MarchFlavor.SETTLER, Double.NaN, null, CFG);
		assertFalse(polar.marches(), "undefined daylight -> a forced halt");
		assertEquals(0.0, polar.netMarchKm(), 0.0);
		assertTrue(polar.stages().isEmpty(), "a halted day has no order of march");

		MarchDay darkWinter = March.compute(DAY, 500, MarchFlavor.SETTLER, 2.0, SUNRISE, CFG);
		assertEquals(0.0, darkWinter.netMarchKm(), 0.0,
				"a day shorter than the camp overhead makes no progress");
	}

	@Test
	void settlerFlavorFieldsOnlyItsBlocksAndSchedulesThemInOrder() {
		MarchDay day = March.compute(DAY, 900, MarchFlavor.SETTLER, 15.0, SUNRISE, CFG);
		assertTrue(day.marches());
		// settler fields vanguard + main body + baggage only (no scouts/command/guards)
		assertEquals(3, day.stages().size(), "a settler band fields three blocks");
		for (MarchDay.Stage s : day.stages())
			assertTrue(s.element() == MarchElement.VANGUARD
					|| s.element() == MarchElement.MAIN_BODY
					|| s.element() == MarchElement.BAGGAGE_TRAIN,
					"only settler blocks are fielded: " + s.element());
		// the first departure is sunrise + the prep span, and departures are ordered
		assertEquals(LocalTime.of(6, 0), day.firstDepart(), "sunrise 05:00 + 1h prep = 06:00");
		LocalTime prev = null;
		for (MarchDay.Stage s : day.stages()) {
			if (prev != null)
				assertTrue(!s.depart().isBefore(prev), "elements depart in column order");
			prev = s.depart();
			assertTrue(!s.headArrives().isBefore(s.depart()), "a head arrives after it departs");
		}
		assertTrue(day.campMade().isAfter(day.firstDepart()), "camp is made after the first departure");
	}
}
