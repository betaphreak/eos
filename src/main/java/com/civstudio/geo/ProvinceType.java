package com.civstudio.geo;

/**
 * The terrain class of a {@link Province}. {@link #LAND}/{@link #SEA}/{@link
 * #LAKE} are imported from the Strapi world content's {@code province_type}
 * column; {@link #IMPASSABLE} is overlaid from {@code data/climate.txt} (the
 * wasteland/mountain provinces) by {@link
 * com.civstudio.geo.export.ClimateExporter}.
 * <ul>
 * <li>{@link #LAND} — dry land; settleable and passable.</li>
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
}
