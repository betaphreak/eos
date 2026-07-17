package com.civstudio.geo;

import java.util.regex.Pattern;

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

	/**
	 * The comments Anbennar gives a <b>teleporter</b> row — a connection that is magical
	 * rather than geographic: the Deepwoods gladeway mesh, the fey portals, and the four
	 * Domandrod seasonal gates. Anchored at the end of the comment and requiring the
	 * separating {@code _} so an ordinary strait that merely contains the word (the sea
	 * crossing {@code "Eargate-Damescross"}) is not swept in.
	 * <p>
	 * The eight comments this matches, and their counts in the Anbennar source (92 rows
	 * total): {@code Deepwoods_Teleporter} 64, {@code deepwoods_fey_portal} 14, {@code
	 * domandrod_fey_portal} 9, and one each of {@code domandrod_}{@code
	 * summer}/{@code spring}/{@code autumn}/{@code winter}{@code _gate} plus {@code
	 * winter_gate2}.
	 */
	private static final Pattern TELEPORTER_COMMENT = Pattern
			.compile("(?i).*(_teleporter|_fey_portal|_(?:summer|spring|autumn|winter)_gate\\d*)$");

	/**
	 * Whether this connection is a <b>teleporter</b> rather than a strait/canal/tunnel —
	 * i.e. the two endpoints are joined by magic and no distance is travelled.
	 * <p>
	 * This is <em>data</em>, read from the source comment, and deliberately not a distance
	 * heuristic: gladeway endpoints sit close together, so any "far apart ⇒ teleporter" rule
	 * misses all 92 of them while firing on ordinary long sea links. Not to be confused with
	 * {@link ProvincePortals.Portal}, which is a border-midpoint anchor for corridor routing
	 * and has nothing to do with teleportation.
	 */
	public boolean teleporter() {
		return comment != null && TELEPORTER_COMMENT.matcher(comment).matches();
	}
}
