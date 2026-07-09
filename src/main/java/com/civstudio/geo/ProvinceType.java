package com.civstudio.geo;

/**
 * The terrain class of a {@link Province}. {@link #LAND}/{@link #SEA}/{@link
 * #LAKE} are derived from the Anbennar {@code data/anbennar/default.map} (its {@code
 * sea_starts}/{@code lakes} province-id blocks, every other province being land)
 * by {@link com.civstudio.geo.export.ProvinceExporter}; {@link #IMPASSABLE} is
 * overlaid from {@code data/anbennar/climate.txt} (the wasteland/mountain provinces) by
 * {@link com.civstudio.geo.export.ClimateExporter}; {@link #CAVERN} is overlaid from
 * {@code data/anbennar/terrain.txt} (the {@code cavern} terrain's {@code
 * terrain_override} list — the underground Serpentspine) by
 * {@link com.civstudio.geo.export.CavernExporter}.
 * <ul>
 * <li>{@link #LAND} — dry land; settleable and passable.</li>
 * <li>{@link #CAVERN} — underground cave floor (the Serpentspine/Dwarovar): settleable
 * and passable dry land like {@link #LAND}, but sunless — a cavern colony runs on a
 * fixed lamplit work schedule rather than solar daylight (see {@code docs/underworld.md}).</li>
 * <li>{@link #SEA} — open ocean; unsettleable, but the water the travel/trade
 * graph routes over (passable).</li>
 * <li>{@link #LAKE} — inland water; unsettleable for now (lakeshore settlement is
 * future work), passable.</li>
 * <li>{@link #IMPASSABLE} — wasteland (deserts, mountains): neither settleable nor
 * passable, so caravans can neither found into it nor route through it.</li>
 * </ul>
 */
public enum ProvinceType {

	LAND(true, true),
	CAVERN(true, true),
	SEA(false, true),
	LAKE(false, true),
	IMPASSABLE(false, false);

	private final boolean settleable;
	private final boolean passable;

	ProvinceType(boolean settleable, boolean passable) {
		this.settleable = settleable;
		this.passable = passable;
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
	 * Whether this is dry land a caravan can march over on foot — {@link #LAND} or
	 * {@link #CAVERN} (underground floor is still walkable ground). Water ({@link
	 * #SEA}/{@link #LAKE}) is {@link #isPassable() passable} for the future sea/trade
	 * graph but is <b>not</b> land, so land routing ({@link
	 * com.civstudio.geo.LandRouter}) excludes it; {@link #IMPASSABLE} wasteland is
	 * neither. This is the predicate land caravans traverse on, not {@link
	 * #isPassable()} (which would let a foot caravan cross open water). Cave-entrance
	 * gating of surface↔underground travel is future work; today a cavern province is
	 * plain land in the routing graph.
	 */
	public boolean isLand() {
		return this == LAND || this == CAVERN;
	}
}
