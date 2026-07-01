package com.civstudio.geo;

/**
 * The committed travel weights of one province's out-edges: the great-circle km to
 * each of its {@link Province#neighbors() neighbours}, <b>aligned to that neighbour
 * order</b> (so {@code km[i]} is the distance to {@code neighbors().get(i)}). Written
 * once by the {@link com.civstudio.geo.export.LandRouteExporter} to {@code
 * /map/edges.json} and loaded by {@link WorldMap} (which validates the length against
 * the province's neighbour count). Pure geometry — seed- and run-independent — so it
 * is precomputed and committed rather than recomputed per run (see {@code
 * docs/land-routing.md}).
 *
 * @param id the province's id (the adjacency key it aligns to)
 * @param km the per-neighbour edge weight in km, aligned to {@link Province#neighbors()}
 */
public record ProvinceEdges(int id, double[] km) {
}
