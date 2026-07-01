package com.civstudio.settlement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.civstudio.geo.PlotType;
import com.civstudio.geo.Province;
import com.civstudio.geo.ProvincePlotField;
import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.geo.ProvinceRaster;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.tech.Sector;
import com.civstudio.util.Rng;

/**
 * The shared, claimable plot field of one province: the {@link Province}'s land
 * pixels realised as occupiable {@link Plot}s, owned by the province until a
 * settlement claims them. Generated once per province (lazily, cached on the
 * {@link GameSession}) from the Phase-1 {@link ProvincePlotField} terrain
 * generation, and shared by every settlement founded into the province — the
 * substrate for several settlements occupying one province (see {@code
 * docs/province-plots.md}).
 * <p>
 * Ownership is <b>hybrid</b>: a plot is free (province-owned, {@link Plot#owner()}
 * {@code null}) until a settlement claims it, which transfers it to that
 * settlement; {@link #release(Plot) releasing} returns it to the free pool. A
 * settlement claims via {@link #claimFoundingCenter} (its first plot, spaced from
 * any other settlement already in the province) then {@link #claimNearest} (each
 * subsequent plot, nearest its center). Because several settlements in one province
 * claim from the same pool — concurrently, under the lockstep
 * {@link com.civstudio.simulation.SessionRunner} — every mutating operation is
 * {@code synchronized}; a claim that finds no free plot returns {@code null} (the
 * caller treats it as "no room", never an error). See {@code docs/province-plots.md}.
 */
public final class ProvincePlotPool {

	private final Province province;
	private final List<Plot> plots;
	private int freeCount;
	private final int centroidX; // the founding anchor for the first settlement
	private final int centroidY;

	// lazily-built spatial index (packed (x,y) -> workable plot) and corridor cache, for
	// the caravan land-routing corridors (docs/land-routing.md Level 2). Built on first
	// corridor() request; a province with no caravan crossing it never pays for them.
	private Map<Long, Plot> posIndex;
	private final Map<Long, PlotCorridor> corridorCache = new HashMap<>();

	private ProvincePlotPool(Province province, List<Plot> plots) {
		this.province = province;
		this.plots = plots;
		this.freeCount = plots.size();
		long sx = 0, sy = 0;
		for (Plot p : plots) {
			sx += p.x();
			sy += p.y();
		}
		int n = Math.max(1, plots.size());
		this.centroidX = Math.round((float) sx / n);
		this.centroidY = Math.round((float) sy / n);
	}

	/**
	 * Build a province's pool: generate its plot field and realise each
	 * {@link ProvincePlot} as a free, claimable {@link Plot} (position + terrain +
	 * relief + feature + resource). Deterministic per {@code rng} (a per-province
	 * terrain stream).
	 *
	 * @param province the province to build the pool for
	 * @param registry the curated terrain/feature/bonus definitions
	 * @param raster   the raster reader (supplies the province mask)
	 * @param rng      the dedicated per-province terrain stream
	 * @return the province's claimable plot pool
	 */
	public static ProvincePlotPool generate(Province province, TerrainRegistry registry,
			ProvinceRaster raster, Rng rng) throws IOException {
		ProvincePlotField field = ProvincePlotField.generate(province, registry, raster, rng);
		List<Plot> plots = new ArrayList<>(field.size());
		for (ProvincePlot pp : field.plots())
			plots.add(new Plot(pp.x(), pp.y(), pp.terrain(), pp.plotType(), pp.feature(), pp.bonus()));
		return new ProvincePlotPool(province, plots);
	}

	/** The province this pool belongs to. */
	public Province province() {
		return province;
	}

	/** All plots of the province (free and claimed), an unmodifiable view. */
	public List<Plot> plots() {
		return Collections.unmodifiableList(plots);
	}

	/** The total number of plots (== {@code province.plots()}). */
	public int size() {
		return plots.size();
	}

	/** The raster x of the province's plot centroid (a corridor anchor fallback). */
	public int centroidX() {
		return centroidX;
	}

	/** The raster y of the province's plot centroid (a corridor anchor fallback). */
	public int centroidY() {
		return centroidY;
	}

	/** The number of unclaimed (province-owned) plots. */
	public synchronized int freeCount() {
		return freeCount;
	}

	/**
	 * Claim the free plot nearest {@code (cx, cy)} for a settlement, transferring its
	 * ownership — a settlement's per-plot claim, growing outward from its center.
	 *
	 * @param owner the claiming settlement (non-null)
	 * @param cx    the settlement's center x
	 * @param cy    the settlement's center y
	 * @return the claimed plot, or {@code null} if the pool is exhausted
	 */
	public synchronized Plot claimNearest(Settlement owner, int cx, int cy) {
		return take(owner, bestYieldNearest(cx, cy));
	}

