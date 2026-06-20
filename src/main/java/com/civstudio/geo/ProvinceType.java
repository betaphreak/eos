package com.civstudio.geo;

/**
 * The terrain class of a {@link Province}, imported from the Strapi world
 * content's {@code province_type} column.
 * <ul>
 * <li>{@link #LAND} — dry land; the only type a colony may be founded into at
 * this stage (see {@link Province#isSettleable()}).</li>
 * <li>{@link #SEA} — open ocean; unsettleable, the water the travel/trade graph
 * routes over.</li>
 * <li>{@link #LAKE} — inland water; unsettleable for now (lakeshore settlement
 * is future work).</li>
 * </ul>
 */
public enum ProvinceType {
	LAND, SEA, LAKE
}
