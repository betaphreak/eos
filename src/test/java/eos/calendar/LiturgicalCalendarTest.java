package eos.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Checks the liturgical calendar as a pure date -&gt; {@link DayType} function:
 * feasts load, fixed feasts are holidays, Sundays are weekends, ordinary days
 * are workdays, and a feast falling on a Sunday resolves to a holiday.
 */
class LiturgicalCalendarTest {

	private final LiturgicalCalendar cal = LiturgicalCalendar.load();

	@Test
	void loadsTheCuratedFeastList() {
		assertEquals(31, cal.feasts().size(),
				"bundled feasts.json should hold the 31 curated feasts");
	}

	@Test
	void christmasIsAHoliday() {
		LocalDate christmas = LocalDate.of(1445, 12, 25);
		assertTrue(cal.isFeast(christmas));
		assertEquals(DayType.HOLIDAY, cal.dayType(christmas));
		assertNotNull(cal.feastOn(christmas));
		assertTrue(cal.feastOn(christmas).name().startsWith("Christmas"));
	}

	@Test
	void anOrdinarySundayIsAWeekend() {
		// 1445-01-05 is a Sunday in the proleptic-Gregorian timeline, no feast
		LocalDate sunday = LocalDate.of(1445, 1, 5);
		assertEquals(java.time.DayOfWeek.SUNDAY, sunday.getDayOfWeek());
		assertFalse(cal.isFeast(sunday));
		assertEquals(DayType.WEEKEND, cal.dayType(sunday));
	}

	@Test
	void saturdayIsAWorkday() {
		// the period has no Saturday weekend: 1445-01-04 is a feast-free Saturday
		LocalDate saturday = LocalDate.of(1445, 1, 4);
		assertEquals(java.time.DayOfWeek.SATURDAY, saturday.getDayOfWeek());
		assertEquals(DayType.WORKDAY, cal.dayType(saturday));
		assertNull(cal.feastOn(saturday));
	}

	@Test
	void aFeastOnASundayOutranksTheWeekend() {
		// Candlemas (Feb 2) falls on a Sunday in 1445; HOLIDAY must win
		LocalDate candlemas = LocalDate.of(1445, 2, 2);
		assertEquals(java.time.DayOfWeek.SUNDAY, candlemas.getDayOfWeek());
		assertTrue(cal.isFeast(candlemas));
		assertEquals(DayType.HOLIDAY, cal.dayType(candlemas),
				"a feast falling on a Sunday should report as a holiday");
	}

	@Test
	void everyFeastDateClassifiesAsAHoliday() {
		// every curated feast is a holiday whenever it occurs (sample a year)
		for (Feast f : cal.feasts()) {
			LocalDate date = LocalDate.of(1450, f.month(), f.day());
			assertEquals(DayType.HOLIDAY, cal.dayType(date),
					"feast should be a holiday: " + f.name());
		}
	}
}
