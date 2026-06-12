package eos.solar;

/**
 * A geographic location — latitude and longitude in decimal degrees — that the
 * {@link SolarEventCalculator} computes sunrise/sunset for. North and east are
 * positive (so London is roughly {@code 51.5, -0.13}).
 *
 * @param latitude
 *            latitude in decimal degrees (north positive)
 * @param longitude
 *            longitude in decimal degrees (east positive)
 */
public record GeoLocation(double latitude, double longitude) {
}
