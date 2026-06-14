package eos.calendar;

import java.time.MonthDay;

/**
 * A single <b>fixed-date</b> feast of the universal Western (pre-Reformation)
 * liturgical calendar — a holy day that falls on the same calendar date every
 * year (e.g. Christmas on December 25). The other medieval feasts, the
 * <em>movable</em> ones computed from Easter (Ascension, Pentecost, Corpus
 * Christi, …), are deliberately not modeled here — this colony uses a curated
 * fixed list only.
 *
 * @param month
 *            the month, 1–12
 * @param day
 *            the day of month, 1–31
 * @param name
 *            the feast's traditional name (for display and future logging)
 */
public record Feast(int month, int day, String name) {

	/** This feast's calendar position as a {@link MonthDay}. */
	public MonthDay monthDay() {
		return MonthDay.of(month, day);
	}
}
