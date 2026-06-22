package com.civstudio.agent;

import java.util.List;

import com.civstudio.geo.WorldMap;
import com.civstudio.util.Rng;
import lombok.Getter;

/**
 * A <b>wandering band</b> on the map: the abstract base for any led band that is
 * <b>not settled</b> — a mobile aggregate (not a household type) holding a
 * {@link #getLeader() leader} (the band's Captain, a {@link Member}, not a distinct
 * class), a carried {@link #getHoard() hoard} of money held outside any bank, and a
 * <b>position</b> on the province graph.
 * <p>
 * The base carries only the universal band state — leader, hoard, position, and the
 * daily {@link #tick(Rng)}. Purpose-specific payload lives on the concrete
 * subclasses: {@link MigrantCaravan} (the dissolution-born band that carries a
 * following and re-founds a colony — see {@code docs/caravan.md}); a settlement-
 * sponsored {@code TradeCaravan} is the planned follow-on (see
 * {@code docs/caravan-trade.md}).
 * <p>
 * <b>Position.</b> A band is normally a citizen of the {@link WorldMap} province
 * graph: it sits at a {@link #getProvinceId() province} and {@link #moveTo(int)
 * moves} along neighbor edges, one hop per day, with its {@link #getLatitude()
 * latitude}/{@link #getLongitude() longitude} <em>derived</em> from that province. A
 * band may also be <b>off-graph</b> ({@code provinceId == }{@link #OFF_GRAPH}) — born
 * from a colony founded at bare coordinates with no province — in which case it holds
 * raw coordinates and cannot move on the graph (see {@link #onGraph()}). Since the
 * standard ruler-bearing colonies now found <em>into</em> provinces, the on-graph
 * path is the normal one.
 */
public abstract class Caravan {

	/** Sentinel {@link #getProvinceId() province id} for an off-graph band. */
	public static final int OFF_GRAPH = -1;

	// the band's leader: the dynasty Member that commands it (the Captain, the title
	// Rank.CARAVAN carries) and becomes the holder/Ruler if the band re-founds. Not a
	// household class — just the Member, carried across the settle/unsettle hinge.
	@Getter
	private final Member leader;

	// the band's carried hoard — its money, a copper amount held outside any bank
	// (the colony's circulating money, conserved into one figure on dissolution)
	@Getter
	private double hoard;

	// the band's node on the province graph (OFF_GRAPH for a band that holds raw
	// coordinates instead — see onGraph). Mutable because a caravan moves.
	@Getter
	private int provinceId;

	// the province graph the band moves on; null for an off-graph band. When set, the
	// band's latitude/longitude are derived from worldMap.province(provinceId).
	private final WorldMap worldMap;

	// the band's geographic position in decimal degrees (north / east positive),
	// mirroring a Settlement's: derived from its province when on-graph, or the raw
	// coordinates it was created at when off-graph. Mutable because a caravan moves.
	@Getter
	private double latitude;
	@Getter
	private double longitude;

	/**
	 * Create an <b>on-graph</b> band anchored at a province of {@code worldMap}; its
	 * {@link #getLatitude() coordinates} are derived from that province.
	 *
	 * @param leader     the band's leader (its Captain)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param worldMap   the province graph the band moves on
	 */
	protected Caravan(Member leader, double hoard, int provinceId, WorldMap worldMap) {
		this.leader = leader;
		this.hoard = hoard;
		this.provinceId = provinceId;
		this.worldMap = worldMap;
		this.latitude = worldMap.province(provinceId).latitude();
		this.longitude = worldMap.province(provinceId).longitude();
	}

	/**
	 * Create an <b>off-graph</b> band at bare coordinates (no province graph): it
	 * cannot {@link #moveTo(int) move} on the graph. Born from a colony founded at bare
	 * coordinates with no province.
	 *
	 * @param leader    the band's leader (its Captain)
	 * @param hoard     the band's carried money, in copper, held outside any bank
	 * @param latitude  the band's latitude in decimal degrees (north positive)
	 * @param longitude the band's longitude in decimal degrees (east positive)
	 */
	protected Caravan(Member leader, double hoard, double latitude, double longitude) {
		this.leader = leader;
		this.hoard = hoard;
		this.provinceId = OFF_GRAPH;
		this.worldMap = null;
		this.latitude = latitude;
		this.longitude = longitude;
	}

	/**
	 * Whether this band sits on the {@link WorldMap} province graph (and so can
	 * {@link #moveTo(int) move} along neighbor edges). An off-graph band holds raw
	 * coordinates and cannot move on the graph.
	 *
	 * @return {@code true} if the band is anchored to a province
	 */
	public boolean onGraph() {
		return provinceId != OFF_GRAPH;
	}

	/**
	 * Move the band to a <b>neighbouring</b> province (one hop). The destination must
	 * be adjacent to the band's current province on the {@link WorldMap} graph; the
	 * band's coordinates are updated to the new province's.
	 *
	 * @param destProvinceId the neighbouring province to move into
	 * @throws IllegalStateException    if the band is off-graph
	 * @throws IllegalArgumentException if {@code destProvinceId} is not a neighbour of
	 *                                  the band's current province
	 */
	public void moveTo(int destProvinceId) {
		if (!onGraph())
			throw new IllegalStateException(
					"an off-graph band cannot move on the province graph");
		if (!worldMap.neighbors(provinceId).contains(destProvinceId))
			throw new IllegalArgumentException("province " + destProvinceId
					+ " is not a neighbour of " + provinceId);
		this.provinceId = destProvinceId;
		this.latitude = worldMap.province(destProvinceId).latitude();
		this.longitude = worldMap.province(destProvinceId).longitude();
	}

	/**
	 * Advance one hop along a {@link WorldMap#path path} (a list of province ids from
	 * the band's current node to a goal, inclusive of both endpoints): moves the band
	 * to the next node on the path. A path that has already arrived (size &lt; 2) is a
	 * no-op.
	 *
	 * @param path a path starting at the band's current province
	 * @throws IllegalArgumentException if the path does not start at the band's
	 *                                  current province
	 */
	public void step(List<Integer> path) {
		if (path == null || path.size() < 2)
			return; // already at the goal, or no route
		if (path.get(0) != provinceId)
			throw new IllegalArgumentException("path starts at " + path.get(0)
					+ " but the band is at " + provinceId);
		moveTo(path.get(1));
	}

	// the province graph the band moves on (null when off-graph); for subclasses that
	// need to query adjacency or paths during their tick.
	protected WorldMap worldMap() {
		return worldMap;
	}

	/**
	 * Advance the band by one day: consume its provisions and move and/or act. Driven
	 * once per lockstep day by the session runner (see {@code docs/caravan-trade.md}).
	 *
	 * @param rng the session-level band RNG (distinct from any colony's economic
	 *            stream), for deterministic movement/decisions
	 */
	public abstract void tick(Rng rng);
}
