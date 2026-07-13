package com.civstudio.server.render;

import java.util.List;

/**
 * A read-only projection of one {@link com.civstudio.settlement.Settlement colony} for the
 * live feed — the same aggregates the annual digest and the CSV printers report, gathered
 * into an immutable, JSON-serializable record. Assembled on the session thread by {@link
 * Snapshots} between ticks (never during {@code newDay}), so its reads see a settled
 * population.
 *
 * @param name           the colony's name
 * @param alive          whether the colony is still running
 * @param date           the colony's current in-game date (ISO-8601)
 * @param population     the workforce (laborer households)
 * @param children       under-age members (household-born + pool wards)
 * @param nobles         noble households
 * @param firms          living firms
 * @param poolSize       peasant-pool reserve size
 * @param cpi            the consumer price index (inflation)
 * @param necessityPrice last clearing price of the necessity (food) market
 * @param enjoymentPrice last clearing price of the enjoyment market
 * @param plotCount      claimed build plots
 * @param maxPlots       the colony's plot ceiling (its province cap)
 * @param latitude       the colony's latitude (decimal degrees) — the map anchor
 * @param longitude      the colony's longitude (decimal degrees)
 * @param bankProfitTax  the ruler's bank-profit tax rate (the player-set lever), 0 if none
 * @param nobleIncomeTax the ruler's noble-income tax rate (the player-set lever), 0 if none
 * @param advisors       the colony's privy council — the court noble seated in each
 *                       filled advisor role (unfilled roles are absent)
 */
public record ColonyView(String name, boolean alive, String date, int population,
		int children, int nobles, int firms, int poolSize, double cpi,
		double necessityPrice, double enjoymentPrice, int plotCount, int maxPlots,
		double latitude, double longitude, double bankProfitTax, double nobleIncomeTax,
		List<AdvisorView> advisors) {
}
