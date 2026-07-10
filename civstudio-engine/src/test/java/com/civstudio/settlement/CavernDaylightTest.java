package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.calendar.LiturgicalCalendar;
import com.civstudio.geo.Province;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.race.Race;
import com.civstudio.util.Rng;

/**
 * Underground ({@link com.civstudio.geo.ProvinceType#CAVERN cavern}) colonies have no
 * sun, so a colony founded into one runs a fixed lamplit "sweatshop" day
 * ({@link FixedDaylightClock}) rather than the seasonal solar clock: its daylight length
 * is the constant {@link Settlement#CAVERN_WORK_HOURS} on every date. See {@code
 * docs/underworld.md}.
 */
class CavernDaylightTest {

	@Test
	void fixedClockReportsTheSameWorkdayEveryDate() {
		FixedDaylightClock clock =
				new FixedDaylightClock(23.0, 76.0, LocalTime.of(5, 0), 14.0);

		clock.update(LocalDate.of(1445, 6, 21)); // summer solstice
		assertEquals(14.0, clock.getDaylightHours(), 1e-9);
		assertEquals(LocalTime.of(5, 0), clock.getSunrise());
		assertEquals(LocalTime.of(19, 0), clock.getSunset());

		clock.update(LocalDate.of(1444, 12, 21)); // winter solstice — no change
		assertEquals(14.0, clock.getDaylightHours(), 1e-9);
		assertEquals(LocalTime.of(19, 0), clock.getSunset());
	}

	@Test
	void cavernColonyRunsTheFixedScheduleConstantAcrossSeasons() {
		WorldMap map = WorldMap.load();
		Province cave = map.provinces().stream().filter(Province::isUnderground)
				.findFirst().orElseThrow(() -> new AssertionError("no CAVERN province"));

		Settlement summer = colonyInto(cave, LocalDate.of(1445, 6, 21));
		Settlement winter = colonyInto(cave, LocalDate.of(1444, 12, 21));

		assertEquals(Settlement.CAVERN_WORK_HOURS, summer.getDaylightHours(), 1e-9);
		assertEquals(Settlement.CAVERN_WORK_HOURS * 3600, summer.getWorkWindowSeconds(), 1e-6);
		// no seasonal swing underground: midsummer and midwinter are identical
		assertEquals(summer.getDaylightHours(), winter.getDaylightHours(), 1e-9);
	}

	private static Settlement colonyInto(Province p, LocalDate date) {
		return new Settlement("Cave", date, new Rng(1L), null, null,
				TerrainRegistry.load(), new Rng(2L), LiturgicalCalendar.load(),
				35, 26, 5, 2, p.latitude(), p.longitude(),
				Race.HUMAN, Map.of(Race.HUMAN, 1.0), p);
	}
}
