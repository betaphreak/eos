package com.civstudio.settlement;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A {@link SolarClock} for sunless underground ({@link
 * com.civstudio.geo.ProvinceType#CAVERN cavern}) colonies. There is no sun in the
 * Serpentspine, so instead of turning a date and latitude into solar times this clock
 * reports the <b>same fixed working day every day</b> — a constant lamplit "sweatshop"
 * schedule standing in for solar daylight. {@link #update(LocalDate)} is a no-op, so the
 * reported sunrise/sunset — and therefore {@link Settlement#getWorkWindowSeconds()} and
 * the labor market's daylight scaling — never move with the date or the seasons.
 *
 * <p>Twilight is meaningless underground, so dawn coincides with sunrise and dusk with
 * sunset (lights on, lights off). See {@code docs/underworld.md}.
 */
public final class FixedDaylightClock extends SolarClock {

	private final LocalTime dawn;
	private final LocalTime sunrise;
	private final LocalTime sunset;
	private final LocalTime dusk;
	private final double daylightHours;

	/**
	 * @param latitude  the colony's latitude (carried for parity with {@link
	 *                  SolarClock}; unused, since the schedule is fixed)
	 * @param longitude the colony's longitude (likewise unused)
	 * @param sunrise   the fixed daily "lights on" time; keep {@code sunrise + workHours}
	 *                  within the same day so sunset does not wrap past midnight
	 * @param workHours the fixed length of the working day in hours — sunset is {@code
	 *                  sunrise + workHours} and {@link #getDaylightHours()} returns this
	 */
	public FixedDaylightClock(double latitude, double longitude,
			LocalTime sunrise, double workHours) {
		super(latitude, longitude);
		this.sunrise = sunrise;
		this.sunset = sunrise.plusMinutes(Math.round(workHours * 60));
		this.dawn = sunrise;
		this.dusk = sunset;
		this.daylightHours = workHours;
	}

	@Override
	public void update(LocalDate date) {
		// fixed schedule: there is no sun to recompute for the date
	}

	@Override
	public LocalTime getDawn() {
		return dawn;
	}

	@Override
	public LocalTime getSunrise() {
		return sunrise;
	}

	@Override
	public LocalTime getSunset() {
		return sunset;
	}

	@Override
	public LocalTime getDusk() {
		return dusk;
	}

	@Override
	public double getDaylightHours() {
		return daylightHours;
	}
}
