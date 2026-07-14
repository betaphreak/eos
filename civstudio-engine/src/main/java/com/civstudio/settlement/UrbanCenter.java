package com.civstudio.settlement;

import java.util.List;

/**
 * The <b>urban surface</b> a settlement exposes to the district/building layer — the city
 * center it always has, the district-plot map buildings stand on, and how many districts it
 * begins with. Both settlement tiers ({@link Village}, {@link City}) are {@code UrbanCenter}s;
 * only a {@link City} additionally has {@link DistrictHost districts beyond the center}.
 * <p>
 * Factored out of {@link Settlement} so the district feed, the auto-build trigger, and future
 * non-settlement holders (a caravan camp, a league seat) can depend on the urban surface
 * without the whole colony machinery. See {@code docs/settlement-tiers.md} and {@code
 * docs/district-buildout.md}.
 */
public interface UrbanCenter {

	/**
	 * The <b>city center</b> — the village-center plot (index 0 of the district map) where the
	 * center-grouped firms, banks and center buildings sit — or {@code null} if no plot has been
	 * laid yet.
	 *
	 * @return the city-center plot, or {@code null} if none is laid
	 */
	Plot getCityCenter();

	/**
	 * The settlement's <b>district plots</b> — its build plots in claim order (the 1D,
	 * time-ordered plot map). A {@link Village} works only its center; a {@link City} spreads
	 * across its province's several urban plots.
	 *
	 * @return the district plots, in claim order
	 */
	List<Plot> getDistrictPlots();

	/**
	 * The number of districts the settlement begins with — a {@link City}'s founding province
	 * development (capped at its plots); a {@link Village}'s single city center.
	 *
	 * @return the starting district count
	 */
	int getStartingDistrictCount();

	/**
	 * Whether this settlement has <b>districts beyond the city center</b> — i.e. it is a {@link
	 * City} (a multi-urban-plot province), realized by implementing {@link DistrictHost}. A
	 * {@link Village} has only its center, so this is {@code false}.
	 *
	 * @return {@code true} if the settlement can spread buildings across districts
	 */
	boolean hasDistricts();
}
