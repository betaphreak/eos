package com.civstudio.settlement;

/**
 * A settlement that has <b>districts beyond the city center</b> — a {@link City}. Marks the
 * capability that separates a City from a {@link Village}: a City is founded into a province
 * with <b>several urban plots</b> (an Anbennar {@code city_terrain} capital), so its buildings
 * can spread across district plots; a Village has only its single city-center plot.
 * <p>
 * A pure capability marker over {@link UrbanCenter} — code that needs "does this settlement have
 * districts?" tests {@code instanceof DistrictHost} (or {@link UrbanCenter#hasDistricts()}).
 * The district-plot API itself lives on {@link UrbanCenter}, shared by both tiers. See {@code
 * docs/settlement-tiers.md}.
 */
public interface DistrictHost extends UrbanCenter {
}