	/**
	 * Claim a settlement's <b>founding center</b>: the free plot best spaced from any
	 * plots already claimed by <em>other</em> settlements in the province (so several
	 * settlements spread out), or — for the first settlement — the plot nearest the
	 * province centroid.
	 *
	 * @param owner the founding settlement (non-null)
	 * @return the claimed center plot, or {@code null} if the pool is exhausted
	 */
	public synchronized Plot claimFoundingCenter(Settlement owner) {
		return take(owner, foundingCenter(owner));
	}

	/**
	 * Claim a specific free plot for a settlement (used directly in tests; settlements
	 * claim via {@link #claimNearest}/{@link #claimFoundingCenter}).
	 *
	 * @param plot  a plot of this pool that is currently free
	 * @param owner the claiming settlement (non-null)
	 * @throws IllegalArgumentException if the plot is not free or owner is null
	 */
	public synchronized void claim(Plot plot, Settlement owner) {
		if (owner == null)
			throw new IllegalArgumentException("owner must be non-null");
		if (plot == null || plot.owner() != null)
			throw new IllegalArgumentException("plot is not free to claim");
		take(owner, plot);
	}

	/**
	 * Release a claimed plot back to the free pool (clearing its owner). A no-op for a
	 * plot that is already free.
	 *
	 * @param plot a plot of this pool
	 */
	public synchronized void release(Plot plot) {
		if (plot != null && plot.owner() != null) {
			plot.setOwner(null);
			freeCount++;
		}
	}

	// transfer a (free) plot to owner; null-safe so the claim helpers can pass a
	// failed search straight through as a null result
	private Plot take(Settlement owner, Plot plot) {
		if (plot == null)
			return null;
		plot.setOwner(owner);
		freeCount--;
		return plot;
	}

	// how many of the nearest free plots a settlement weighs by food yield when it
	// claims — it takes the best-food plot among its nearest few, favouring good land
	// in its vicinity without abandoning the nearest-first growth (which keeps the
	// travel ladder and the food-balance calibration stable). A small, proximity-
	// dominant placeholder pending calibration (1 == pure nearest-first).
	private static final int YIELD_CHOICE_NEIGHBOURS = 4;

	// the best-food free plot among the YIELD_CHOICE_NEIGHBOURS nearest to (cx, cy):
	// proximity picks the candidate band, yield picks within it (Phase 4b/4c). Returns
	// null if the pool is exhausted.
	private Plot bestYieldNearest(int cx, int cy) {
		int k = YIELD_CHOICE_NEIGHBOURS;
		long[] nearDist = new long[k];
		Plot[] nearPlot = new Plot[k];
		Arrays.fill(nearDist, Long.MAX_VALUE);
		for (Plot p : plots) {
			if (p.owner() != null)
				continue;
			long d = dist2(p, cx, cy);
			// keep the k nearest: replace the current farthest if this is closer
			int farthest = 0;
			for (int i = 1; i < k; i++)
				if (nearDist[i] > nearDist[farthest])
					farthest = i;
			if (d < nearDist[farthest]) {
				nearDist[farthest] = d;
				nearPlot[farthest] = p;
			}
		}
		Plot best = null;
		double bestYield = -1;
		for (Plot p : nearPlot) {
			if (p == null)
				continue;
			double y = p.yieldFactor(Sector.NECESSITY);
			if (y > bestYield) {
				bestYield = y;
				best = p;
			}
		}
		return best;
	}

	// the free plot maximizing the minimum distance to any other settlement's claimed
	// plots (min-distance auto-spacing); the centroid-nearest free plot if this is the
	// first settlement to claim in the province
	private Plot foundingCenter(Settlement owner) {
		boolean anyOther = false;
		for (Plot p : plots)
			if (p.owner() != null && p.owner() != owner) {
				anyOther = true;
				break;
			}
		if (!anyOther)
			return bestYieldNearest(centroidX, centroidY);

		Plot best = null;
		long bestSpacing = -1;
		for (Plot p : plots) {
			if (p.owner() != null)
				continue;
			long minDist = Long.MAX_VALUE;
			for (Plot q : plots)
				if (q.owner() != null && q.owner() != owner)
					minDist = Math.min(minDist, dist2(p, q.x(), q.y()));
			if (minDist > bestSpacing) {
				bestSpacing = minDist;
				best = p;
			}
		}
		return best;
	}

	private static long dist2(Plot p, int x, int y) {
		long dx = p.x() - x, dy = p.y() - y;
		return dx * dx + dy * dy;
	}

	// --- caravan land-routing corridors (docs/land-routing.md Level 2) --------

