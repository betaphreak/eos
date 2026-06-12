package eos.settlement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import eos.util.Rng;

/**
 * Exercises the colony's daily solar times: a {@link Settlement} placed at a
 * latitude/longitude computes and stores dawn, sunrise, sunset and dusk for its
 * current in-game date (seeded in the constructor, recomputed each {@code
 * newDay}). dawn/dusk are the astronomical (sun 18° below horizon) times,
 * sunrise/sunset the official ones. Checks the obvious astronomical sanity so the
 * wiring (and the BigDecimal->double port) is sound: London gets far more
 * daylight in June than December, the four times are correctly ordered, and the
 * astronomical twilight is absent (null) at London in high summer.
 */
class SettlementSolarTest {

	// London, matching SimulationConfig.DEFAULT
	private static final double LAT = 51.5074, LON = -0.1278;

	// a colony whose step 0 (and thus current date) falls on the given date, so
	// its stored solar times are those of that date
	private Settlement colonyOn(LocalDate date) {
		return new Settlement("Test Colony", date, new Rng(1L), null, null, 35, 26,
				5, LAT, LON);
	}

	@Test
	void londonHasLongSummerDaysAndShortWinterDays() {
		double summer = colonyOn(LocalDate.of(1445, 6, 21)).getDaylightHours();
		double winter = colonyOn(LocalDate.of(1444, 12, 21)).getDaylightHours();

		// London: ~16.5 h at the June solstice, ~7.8 h at the December one
		assertTrue(summer > 15 && summer < 18,
				"London June-solstice daylight should be ~16.5 h, got " + summer);
		assertTrue(winter > 6 && winter < 9,
				"London December-solstice daylight should be ~7.8 h, got " + winter);
		assertTrue(summer - winter > 6,
				"summer should be much longer than winter, got "
						+ (summer - winter) + " h difference");
	}

	@Test
	void dawnSunriseSunsetDuskAreOrderedThroughTheDay() {
		// at the December solstice London has a full astronomical twilight, so all
		// four times are defined and run dawn < sunrise < sunset < dusk
		Settlement colony = colonyOn(LocalDate.of(1444, 12, 21));
		LocalTime dawn = colony.getDawn(), sunrise = colony.getSunrise();
		LocalTime sunset = colony.getSunset(), dusk = colony.getDusk();

		assertNotNull(dawn, "December dawn should be defined");
		assertNotNull(dusk, "December dusk should be defined");
		assertTrue(dawn.isBefore(sunrise),
				"dawn (" + dawn + ") should precede sunrise (" + sunrise + ")");
		assertTrue(sunrise.isBefore(sunset),
				"sunrise (" + sunrise + ") should precede sunset (" + sunset + ")");
		assertTrue(sunset.isBefore(dusk),
				"sunset (" + sunset + ") should precede dusk (" + dusk + ")");
	}

	@Test
	void astronomicalTwilightIsAbsentAtLondonMidsummer() {
		// around the June solstice the sun never reaches 18° below the London
		// horizon — astronomical twilight lasts all night, so dawn/dusk are
		// undefined while the official sunrise/sunset still occur
		Settlement colony = colonyOn(LocalDate.of(1445, 6, 21));
		assertNull(colony.getDawn(), "no astronomical dawn at London midsummer");
		assertNull(colony.getDusk(), "no astronomical dusk at London midsummer");
		assertNotNull(colony.getSunrise(), "official sunrise still occurs");
		assertNotNull(colony.getSunset(), "official sunset still occurs");
	}
}
