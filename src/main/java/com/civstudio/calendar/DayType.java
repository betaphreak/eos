package com.civstudio.calendar;

/**
 * The kind of day a calendar date falls on, used to distinguish working days
 * from days of rest. In the 15th-century Western (pre-Reformation) calendar
 * this is a three-way split:
 * <ul>
 * <li>{@link #WORKDAY} — an ordinary working day.</li>
 * <li>{@link #WEEKEND} — the weekly day of rest. Historically the
 * <em>only</em> weekly non-work day was <b>Sunday</b> (the Lord's Day);
 * Saturday was a full working day, so there is no modern Saturday–Sunday
 * weekend here.</li>
 * <li>{@link #HOLIDAY} — a liturgical feast day (a holy day on which labor was
 * forbidden or curtailed). See {@link LiturgicalCalendar}.</li>
 * </ul>
 * Today this is purely a <em>tag</em> on the date — nothing in the economy
 * reads it yet; wiring labor output to the day type is a later step.
 */
public enum DayType {
	/** An ordinary working day. */
	WORKDAY,
	/** The weekly day of rest — Sunday in the 15th-century calendar. */
	WEEKEND,
	/** A liturgical feast day (holy day of obligation). */
	HOLIDAY
}
