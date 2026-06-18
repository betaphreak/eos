package eos.calendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The colony's liturgical calendar: a pure {@code date -> }{@link DayType}
 * lookup that classifies every calendar date as a {@link DayType#WORKDAY
 * workday}, {@link DayType#WEEKEND weekend} (Sunday) or {@link DayType#HOLIDAY
 * holiday} (a feast day). The feast set is loaded once from {@code
 * /feasts.json}, a curated list of <b>fixed-date</b> universal Western
 * (pre-Reformation) feasts — the same set everywhere, independent of seed and
 * location, exactly like {@link eos.settlement.SlotTable}. A single instance is
 * therefore shared by every colony in a {@link eos.settlement.GameSession},
 * which loads it at start and threads it into each {@link
 * eos.settlement.Settlement}.
 * <p>
 * Two historical notes shape the classification:
 * <ul>
 * <li><b>Sunday only is the weekend.</b> In the 15th century the sole weekly
 * non-work day was Sunday; Saturday was a working day. (Sunday is read from
 * {@link LocalDate#getDayOfWeek()}, so the weekly cycle is consistent within
 * the simulation's proleptic-Gregorian timeline.)</li>
 * <li><b>A feast outranks the weekend.</b> When a feast falls on a Sunday the
 * date is reported as {@link DayType#HOLIDAY} — the more specific, more
 * informative tag — not {@link DayType#WEEKEND}.</li>
 * </ul>
 * The movable Easter-derived feasts are not modeled (see {@link Feast}).
 */
public final class LiturgicalCalendar {

	private static final String RESOURCE = "/feasts.json";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// the curated feast list, in calendar order (kept for display/logging)
	private final List<Feast> feasts;

	// fast date lookup: month-day -> feast (universal feasts, so year-agnostic)
	private final Map<MonthDay, Feast> byDate;

	private LiturgicalCalendar(List<Feast> feasts) {
		this.feasts = List.copyOf(feasts);
		Map<MonthDay, Feast> byDate = new HashMap<MonthDay, Feast>();
		for (Feast f : this.feasts) {
			Feast prev = byDate.put(f.monthDay(), f);
			if (prev != null)
				throw new IllegalStateException(
						"two feasts on the same date: " + prev.name() + " and "
								+ f.name() + " (" + f.monthDay() + ")");
		}
		this.byDate = byDate;
	}

	/**
	 * Load the liturgical calendar from its classpath resource ({@code
	 * /feasts.json}).
	 *
	 * @return the loaded calendar
	 * @throws IllegalStateException
	 *             if the resource is missing or lists two feasts on one date
	 */
	public static LiturgicalCalendar load() {
		return load(RESOURCE);
	}

	/**
	 * Load a liturgical calendar from a specific classpath resource — used for the
	 * per-race feast calendars ({@code /feasts-<id>.json}; see {@code docs/race.md}),
	 * a colony keying its rest calendar off its founding race. The shipped
	 * {@code /feasts.json} is the default (human) calendar.
	 *
	 * @param resource
	 *            the feast-list classpath resource (e.g. {@code "/feasts-harimari.json"})
	 * @return the loaded calendar
	 * @throws IllegalStateException
	 *             if the resource is missing or lists two feasts on one date
	 */
	public static LiturgicalCalendar load(String resource) {
		try (InputStream in =
				LiturgicalCalendar.class.getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException(
						"Feast calendar resource not found: " + resource);
			List<Feast> rows = MAPPER.readValue(in,
					new TypeReference<List<Feast>>() {
					});
			return new LiturgicalCalendar(rows);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load feast calendar resource: " + resource, e);
		}
	}

	/**
	 * Classify a date. A feast day is a {@link DayType#HOLIDAY} (even when it
	 * falls on a Sunday); any other Sunday is a {@link DayType#WEEKEND}; every
	 * other day is a {@link DayType#WORKDAY}.
	 *
	 * @param date
	 *            the date to classify
	 * @return the day type for that date
	 */
	public DayType dayType(LocalDate date) {
		if (isFeast(date))
			return DayType.HOLIDAY;
		if (date.getDayOfWeek() == DayOfWeek.SUNDAY)
			return DayType.WEEKEND;
		return DayType.WORKDAY;
	}

	/**
	 * Whether a feast falls on the given date.
	 *
	 * @param date
	 *            the date to test
	 * @return true if a feast falls on it
	 */
	public boolean isFeast(LocalDate date) {
		return byDate.containsKey(MonthDay.from(date));
	}

	/**
	 * The feast falling on the given date, if any.
	 *
	 * @param date
	 *            the date to look up
	 * @return the feast on that date, or null if none
	 */
	public Feast feastOn(LocalDate date) {
		return byDate.get(MonthDay.from(date));
	}

	/** The curated feast list, in calendar order (unmodifiable). */
	public List<Feast> feasts() {
		return feasts;
	}
}
