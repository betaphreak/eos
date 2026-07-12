package com.civstudio.geo;

/**
 * The terrain class of a {@link Province}. {@link #LAND}/{@link #SEA}/{@link
 * #LAKE} are derived from the Anbennar {@code data/anbennar/default.map} (its {@code
 * sea_starts}/{@code lakes} province-id blocks, every other province being land)
 * by {@link com.civstudio.geo.export.ProvinceExporter}; {@link #IMPASSABLE} is
 * overlaid from {@code data/anbennar/climate.txt} (the wasteland/mountain provinces) by
 * {@link com.civstudio.geo.export.ClimateExporter}; the four <b>underground</b> types
 * ({@link #CAVERN}, {@link #DWARVEN_HOLD}, {@link #DWARVEN_HOLD_SURFACE}, {@link
 * #DWARVEN_ROAD}) are overlaid from {@code data/anbennar/terrain.txt} (the Dwarovar
 * terrain blocks) by {@link com.civstudio.geo.export.CavernExporter}.
 * <ul>
 * <li>{@link #LAND} — dry land; settleable and passable.</li>
 * <li>{@link #CAVERN} — an open underground cavern floor.</li>
 * <li>{@link #DWARVEN_HOLD} — a sub-surface dwarven hold (karak): an underground city.</li>
 * <li>{@link #DWARVEN_HOLD_SURFACE} — a surface-gate dwarven hold (e.g. Verkal Dromak,
 * Marrhold): a hold with a surface entrance, still part of the underground realm.</li>
 * <li>{@link #DWARVEN_ROAD} — a Dwarovrod tunnel: the road network linking the holds.</li>
 * </ul>
 * The four underground types are all {@link #isUnderground() sunless} — a colony in one
 * runs on a fixed lamplit work schedule rather than solar daylight (see {@code
 * docs/underworld.md}) — and all settleable, passable dry {@link #isLand() land} like
 * {@link #LAND}.
 * <p>
 * Seven <b>special surface</b> types capture distinctive Anbennar terrains that would
 * otherwise flatten onto generic {@link #LAND} — likewise overlaid from {@code
 * terrain.txt} by {@link com.civstudio.geo.export.CavernExporter}, all settleable/passable
 * surface land: {@link #ANCIENT_FOREST}, {@link #GLADEWAY}, {@link #FEY_GLADEWAY} and
 * {@link #BLOODGROVES} (fey/old-growth/blood-magic forests), {@link #MUSHROOM_FOREST}
 * (the Haless fungal woodland), {@link #SHADOW_SWAMP} and {@link #GLACIER}. (The Anbennar
 * {@code city_terrain} is <em>not</em> a province type: a city keeps its real land terrain
 * and gains one or more urban <em>plots</em> — the {@code TERRAIN_URBAN} built-up core —
 * rather than becoming a wholly-urban province; see {@code docs/urban-plots.md}.) The
 * remaining types:
 * <ul>
 * <li>{@link #SEA} — open ocean; unsettleable, but the water the travel/trade
 * graph routes over (passable).</li>
 * <li>{@link #LAKE} — inland water; unsettleable for now (lakeshore settlement is
 * future work), passable.</li>
 * <li>{@link #IMPASSABLE} — wasteland (deserts, mountains): neither settleable nor
 * passable, so caravans can neither found into it nor route through it.</li>
 * </ul>
 */
public enum ProvinceType {

	LAND(true, true, false),
	CAVERN(true, true, true),
	DWARVEN_HOLD(true, true, true),
	DWARVEN_HOLD_SURFACE(true, true, true),
	DWARVEN_ROAD(true, true, true),
	// special Anbennar surface terrains (settleable, passable, not underground)
	ANCIENT_FOREST(true, true, false),
	GLADEWAY(true, true, false),
	FEY_GLADEWAY(true, true, false),
	BLOODGROVES(true, true, false),
	MUSHROOM_FOREST(true, true, false),
	SHADOW_SWAMP(true, true, false),
	GLACIER(true, true, false),
	SEA(false, true, false),
	LAKE(false, true, false),
	IMPASSABLE(false, false, false);

	private final boolean settleable;
	private final boolean passable;
	private final boolean underground;

	ProvinceType(boolean settleable, boolean passable, boolean underground) {
		this.settleable = settleable;
		this.passable = passable;
		this.underground = underground;
	}

	/** Whether a colony may be founded into a province of this type. */
	public boolean isSettleable() {
		return settleable;
	}

	/** Whether the travel/trade graph (caravans) may route through this type. */
	public boolean isPassable() {
		return passable;
	}

	/**
	 * Whether this is an underground (sunless) type — one of the Dwarovar classes
	 * ({@link #CAVERN}/{@link #DWARVEN_HOLD}/{@link #DWARVEN_HOLD_SURFACE}/{@link
	 * #DWARVEN_ROAD}). Underground colonies bypass the solar calculator for a fixed
	 * lamplit work schedule (see {@code docs/underworld.md}), and the web viewer lights
	 * them on the Underworld plane. This is the single membership test for the Underworld.
	 */
	public boolean isUnderground() {
		return underground;
	}

	/**
	 * Whether this is dry land a caravan can march over on foot — {@link #LAND}, any
	 * {@link #isUnderground() underground} type, or a special surface terrain (all walkable
	 * ground). Water ({@link #SEA}/{@link #LAKE}) is {@link #isPassable() passable} for the
	 * future sea/trade graph but is <b>not</b> land, so land routing ({@link
	 * com.civstudio.geo.LandRouter}) excludes it; {@link #IMPASSABLE} wasteland is neither.
	 * This is the predicate land caravans traverse on, not {@link #isPassable()} (which
	 * would let a foot caravan cross open water). Cave-entrance gating of surface↔underground
	 * travel is future work; today an underground province is plain land in the routing graph.
	 */
	public boolean isLand() {
		// every dry-land type: LAND, the underground types, and the special surface
		// terrains — i.e. anything passable that isn't open water
		return passable && this != SEA && this != LAKE;
	}
}
