package com.civstudio.geo.names;

/**
 * One real Earth place from the GeoNames gazetteer, kept as a plot-name
 * candidate. A compact projection of the {@code allCountries} row — only the
 * fields plot naming needs.
 *
 * @param id           the GeoNames id (stable across the dump; the per-province
 *                     "already used" key)
 * @param name         the place's UTF-8 name (the {@code name} column, or
 *                     {@code asciiname} if that was blank)
 * @param lat          latitude in decimal degrees
 * @param lon          longitude in decimal degrees
 * @param population   population (0 for unpopulated features such as hills/lakes)
 * @param featureClass the GeoNames feature class: {@code P} populated, {@code A}
 *                     admin, {@code T} mountain, {@code L} area/park, {@code H}
 *                     water
 */
public record GeoNamesPlace(
		int id, String name, double lat, double lon, int population, char featureClass) {
}
