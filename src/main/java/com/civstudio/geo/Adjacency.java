package com.civstudio.geo;

/**
 * A special map <b>connection</b> between two provinces that are <em>not</em> visually
 * adjacent — a sea strait, a canal, a lake crossing, or a Dwarovar tunnel/pass — taken
 * from the Anbennar EU4 {@code map/adjacencies.csv} (canonical source
 * {@code gitlab.com/anbennar/anbennar-eu4-dev}). These are absent from the raster-derived
 * {@link Province#neighbors()} pixel adjacency, so {@link WorldMap} merges them into the
 * routing graph (a caravan may traverse a tunnel/strait as an edge, cost = the great-circle
 * {@link WorldMap#distanceKm(int, int)}), and the web viewer draws each as an EU4-style red
 * dotted line between the two provinces' centroids. Exported by {@link
 * com.civstudio.geo.export.AdjacencyExporter} to {@code /map/adjacencies.json}.
 *
 * @param from    the {@code province_id} of one endpoint
 * @param to      the {@code province_id} of the other endpoint
 * @param type    the EU4 kind: {@code "sea"}, {@code "canal"}, {@code "lake"}, or {@code ""}
 *                (a Dwarovar tunnel/pass)
 * @param comment the source comment (e.g. {@code "Dwarovar"}, {@code "kiel_canal"}), or empty
 */
public record Adjacency(int from, int to, String type, String comment) {
}
