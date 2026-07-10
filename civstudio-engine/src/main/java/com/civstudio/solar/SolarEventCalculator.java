package com.civstudio.solar;

import lombok.NonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.AbstractMap;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Parent class of the Sunrise and Sunset calculator classes.
 */
public class SolarEventCalculator {
    final private GeoLocation location;
    final private TimeZone timeZone;

    /**
     * Constructs a new <code>SolarEventCalculator</code> using the given parameters.
     *
     * @param location
     *            <code>Location</code> of the place where the solar event should be calculated from.
     * @param timeZoneIdentifier
     *            time zone identifier of the timezone of the location parameter. For example,
     *            "America/New_York".
     */
    public SolarEventCalculator(GeoLocation location, String timeZoneIdentifier) {
        this.location = location;
        this.timeZone = TimeZone.getTimeZone(timeZoneIdentifier);
    }

    /**
     * Constructs a new <code>SolarEventCalculator</code> using the given parameters.
     *
     * @param location
     *            <code>Location</code> of the place where the solar event should be calculated from.
     * @param timeZone
     *            timezone of the location parameter.
     */
    public SolarEventCalculator(GeoLocation location, TimeZone timeZone) {
        this.location = location;
        this.timeZone = timeZone;
    }

    /**
     * Computes the sunrise time for the given zenith at the given date.
     *
     * @param solarZenith
     *            <code>Zenith</code> enum corresponding to the type of sunrise to compute.
     * @param date
     *            <code>Calendar</code> object representing the date to compute the sunrise for.
     * @return the sunrise time, in HH:MM format (24-hour clock), 00:00 if the sun does not rise on the given
     *         date.
     */
    public AbstractMap.SimpleImmutableEntry<Integer, Integer> computeSunriseTime(Zenith solarZenith, Calendar date) {
        return getLocalTime(computeSolarEventTime(solarZenith, date, true));
    }

    /**
     * Computes the sunrise time for the given zenith at the given date.
     *
     * @param solarZenith
     *            <code>Zenith</code> enum corresponding to the type of sunrise to compute.
     * @param date
     *            <code>Calendar</code> object representing the date to compute the sunrise for.
     * @return the sunrise time as a calendar or null for no sunrise
     */
    public Calendar computeSunriseCalendar(Zenith solarZenith, Calendar date) {
        return getLocalTimeAsCalendar(computeSolarEventTime(solarZenith, date, true), date);
    }

    /**
     * Computes the sunset time for the given zenith at the given date.
     *
     * @param solarZenith
     *            <code>Zenith</code> enum corresponding to the type of sunset to compute.
     * @param date
     *            <code>Calendar</code> object representing the date to compute the sunset for.
     * @return the sunset time, in HH:MM format (24-hour clock), 00:00 if the sun does not set on the given
     *         date.
     */
    public AbstractMap.SimpleImmutableEntry<Integer, Integer> computeSunsetTime(Zenith solarZenith, Calendar date) {
        return getLocalTime(computeSolarEventTime(solarZenith, date, false));
    }

    /**
     * Computes the sunset time for the given zenith at the given date.
     *
     * @param solarZenith
     *            <code>Zenith</code> enum corresponding to the type of sunset to compute.
     * @param date
     *            <code>Calendar</code> object representing the date to compute the sunset for.
     * @return the sunset time as a Calendar or null for no sunset.
     */
    public Calendar computeSunsetCalendar(Zenith solarZenith, Calendar date) {
        return getLocalTimeAsCalendar(computeSolarEventTime(solarZenith, date, false), date);
    }

    private double computeSolarEventTime(Zenith solarZenith, Calendar date, boolean isSunrise) {
        date.setTimeZone(this.timeZone);
        double longitudeHour = getLongitudeHour(date, isSunrise);

        double meanAnomaly = getMeanAnomaly(longitudeHour);
        double sunTrueLong = getSunTrueLongitude(meanAnomaly);
        double cosineSunLocalHour = getCosineSunLocalHour(sunTrueLong, solarZenith);
        if ((cosineSunLocalHour < -1.0) || (cosineSunLocalHour > 1.0)) {
            throw new UnsupportedOperationException("Invalid cosine sun local hour in event time");
        }

        double sunLocalHour = getSunLocalHour(cosineSunLocalHour, isSunrise);
        double localMeanTime = getLocalMeanTime(sunTrueLong, longitudeHour, sunLocalHour);
        return getLocalTime(localMeanTime, date);
    }

    /**
     * Computes the base longitude hour, lngHour in the algorithm.
     *
     * @return the longitude of the location of the solar event divided by 15 (deg/hour), in
     *         <code>BigDecimal</code> form.
     */
    private double getBaseLongitudeHour() {
        return location.longitude() / 15;
    }