	/**
	 * The <b>plot corridor</b> a caravan crosses through this province from its entry
	 * border to its exit border — an A* over the province's plots (4-neighbour raster
	 * adjacency, {@link PlotType#PEAK peaks} impassable), returning the plots crossed and
	 * their total move cost (see {@link PlotCorridor} / {@code docs/caravan-march.md} §6).
	 * The entry/exit anchors are the {@link com.civstudio.geo.WorldMap#portal border
	 * portals}; the corridor snaps each to the nearest workable plot. Cached per
	 * (entry-plot, exit-plot), so a province a route revisits pays the search once.
	 *
	 * @param entryX the entry border anchor's raster x
	 * @param entryY the entry border anchor's raster y
	 * @param exitX  the exit border anchor's raster x
	 * @param exitY  the exit border anchor's raster y
	 * @return the corridor, or {@link PlotCorridor#NONE} if no plot path exists
	 */
	public synchronized PlotCorridor corridor(int entryX, int entryY, int exitX, int exitY) {
		Plot start = nearestWorkable(entryX, entryY);
		Plot goal = nearestWorkable(exitX, exitY);
		if (start == null || goal == null)
			return PlotCorridor.NONE;
		long key = corridorKey(start, goal);
		PlotCorridor cached = corridorCache.get(key);
		if (cached != null)
			return cached;
		PlotCorridor result = search(start, goal);
		corridorCache.put(key, result);
		return result;
	}

	// A* over the workable plots' 4-neighbour raster adjacency, from start to goal
	private PlotCorridor search(Plot start, Plot goal) {
		Map<Long, Plot> index = posIndex();
		Map<Plot, Double> g = new HashMap<>();
		Map<Plot, Plot> cameFrom = new HashMap<>();
		PriorityQueue<Plot> open = new PriorityQueue<>((a, b) -> Double.compare(
				g.getOrDefault(a, Double.MAX_VALUE) + heuristic(a, goal),
				g.getOrDefault(b, Double.MAX_VALUE) + heuristic(b, goal)));
		g.put(start, 0.0);
		open.add(start);
		while (!open.isEmpty()) {
			Plot cur = open.poll();
			if (cur == goal)
				return build(cameFrom, start, goal, g.get(goal));
			double gc = g.get(cur);
			for (Plot nb : neighbours(index, cur)) {
				double tentative = gc + moveCost(nb);
				Double best = g.get(nb);
				if (best == null || tentative < best) {
					g.put(nb, tentative);
					cameFrom.put(nb, cur);
					open.add(nb);
				}
			}
		}
		return PlotCorridor.NONE; // disconnected (e.g. a peak barrier splits the province)
	}

	// reconstruct the corridor path (entry -> exit) and carry the accumulated cost
	private PlotCorridor build(Map<Plot, Plot> cameFrom, Plot start, Plot goal, double cost) {
		List<Plot> path = new ArrayList<>();
		for (Plot cur = goal; cur != start; cur = cameFrom.get(cur))
			path.add(cur);
		path.add(start);
		Collections.reverse(path);
		return new PlotCorridor(Collections.unmodifiableList(path), cost);
	}

	// the 4-neighbour workable plots of a plot, by raster adjacency
	private List<Plot> neighbours(Map<Long, Plot> index, Plot p) {
		List<Plot> out = new ArrayList<>(4);
		addNeighbour(out, index, p.x() + 1, p.y());
		addNeighbour(out, index, p.x() - 1, p.y());
		addNeighbour(out, index, p.x(), p.y() + 1);
		addNeighbour(out, index, p.x(), p.y() - 1);
		return out;
	}

	private static void addNeighbour(List<Plot> out, Map<Long, Plot> index, int x, int y) {
		Plot n = index.get(pack(x, y));
		if (n != null)
			out.add(n);
	}

	// the cost of stepping onto a plot: base 1, rougher for a hill or an uncleared wild
	// feature (a later cut lowers it for a ROAD-improved plot, so corridors hug roads)
	private static double moveCost(Plot p) {
		double c = 1.0;
		if (p.plotType() == PlotType.HILL)
			c += 0.5;
		if (p.isWild())
			c += 0.5;
		return c;
	}

	// straight-line (Euclidean) distance to the goal — admissible, since the minimum
	// per-plot move cost is 1
	private static double heuristic(Plot a, Plot goal) {
		double dx = a.x() - goal.x(), dy = a.y() - goal.y();
		return Math.sqrt(dx * dx + dy * dy);
	}

	// the workable plot nearest (x,y), the snap target for a border-portal anchor
	private Plot nearestWorkable(int x, int y) {
		Plot best = null;
		long bestD = Long.MAX_VALUE;
		for (Plot p : plots) {
			if (!p.isWorkable())
				continue;
			long d = dist2(p, x, y);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	// the packed (x,y) -> workable-plot index, built once on first corridor request
	private Map<Long, Plot> posIndex() {
		if (posIndex == null) {
			Map<Long, Plot> idx = new HashMap<>(plots.size() * 2);
			for (Plot p : plots)
				if (p.isWorkable())
					idx.put(pack(p.x(), p.y()), p);
			posIndex = idx;
		}
		return posIndex;
	}

	// pack a raster (x,y) into 25 bits (x<<12 | y; y < 4096 covers the 2048-tall raster,
	// x < ~8192 the 5632-wide one) — so two packs compose into a collision-free long key
	private static long pack(int x, int y) {
		return ((long) x << 12) | (y & 0xFFF);
	}

	private static long corridorKey(Plot start, Plot goal) {
		return (pack(start.x(), start.y()) << 32) | pack(goal.x(), goal.y());
	}
}
