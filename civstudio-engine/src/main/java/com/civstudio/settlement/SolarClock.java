package com.civstudio.settlement;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

import com.civstudio.solar.GeoLocation;
import com.civstudio.solar.SolarEventCalculator;
import com.civstudio.solar.Zenith;
import lombok.Getter;

/**
 * Computes a fixed location's daily solar times — dawn, sunrise, sunset, dusk —
 * and the resulting daylight length for a given date. Owns the lazy construction
 * of the underlying {@link SolarEventCalculator} (its inputs, the latitude and
 * longitude, are fixed for the clock's life) and the bridge from
 * {@link LocalDate} to the legacy {@link Calendar}-based calculator. Times are
 * computed in UTC.
 *
 * <p>Extracted from {@link Settlement}, which holds one per colony and refreshes
 * it for the current in-game date at the top of every {@link Settlement#newDay()}.
 * It is <b>public</b> so a moving {@link com.civstudio.agent.Caravan wandering band}
 * can compute the daylight length at its own shifting position/date for the
 * daylight-bounded march (see {@code docs/caravan-march.md}); a band constructs a
 * fresh clock at its current coordinates each day it needs one.
 */
public class SolarClock {

	private final double latitude;
	private final double longitude;

	// the location's local mean-solar-time zone, derived from its longitude (15° = 1h, so
	// the offset is longitude/15 hours). Using it — rather than UTC — makes the reported
	// dawn/sunrise/sunset/dusk read as realistic LOCAL clock times (sunrise ~06:00, etc.)
	// wherever the province sits, instead of UTC times skewed by its longitude. Daylight
	// *length* is a sunrise→sunset duration, invariant to the offset, so the colony economy
	// (which reads only durations) is unchanged.
	private final TimeZone zone;

	// the solar calculator for this location, built lazily on first use; its
	// inputs (latitude/longitude) are fixed for the clock's life
	private SolarEventCalculator solarCalculator;

	// the most recently computed day's solar times (UTC). sunrise/sunset are the
	// official (disc-on-the-horizon) times; dawn/dusk the astronomical ones (the
	// sun 18° below the horizon). Any of them is null on a date where the event
	// does not occur — e.g. no astronomical twilight near midsummer at high
	// latitude, when twilight lasts all night and the event is undefined.
	@Getter
	private LocalTime dawn;
	@Getter
	private LocalTime sunrise;
	@Getter
	private LocalTime sunset;
	@Getter
	private LocalTime dusk;

	// hours of daylight (sunrise to sunset) on the computed date; NaN when
	// sunrise/sunset are undefined (polar day/night)
	@Getter
	private double daylightHours;

	public SolarClock(double latitude, double longitude) {
		this.latitude = latitude;
		this.longitude = longitude;
		// longitude/15 hours, in ms, as a DST-free fixed-offset zone (mean solar time)
		this.zone = new SimpleTimeZone(
				(int) Math.round(longitude / 15.0 * 3_600_000), "LMT");
	}

	/**
	 * Recompute the solar times for {@code date}. A solar event that does not
	 * occur on the date (e.g. no astronomical twilight at this latitude in high
	 * summer) is stored as null, and {@link #getDaylightHours()} as NaN.
	 */
	public void update(LocalDate date) {
		dawn = computeSolarTime(date, Zenith.ASTRONOMICAL, true);
		sunrise = computeSolarTime(date, Zenith.OFFICIAL, true);
		sunset = computeSolarTime(date, Zenith.OFFICIAL, false);
		dusk = computeSolarTime(date, Zenith.ASTRONOMICAL, false);
		daylightHours = (sunrise == null || sunset == null) ? Double.NaN
				: Duration.between(sunrise, sunset).toMinutes() / 60.0;
	}

	// the solar calculator for this location, built once on first use
	private SolarEventCalculator solarCalculator() {
		if (solarCalculator == null)
			solarCalculator = new SolarEventCalculator(
					new GeoLocation(latitude, longitude), zone);
		return solarCalculator;
	}

	// a date as a java.util.Calendar in the location's mean-solar-time zone, the input the
	// SolarEventCalculator (legacy Calendar-based) expects — the same zone the calculator
	// uses, so the day-of-year is not shifted by a timezone change mid-calculation
	private Calendar asCalendar(LocalDate d) {
		Calendar c = Calendar.getInstance(zone);
		c.clear();
		c.set(d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
		return c;
	}

	// compute one solar event (UTC) for the date, or null when the event does not
	// occur (the calculator throws for an undefined event — e.g. the sun never
	// reaching the zenith at extreme latitudes/dates)
	private LocalTime computeSolarTime(LocalDate date, Zenith zenith, boolean isSunrise) {
		try {
			Calendar cal = asCalendar(date);
			var hm = isSunrise
					? solarCalculator().computeSunriseTime(zenith, cal)
					: solarCalculator().computeSunsetTime(zenith, cal);
			return LocalTime.of(hm.getKey(), hm.getValue());
		} catch (UnsupportedOperationException noEvent) {
			return null;
		}
	}
}