    /**
     * Computes the longitude time, t in the algorithm.
     *
     * @return longitudinal time in <code>BigDecimal</code> form.
     */
    private double getLongitudeHour(Calendar date, Boolean isSunrise) {
        int offset = isSunrise? 6 : 18;
        double dividend = offset - getBaseLongitudeHour();
        double addend = dividend / 24;
        return getDayOfYear(date) + addend;
    }

    /**
     * Computes the mean anomaly of the Sun, M in the algorithm.
     *
     * @return the suns mean anomaly, M, in <code>BigDecimal</code> form.
     */
    private double getMeanAnomaly(double longitudeHour) {
        return (0.9856d * longitudeHour) - 3.289d;
    }

    /**
     * Computes the true longitude of the sun, L in the algorithm, at the given location, adjusted to fit in
     * the range [0-360].
     *
     * @param meanAnomaly
     *            the suns mean anomaly.
     * @return the suns true longitude, in <code>BigDecimal</code> form.
     */
    private double getSunTrueLongitude(double meanAnomaly) {
        double sinMeanAnomaly = Math.sin(convertDegreesToRadians(meanAnomaly));
        // BigDecimal sinMeanAnomaly = BigDecimal.valueOf(Math.sin(convertDegreesToRadians(meanAnomaly).doubleValue()));
        double sinDoubleMeanAnomaly = Math.sin(convertDegreesToRadians(meanAnomaly) * 2);
        //BigDecimal sinDoubleMeanAnomaly = BigDecimal.valueOf(Math.sin(multiplyBy(convertDegreesToRadians(meanAnomaly), BigDecimal.valueOf(2).doubleValue()));
        double firstPart = meanAnomaly + sinMeanAnomaly * 1.916d;
        //BigDecimal firstPart = meanAnomaly.add(multiplyBy(sinMeanAnomaly, new BigDecimal("1.916")));
        double secondPart = (sinDoubleMeanAnomaly * 0.02d) + 282.634d;
        //BigDecimal secondPart = multiplyBy(sinDoubleMeanAnomaly, new BigDecimal("0.020")).add(new BigDecimal("282.634"));
        double trueLongitude = firstPart + secondPart;
        // wrap into [0, 360): subtract a full turn only when we overshoot it
        return trueLongitude > 360 ? trueLongitude - 360 : trueLongitude;
    }

    /**
     * Computes the suns right ascension, RA in the algorithm, adjusting for the quadrant of L and turning it
     * into degree-hours. Will be in the range [0,360].
     *
     * @param sunTrueLong
     *            Suns true longitude, in <code>BigDecimal</code>
     * @return suns right ascension in degree-hours, in <code>BigDecimal</code> form.
     */
    private double getRightAscension(double sunTrueLong) {
        double tanL = Math.tan(convertDegreesToRadians(sunTrueLong));

        double innerParens = convertRadiansToDegrees(tanL) * 0.91764d;
        double rightAscension = Math.atan(convertDegreesToRadians(innerParens));
        rightAscension = convertRadiansToDegrees(rightAscension);

        if (rightAscension < 0) {
            rightAscension += 360;
        } else if (rightAscension > 360) {
            rightAscension -= 360;
        }
        //BigDecimal ninety = BigDecimal.valueOf(90);
        double longitudeQuadrant = sunTrueLong / 90;
        longitudeQuadrant = Math.floor(longitudeQuadrant) * 90;

        double rightAscensionQuadrant = rightAscension / 90;
        rightAscensionQuadrant = Math.floor(rightAscensionQuadrant) * 90;

        double augend = longitudeQuadrant - rightAscensionQuadrant;
        return (rightAscension + augend) / 15;
    }

    private double getCosineSunLocalHour(double sunTrueLong, Zenith zenith) {
        double sinSunDeclination = getSinOfSunDeclination(sunTrueLong);
        double cosineSunDeclination = getCosineOfSunDeclination(sinSunDeclination);

        double zenithInRads = convertDegreesToRadians(zenith.degrees());
        double cosineZenith = Math.cos(zenithInRads);
        double sinLatitude = Math.sin(convertDegreesToRadians(location.latitude()));
        double cosLatitude = Math.cos(convertDegreesToRadians(location.latitude()));

        double sinDeclinationTimesSinLat = sinSunDeclination * sinLatitude;
        double dividend = cosineZenith - sinDeclinationTimesSinLat;
        double divisor = cosineSunDeclination * cosLatitude;

        return dividend / divisor;
    }

