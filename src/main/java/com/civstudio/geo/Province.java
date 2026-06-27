package com.civstudio.geo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One province of the world map: a node imported from the Strapi world content
 * (see {@code com.civstudio.geo.export.ProvinceExporter}) and loaded as part of
 * the per-{@link com.civstudio.settlement.GameSession} {@link WorldMap}. A
 * province is the geographic cell a colony is founded into — its
 * {@link #latitude()}/{@link #longitude()} feed the solar/daylight system and
 * its {@link #plots()} cap how large the settlement may grow (build slots
 * <em>are</em> plots, so a colony's {@code SlotTable} total stays
 * {@code <= plots}). The {@link #neighbors()} are the adjacent provinces — the
 * undirected edges of the travel/trade graph the caravan and village-founding
 * features route over.
 * <p>
 * The {@code id} is the game's {@code province_id} (the "used id"), unique per
 * province and the value the {@link #neighbors()} adjacency references — not the
 * Strapi surrogate key. Values come straight from {@code /provinces.json} and are
 * loaded once per session.
 *
 * @param id         the province's {@code province_id} (the adjacency key)
 * @param name       the province's display name
 * @param latitude   decimal degrees, north positive (drives daylight length)
 * @param longitude  decimal degrees, east positive (derived from the map
 *                   bounding-box centroid at export time)
 * @param plots      land cells — the hard ceiling on settlement size
 * @param waterPlots water cells — {@code > 0} marks the province coastal
 * @param type       terrain class (only {@link ProvinceType#LAND} is settleable)
 * @param regionKey  the stable {@code raw_key} of the region this province
 *                   belongs to (e.g. {@code "rahen_coast_region"}), or
 *                   {@code null} if it has none (some open-ocean provinces)
 * @param areaKey    the stable {@code raw_key} of the area this province belongs
 *                   to (e.g. {@code "inner_rahen_area"}), the finer tier nested
 *                   inside the region, or {@code null} if it has none; see
 *                   {@link Region} and {@link WorldMap#areaOf(int)}
 * @param continentKey the stable {@code raw_key} of the continent this province
 *                   belongs to (e.g. {@code "asia"}), the coarsest tier — a
 *                   partition parallel to the region/area nesting — or
 *                   {@code null} if it has none; see {@link Continent} and
 *                   {@link WorldMap#continentOf(int)}
 * @param neighbors  the {@code province_id}s of the adjacent provinces (an
 *                   undirected graph; symmetry is materialized at export time)
 */
public record Province(
		int id,
		String name,
		@JsonProperty("lat") double latitude,
		@JsonProperty("lon") double longitude,
		int plots,
		int waterPlots,
		ProvinceType type,
		@JsonProperty("region") String regionKey,
		@JsonProperty("area") String areaKey,
		@JsonProperty("continent") String continentKey,
		List<Integer> neighbors) {

	/** Defensive copy so a loaded province's neighbor list cannot be mutated. */
	public Province {
		neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
	}

	/**
	 * Whether a colony may be founded into this province. Only
	 * {@link ProvinceType#LAND} is settleable at this stage; {@code SEA}/{@code
	 * LAKE} are water the travel graph routes over.
	 *
	 * @return {@code true} if this is land a settlement can occupy
	 */
	public boolean isSettleable() {
		return type == ProvinceType.LAND;
	}

	/**
	 * Whether this province has water access (any {@code waterPlots}).
	 *
	 * @return {@code true} if the province is coastal
	 */
	public boolean isCoastal() {
		return waterPlots > 0;
	}
}
