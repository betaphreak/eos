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
 * @param continent  the {@link Continent} this province belongs to (the coarsest
 *                   tier — a partition parallel to the region/area nesting), or
 *                   {@code null} if it has none; deserialized from the continent
 *                   {@code raw_key} (e.g. {@code "asia"}). See {@link
 *                   WorldMap#continentOf(int)}
 * @param climate    the {@link Climate} band ({@link Climate#TEMPERATE} if the
 *                   source did not classify it — never {@code null})
 * @param winter     the {@link WinterSeverity} ({@link WinterSeverity#NONE} by
 *                   default — never {@code null})
 * @param monsoon    the {@link Monsoon} intensity ({@link Monsoon#NONE} by
 *                   default — never {@code null})
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
		@JsonProperty("continent") Continent continent,
		Climate climate,
		WinterSeverity winter,
		Monsoon monsoon,
		List<Integer> neighbors) {

	/**
	 * Defensive copy of the neighbor list, and the environmental-attribute
	 * defaults: an unclassified climate is {@link Climate#TEMPERATE}, and an
	 * unlisted winter/monsoon is {@code NONE} (so these accessors never return
	 * {@code null}).
	 */
	public Province {
		neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
		climate = climate == null ? Climate.TEMPERATE : climate;
		winter = winter == null ? WinterSeverity.NONE : winter;
		monsoon = monsoon == null ? Monsoon.NONE : monsoon;
	}

	/**
	 * Whether a colony may be founded into this province (its {@link
	 * ProvinceType#isSettleable()}). Only {@link ProvinceType#LAND} qualifies at
	 * this stage; water and {@link ProvinceType#IMPASSABLE} wasteland do not.
	 *
	 * @return {@code true} if this is land a settlement can occupy
	 */
	public boolean isSettleable() {
		return type.isSettleable();
	}

	/**
	 * Whether the travel/trade graph may route through this province (its {@link
	 * ProvinceType#isPassable()}). {@link ProvinceType#IMPASSABLE} wasteland is the
	 * only impassable type — caravans can neither settle it nor cross it.
	 *
	 * @return {@code true} if caravans may route through
	 */
	public boolean isPassable() {
		return type.isPassable();
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