    private double getSinOfSunDeclination(double sunTrueLong) {
        double sinTrueLongitude = Math.sin(convertDegreesToRadians(sunTrueLong));
        return sinTrueLongitude * 0.39782d;
    }

    private double getCosineOfSunDeclination(double sinSunDeclination) {
        double arcSinOfSinDeclination = Math.asin(sinSunDeclination);
        return Math.cos(arcSinOfSinDeclination);
    }

    private double getSunLocalHour(double cosineSunLocalHour, Boolean isSunrise) {
        double arcCosineOfCosineHourAngle = Math.acos(cosineSunLocalHour);
        double localHour = convertRadiansToDegrees(arcCosineOfCosineHourAngle);
        if (isSunrise) {
            localHour = 360 - localHour;
        }
        return localHour / 15;
    }

    private double getLocalMeanTime(double sunTrueLong, double longitudeHour, double sunLocalHour) {
        double rightAscension = this.getRightAscension(sunTrueLong);
        double innerParens = longitudeHour * 0.06571d;
        double localMeanTime = sunLocalHour + rightAscension - innerParens;
        localMeanTime = localMeanTime - 6.622d;

        if (localMeanTime < 0) {
            localMeanTime += 24;
        } else if (localMeanTime > 24) {
            localMeanTime -= 24;
        }
        return localMeanTime;
    }

    private double getLocalTime(double localMeanTime, Calendar date) {
        double utcTime = localMeanTime - getBaseLongitudeHour();
        double utcOffSet = getUTCOffSet(date);
        double utcOffSetTime = utcTime + utcOffSet;
        return adjustForDST(utcOffSetTime, date);
    }

    private double adjustForDST(double localMeanTime, Calendar date) {
        double localTime = localMeanTime;
        if (timeZone.inDaylightTime(date.getTime()))
            localTime = localTime + 1;
        if (localTime> 24.0)
            localTime -= 24;
        return localTime;
    }

    private AbstractMap.SimpleImmutableEntry<Integer, Integer> getLocalTime(@NonNull Double localTime) {
        if (localTime < 0) {
            localTime += 24;
        }
        String[] timeComponents = localTime.toString().split("\\.");
        int hour = Integer.parseInt(timeComponents[0]);

        BigDecimal minutes = new BigDecimal("0." + timeComponents[1]);
        minutes = minutes.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_EVEN);
        if (minutes.intValue() == 60) {
            minutes = BigDecimal.ZERO;
            hour += 1;
        }
        if (hour == 24) {
            hour = 0;
        }
        return new AbstractMap.SimpleImmutableEntry<>(hour, minutes.intValue());
    }

    /**
     * Returns the local rise/set time in the form HH:MM.
     *
     * @param localTime
     *            <code>BigDecimal</code> representation of the local rise/set time.
     * @return <code>Calendar</code> representation of the local time as a calendar, or null for none.
     */
    protected Calendar getLocalTimeAsCalendar(@NonNull Double localTime, Calendar date) {
        // Create a clone of the input calendar, so we get locale/timezone information.
        Calendar resultTime = (Calendar) date.clone();

        if (localTime < 0) {
            localTime += 24;
            resultTime.add(Calendar.HOUR_OF_DAY, -24);
        }
        String[] timeComponents = localTime.toString().split("\\.");
        int hour = Integer.parseInt(timeComponents[0]);

        BigDecimal minutes = new BigDecimal("0." + timeComponents[1]);
        minutes = minutes.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_EVEN);
        if (minutes.intValue() == 60) {
            minutes = BigDecimal.ZERO;
            hour += 1;
        }
        if (hour == 24) {
            hour = 0;
        }

        // Set the local time
        resultTime.set(Calendar.HOUR_OF_DAY, hour);
        resultTime.set(Calendar.MINUTE, minutes.intValue());
        resultTime.set(Calendar.SECOND, 0);
        resultTime.set(Calendar.MILLISECOND, 0);
        resultTime.setTimeZone(date.getTimeZone());

        return resultTime;
    }
    // extra utility methods

    private double getDayOfYear(Calendar date) {
        return date.get(Calendar.DAY_OF_YEAR);
    }

    private double getUTCOffSet(Calendar date) {
        double offSetInMillis = date.get(Calendar.ZONE_OFFSET);
        return offSetInMillis / 3600000; // TODO: two decimal points only?
    }

    private double convertRadiansToDegrees(double radians) {
        return radians * 180 / Math.PI;
        //return multiplyBy(radians, new BigDecimal(180 / Math.PI));
    }

    private double convertDegreesToRadians(double degrees) {
        return degrees * Math.PI / 180.0;
        //return multiplyBy(degrees, BigDecimal.valueOf(Math.PI / 180.0));
    }

}