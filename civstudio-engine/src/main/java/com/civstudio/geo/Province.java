package com.civstudio.geo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One province of the world map: a node exported from the Anbennar EU4 map
 * sources (see {@code com.civstudio.geo.export.ProvinceExporter}) and loaded as
 * part of the per-{@link com.civstudio.settlement.GameSession} {@link WorldMap}. A
 * province is the geographic cell a colony is founded into — its
 * {@link #latitude()}/{@link #longitude()} feed the solar/daylight system and
 * its {@link #plots()} cap how large the settlement may grow (build slots
 * <em>are</em> plots, so a colony's plot count stays {@code <= plots}). The
 * {@link #neighbors()} are the adjacent provinces — the
 * undirected edges of the travel/trade graph the caravan and village-founding
 * features route over.
 * <p>
 * The {@code id} is the game's {@code province_id} (the "used id"), unique per
 * province and the value the {@link #neighbors()} adjacency references. Values
 * come straight from {@code /provinces.json} and are loaded once per session.
 *
 * @param id         the province's {@code province_id} (the adjacency key)
 * @param name       the province's display name
 * @param latitude   decimal degrees, north positive (drives daylight length)
 * @param longitude  decimal degrees, east positive (derived from the map
 *                   bounding-box centroid at export time)
 * @param plots      land cells — the hard ceiling on settlement size
 * @param waterPlots water cells — {@code > 0} marks the province coastal
 * @param type       terrain class ({@link ProvinceType#LAND} and the underground
 *                   {@link ProvinceType#CAVERN} are settleable)
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
 * @param ownerTag   the tag of the {@link Country} that owns this province at the
 *                   game-start bookmark (e.g. {@code "A04"}), or {@code null} for
 *                   an unowned/uncolonized/wasteland province; the {@link WorldMap}
 *                   joins it to a {@link Country} via {@link WorldMap#country(String)}.
 *                   Stamped from the Anbennar {@code history/provinces} files by
 *                   {@link com.civstudio.geo.export.ProvinceHistoryExporter}
 * @param controllerTag the tag of the {@link Country} that <em>controls</em> the
 *                   province at the start date (equal to {@link #ownerTag()} outside
 *                   of an occupation), or {@code null}
 * @param culture    the {@code raw_key} of the province's {@link Culture} (e.g.
 *                   {@code "west_damerian"}), or {@code null}; joined via
 *                   {@link WorldMap#culture(String)}
 * @param religion   the {@code raw_key} of the province's {@link Religion} (e.g.
 *                   {@code "regent_court"}), or {@code null}; joined via
 *                   {@link WorldMap#religion(String)}
 * @param tradeGood  the {@code raw_key} of the province's {@link TradeGood} (e.g.
 *                   {@code "grain"}), or {@code null} for an undiscovered/uncolonized
 *                   province (the EU4 {@code unknown} placeholder is normalized to
 *                   {@code null}); joined via {@link WorldMap#tradeGood(String)}.
 *                   Stamped from the Anbennar {@code history/provinces} files by
 *                   {@link com.civstudio.geo.export.ProvinceHistoryExporter}
 * @param baseTax    the EU4 {@code base_tax} (ADM development) at game start, or {@code 0}
 *                   if the province has no history entry. Stamped from {@code
 *                   history/provinces} by {@link
 *                   com.civstudio.geo.export.ProvinceHistoryExporter}; see {@link
 *                   #development()} and {@code docs/urban-plots.md}
 * @param baseProduction the EU4 {@code base_production} (DIP development), or {@code 0}
 * @param baseManpower the EU4 {@code base_manpower} (MIL development), or {@code 0}
 * @param city       whether Anbennar marks this province a city (its {@code city_terrain}
 *                   terrain block): the province keeps its real land terrain but earns a
 *                   denser urban core. Stamped from {@code terrain.txt} by {@link
 *                   com.civstudio.geo.export.CavernExporter}; see {@code docs/urban-plots.md}
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
		List<Integer> neighbors,
		@JsonProperty("owner") String ownerTag,
		@JsonProperty("controller") String controllerTag,
		String culture,
		String religion,
		@JsonProperty("trade_goods") String tradeGood,
		@JsonProperty("base_tax") int baseTax,
		@JsonProperty("base_production") int baseProduction,
		@JsonProperty("base_manpower") int baseManpower,
		boolean city) {

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
	 * ProvinceType#isSettleable()}). {@link ProvinceType#LAND} and the underground types
	 * qualify; water and {@link ProvinceType#IMPASSABLE} wasteland do not.
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
	 * Whether this is dry land a caravan can march over on foot (its {@link
	 * ProvinceType#isLand()}) — {@link ProvinceType#LAND} or any underground type. Land routing
	 * ({@link com.civstudio.geo.LandRouter}) traverses on this, not {@link
	 * #isPassable()}: water is passable (for the future sea graph) but a foot
	 * caravan must not cross it.
	 *
	 * @return {@code true} if a land caravan may march across this province
	 */
	public boolean isLand() {
		return type.isLand();
	}

	/**
	 * Whether this is an underground province — any of the Serpentspine/Dwarovar types
	 * ({@link ProvinceType#isUnderground()}: cavern, hold, surface hold or road).
	 * Underground colonies are sunless: they run on a fixed lamplit work schedule instead
	 * of solar daylight (see {@link com.civstudio.settlement.FixedDaylightClock} and {@code
	 * docs/underworld.md}). This is the single membership test for the Underworld, read by
	 * both the engine and the web viewer's plane.
	 *
	 * @return {@code true} if this province lies underground
	 */
	public boolean isUnderground() {
		return type.isUnderground();
	}

	/**
	 * Whether this province has water access (any {@code waterPlots}).
	 *
	 * @return {@code true} if the province is coastal
	 */
	public boolean isCoastal() {
		return waterPlots > 0;
	}

	/**
	 * The province's total EU4 development — {@link #baseTax()} (ADM) + {@link
	 * #baseProduction()} (DIP) + {@link #baseManpower()} (MIL). Concentrated on the
	 * province's urban core plot (see {@code docs/urban-plots.md}); {@code 0} for a province
	 * with no history entry.
	 *
	 * @return the summed base development
	 */
	public int development() {
		return baseTax + baseProduction + baseManpower;
	}
}
