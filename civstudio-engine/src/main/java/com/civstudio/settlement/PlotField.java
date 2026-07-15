package com.civstudio.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.Firm;
import com.civstudio.geo.Feature;
import com.civstudio.geo.Improvement;
import com.civstudio.geo.PlotType;
import com.civstudio.geo.Province;
import com.civstudio.geo.Terrain;
import com.civstudio.geo.TerrainGenerator;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.tech.Sector;
import com.civstudio.util.Rng;

/**
 * The <b>spatial subsystem</b> of a {@link Settlement}: the colony's build plots
 * (occupied and vacant), the shared province plot pool it claims from, the terrain
 * generation for a province-less colony, and the builder's plot-clearance queue.
 * Extracted from {@code Settlement} to keep that class focused on the step loop and
 * agent/market registry — everything here is land, not economy.
 * <p>
 * A colony is <b>founded with no plots and grows to fit its occupants</b>: at
 * founding by genesis-appending one developed plot per firm (free), and once live
 * only through its {@link com.civstudio.agent.firm.BuilderFirm} (one plot at a time,
 * via a firm-funded {@link BuildProject}). The count is hard-capped at the province's
 * plots (or {@link Settlement#PROVINCE_LESS_PLOT_CAP} for a bare-coordinate colony).
 * See {@code docs/plots.md} and {@code docs/province-plots.md}.
 * <p>
 * Plot placement moves no money and consumes no economic randomness (the terrain
 * draw is on a dedicated terrain stream, salted apart from the economic one).
 */
class PlotField {

	// the owning colony, for the lifecycle/builder/session state a plot decision reads
	private final Settlement colony;

	// the curated terrain/feature/improvement definitions (shared across the session);
	// null for a colony with no terrain data (some tests)
	private final TerrainRegistry terrainRegistry;

	// the dedicated terrain-generation random stream (salted apart from the economic one)
	private final Rng terrainRng;

	// the province this colony was founded into, or null for a bare-coordinate colony
	private final Province province;

	// the colony's baseline terrain (the generator's reference ground), used for a
	// province-less colony's plots
	private final Terrain baselineTerrain;

	// the per-colony terrain generator (province-founded, province-less colony has none)
	private final TerrainGenerator terrainGenerator;

	// the hard ceiling on the colony's plot count: its province's plots (build slots
	// are plots — see docs/plots.md), or PROVINCE_LESS_PLOT_CAP for a bare-coordinate
	// colony. A colony cannot grow past this.
	private final int maxPlots;

	// the colony's build plots (occupied and vacant), in claim order — one per firm
	// site. Appended at founding (genesis sizing) and, once live, one at a time by the
	// builder (see claimPlot); an occupant keeps its plot. Today only firms occupy
	// plots. Capped at maxPlots.
	private final List<Plot> plots = new ArrayList<Plot>();

	// the shared province plot pool this colony claims from (resolved lazily from the
	// session once province and session are both set); null for a province-less colony,
	// which generates its own plots instead. See docs/province-plots.md.
	private ProvincePlotPool plotPool;
	private boolean plotPoolResolved;

	// the colony's center on the province field — the first plot it claims; its
	// per-settlement travel ladder ranks plots by distance from this. Null until the
	// first claim (province-founded colonies only).
	private Plot center;

	// reverse index from a seated occupant to the plot it stands on, so the terrain
	// yield factor can be read by identity in O(1) (effectiveA reads it per firm per
	// step). Kept in sync by seat()/vacatePlot; an occupant with no entry (a
	// center-grouped or pending firm) reads the neutral factor 1.0.
	private final Map<PlotOccupant, Plot> plotByOccupant =
			new IdentityHashMap<PlotOccupant, Plot>();

	// outstanding plot-clearance tasks the builder is working through, in the order
	// plots were demanded (lowest index first). Empty unless a live colony has
	// outgrown its plots; see claimPlot / requestGrowth / completeFinishedPlots.
	private final List<BuildProject> buildQueue = new ArrayList<BuildProject>();

	// occupants that demanded a plot a live colony could not yet supply: they are
	// seated as the builder finishes clearing the plots being built for them.
	private final List<PlotOccupant> pendingOccupants = new ArrayList<PlotOccupant>();

	PlotField(Settlement colony, TerrainRegistry terrainRegistry, Rng terrainRng,
			Province province) {
		this.colony = colony;
		this.terrainRegistry = terrainRegistry;
		this.terrainRng = terrainRng;
		this.province = province;
		// the province's plots cap growth (build slots are plots); a province-less
		// colony uses the fixed effectively-unbounded cap. A province too small to
		// hold even the founding floor is rejected outright.
		this.maxPlots = province == null ? Settlement.PROVINCE_LESS_PLOT_CAP : province.plots();
		if (province != null && province.plots() < Settlement.MIN_FOUNDING_PLOTS)
			throw new IllegalArgumentException(colony.getName()
					+ " cannot be founded in province " + province.name() + ": its "
					+ province.plots() + " plots are below the minimum founding footprint "
					+ Settlement.MIN_FOUNDING_PLOTS);
		// a province-founded colony generates its plot terrain from the province's
		// climate (off the dedicated terrain RNG); a province-less colony uses the
		// baseline terrain uniformly and takes no terrain draw (see appendPlot).
		this.baselineTerrain = terrainRegistry == null ? null
				: terrainRegistry.terrain(TerrainGenerator.BASELINE_TERRAIN);
		this.terrainGenerator = (province == null || terrainRegistry == null) ? null
				: new TerrainGenerator(terrainRegistry, province.climate(),
						province.winter(), province.monsoon());
	}

	/** The hard ceiling on the colony's plot count. */
	int getMaxPlots() {
		return maxPlots;
	}

	/** The colony's current plot count (occupied or vacant). */
	int getPlotCount() {
		return plots.size();
	}

	/**
	 * The colony's district plots — its build plots in claim order (the 1D,
	 * time-ordered plot map), as an unmodifiable view. Each plot is one district
	 * slot; its occupant is the district's building. See {@code docs/district-generator.md}.
	 */
	List<Plot> getDistrictPlots() {
		return Collections.unmodifiableList(plots);
	}

	// Place <tt>occupant</tt> on a vacant plot. At founding (before start()) it appends
	// a fresh developed plot and seats the occupant; while live it queues the plot's
	// clearance for the builder and returns null (the occupant is seated when the plot
	// is built). Throws if the colony cannot make room. See docs/plots.md.
	Plot claimPlot(PlotOccupant occupant) {
		Plot plot = firstVacantPlot();
		if (plot != null) {
			seat(plot, occupant);
			return plot;
		}
		// a started colony grows through its builder — EXCEPT during a mid-run founding boot (a
		// camp reaching SMALLHOLDING founds its settled economy, docs/settlement-tier-ladder-plan.md
		// Phase D3), which lays its founding farms genesis-style (free, developed) exactly as a
		// fresh founding does, before the builder exists.
		if (colony.isStarted() && !colony.isGenesisFounding())
			return requestBuild(occupant);
		return foundPlot(occupant);
	}

	// founding (pre-run genesis): append fresh plots for the occupant — skipping any
	// unworkable peaks (which stay on the ladder, counting toward the cap) — and seat
	// it on the first workable one. Not the live-growth path.
	private Plot foundPlot(PlotOccupant occupant) {
		while (true) {
			if (!canAcquirePlot(0))
				throw new IllegalStateException(colony.getName() + " cannot seat " + occupant
						+ ": no room left in its province (max " + maxPlots + " plots)");
			Plot plot = appendPlot();
			if (plot == null)
				throw new IllegalStateException(colony.getName() + " cannot seat " + occupant
						+ ": its province pool is exhausted");
			if (!plot.isWorkable())
				continue; // a peak: keep it on the ladder and append the next plot
			seat(plot, occupant);
			developPlot(plot, occupant); // genesis: raise the improvement for free
			return plot;
		}
	}

	// place an occupant on a plot and index it (so its terrain yield can be read by
	// identity — see plotYieldFactor)
	private void seat(Plot plot, PlotOccupant occupant) {
		plot.occupy(occupant);
		plotByOccupant.put(occupant, plot);
	}

	// live colony: only the builder can make room. Queue one plot's clearance and
	// hold the occupant pending; it is seated when the builder finishes the plot.
	private Plot requestBuild(PlotOccupant occupant) {
		if (colony.getBuilder() == null)
			throw new IllegalStateException(colony.getName()
					+ " is full and has no builder to grow it for " + occupant);
		requestGrowth(occupant);
		pendingOccupants.add(occupant);
		return null;
	}

	// generate the next plot's land — terrain plus an optional wild feature — from
	// the province's climate (off the terrain stream); a province-less colony uses the
	// baseline terrain (no feature) and takes no draw. Does not add it to the
	// colony (the founding path appends; live growth holds it on the build queue).
	private Plot generatePlot(int index) {
		// a province-founded colony claims a pre-generated plot from the shared province
		// pool (nearest its center) instead of drawing its own; a province-less colony
		// keeps the legacy per-plot terrain draw. Either way the caller assigns the plot
		// its ladder index and handles peaks the same way.
		ProvincePlotPool pool = plotPool();
		if (pool != null) {
			Plot plot = claimNearestFreePlot(pool);
			if (plot == null)
				return null; // the shared pool is exhausted — the caller handles "no room"
			plot.setIndex(index);
			return plot;
		}
		if (terrainGenerator == null)
			return new Plot(index, baselineTerrain, PlotType.FLAT, null);
		Terrain terrain = terrainGenerator.next(terrainRng);
		PlotType plotType = terrainGenerator.nextPlotType(terrainRng);
		Feature feature = terrainGenerator.nextFeature(terrain, plotType, terrainRng);
		return new Plot(index, terrain, plotType, feature);
	}

	// the shared province pool this colony claims from, resolved lazily from the session
	// (province-founded only); null for a province-less colony. Resolved once.
	private ProvincePlotPool plotPool() {
		if (!plotPoolResolved) {
			plotPoolResolved = true;
			if (province != null && colony.getSession() != null)
				plotPool = colony.getSession().provincePlotPool(province);
		}
		return plotPool;
	}

	// claim the next plot for this colony from the shared province pool: the founding
	// center (spaced from any other settlement already in the province) for the first
	// claim, then the free plot nearest that center. Returns null if the shared pool is
	// exhausted (another settlement took the last plots) — the caller treats that as
	// "no room". A peak is claimed like any plot — the caller (foundPlot/requestGrowth)
	// skips or zero-work-queues it, so peaks consume a ladder rung exactly as before.
	private Plot claimNearestFreePlot(ProvincePlotPool pool) {
		Plot target = (center == null)
				? pool.claimFoundingCenter(colony)
				: pool.claimNearest(colony, center.x(), center.y());
		if (target != null && center == null)
			center = target;
		return target;
	}

	// whether the colony can acquire one more plot: free room in the shared province
	// pool for a province-founded colony (the real, shared limit — several settlements
	// draw from it), or the per-colony plot cap for a province-less one.
	private boolean canAcquirePlot(int inFlight) {
		ProvincePlotPool pool = plotPool();
		if (pool != null)
			return pool.freeCount() > 0;
		return plots.size() + inFlight < maxPlots;
	}

	// claim one BARE plot for a camp's forage (Phase G): generate + append the next plot (from the
	// shared province pool, which sets the founding `center` on the first claim; a baseline plot for
	// a province-less colony) but raise NO improvement and seat no occupant — the camp forages it and
	// builds its own HUNTING_CAMP over time. Null if the shared pool is exhausted / no plot could be
	// generated. Package-visible for Settlement.setUpCampForage.
	Plot claimBarePlot() {
		return appendPlot();
	}

	// append a freshly-generated vacant plot at the next ladder index (the founding
	// genesis path; live growth generates its plot up front in requestGrowth).
	private Plot appendPlot() {
		Plot plot = generatePlot(plots.size());
		if (plot == null)
			return null; // the shared province pool is exhausted
		plots.add(plot);
		return plot;
	}

	// resolve the improvement an on-plot firm raises on its plot (a necessity firm →
	// a FARM), looked up in the shared terrain registry; null for a center-grouped
	// occupant or a non-firm (which raise none).
	private Improvement improvementFor(PlotOccupant occupant) {
		if (terrainRegistry == null || !(occupant instanceof Firm f) || !f.occupiesPlot())
			return null;
		String type = f.plotImprovement();
		return type == null ? null : terrainRegistry.improvement(type);
	}

	// develop a plot for its occupant — raise the firm's improvement, clearing any
	// feature (a farm needs cleared land). A no-op for an occupant operating none.
	// Used on the founding genesis path (free); live growth develops via the builder.
	private void developPlot(Plot plot, PlotOccupant occupant) {
		Improvement imp = improvementFor(occupant);
		if (imp != null)
			plot.raiseImprovement(imp, true);
	}

	// the build-units to open a plot: the improvement's build cost (or, for an
	// occupant with none, the builder's flat land cost) plus the feature clear cost
	// when the plot is wild, scaled up by the terrain's percent build modifier (rough
	// or forested ground takes longer to prepare).
	private double clearanceWork(Plot plot, Improvement imp) {
		double base = imp != null ? imp.buildCost()
				: colony.getBuilder().getConfig().landWorkPerPlot();
		base += plot.clearCost();
		return base * (1.0 + plot.terrain().buildModifier() / 100.0);
	}

	// Whether the colony can seat another plot occupant — either a plot is vacant now,
	// or it can still grow into one (below max plots and it has a builder to grow). See
	// docs/plots.md.
	boolean hasRoomToExpand() {
		return firstVacantPlot() != null
				|| (colony.getBuilder() != null && canAcquirePlot(buildQueue.size()));
	}

	// the first vacant, workable plot, or null if none is free. Peaks are unworkable,
	// so they are never seated (they sit on the ladder as rough ground).
	private Plot firstVacantPlot() {
		for (Plot plot : plots)
			if (plot.isVacant() && plot.isWorkable())
				return plot;
		return null;
	}

	// Free the plot the given occupant held (or drop it from the pending queue if it
	// was still waiting on one).
	void vacatePlot(PlotOccupant occupant) {
		Plot plot = plotByOccupant.remove(occupant);
		if (plot != null) {
			plot.vacate();
			return;
		}
		pendingOccupants.remove(occupant);
	}

	// The terrain yield factor the given occupant's plot applies to its TFP in the
	// given sector (1.0 when the coupling does not apply — province-less colony,
	// non-food sector, or a center-grouped/unseated occupant). See docs/plots.md.
	double plotYieldFactor(PlotOccupant occupant, Sector sector) {
		if (province == null || sector != Sector.NECESSITY)
			return 1.0;
		Plot plot = plotByOccupant.get(occupant);
		return plot == null ? 1.0 : plot.yieldFactor(sector);
	}

	// The round-trip commute, in seconds, a worker pays to reach the given occupant's
	// plot — 2·T(index) on the travel-time ladder (0 for a province-less colony or a
	// center-grouped/unseated firm). See docs/plots.md.
	double plotTravelTime(PlotOccupant occupant) {
		if (province == null)
			return 0;
		Plot plot = plotByOccupant.get(occupant);
		return plot == null ? 0 : 2.0 * TravelLadder.oneWaySeconds(plot.index());
	}

	// queue the land-clearance work to open one more plot on behalf of one occupant.
	// The occupant funds its own plot (so each firm pays its own land clearance); an
	// occupant must be billable, which today means it is an Agent (the sole
	// PlotOccupant); this is the bridge where billing assumes that.
	private void requestGrowth(PlotOccupant requester) {
		// generate forward to the next workable plot, queueing any intervening peaks as
		// zero-work tasks (unworkable rough ground needs no building — it just lands on
		// the ladder, in order, via completeFinishedPlots). The workable plot's land
		// (terrain + feature) is fixed now so its clearance can be costed; the builder
		// raises the improvement and the colony seats the firm once the work is
		// delivered (see completeFinishedPlots).
		while (true) {
			if (!canAcquirePlot(buildQueue.size()))
				return; // no room to grow now — its province is full / the shared pool drained
			int nextIndex = plots.size() + buildQueue.size();
			Plot plot = generatePlot(nextIndex);
			if (plot == null)
				return; // the shared pool emptied between the check and the claim (a race)
			if (!plot.isWorkable()) {
				buildQueue.add(new BuildProject(plot, null, 0, null)); // a peak: no work
				continue;
			}
			Improvement imp = improvementFor(requester);
			buildQueue.add(new BuildProject(plot, imp, clearanceWork(plot, imp),
					(Agent) requester));
			return;
		}
	}

	// The unfinished construction tasks the builder is working through (the plots still
	// queued to open). The builder applies its build-units to these each step.
	List<BuildProject> activeProjects() {
		List<BuildProject> active = new ArrayList<BuildProject>();
		for (BuildProject p : buildQueue)
			if (!p.isComplete())
				active.add(p);
		return active;
	}

	// Append a plot for each completed clearance task and seat the occupants that were
	// waiting on them. Called by the builder each step after it applies its work.
	void completeFinishedPlots() {
		// the builder works the queue in order, so completed tasks sit at its head
		boolean grew = false;
		while (!buildQueue.isEmpty() && buildQueue.get(0).isComplete()) {
			BuildProject done = buildQueue.remove(0);
			Plot plot = done.getPlot();
			// the builder has cleared the land and raised the firm's improvement (a
			// FARM) — develop the plot before it is seated
			if (done.getImprovement() != null)
				plot.raiseImprovement(done.getImprovement(), true);
			plots.add(plot);
			grew = true;
		}
		if (grew)
			placePending();
	}

	// seat the waiting occupants onto newly-built (vacant) plots, in order
	private void placePending() {
		java.util.Iterator<PlotOccupant> it = pendingOccupants.iterator();
		while (it.hasNext()) {
			Plot plot = firstVacantPlot();
			if (plot == null)
				break;
			seat(plot, it.next());
			it.remove();
		}
	}

	// a dead colony no longer holds territory: return all its claimed plots to the
	// shared province pool so they are free for the other settlements in the province
	// (and any later founding). Vacate each first — a colony dies when its last laborer
	// is gone, while its firms (the plot occupants) outlive it, so the plots are still
	// occupied at death. A no-op for a province-less colony (it has no shared pool).
	void releasePlotsToPool() {
		ProvincePlotPool pool = plotPool();
		if (pool == null)
			return;
		for (Plot plot : plots) {
			plot.vacate();
			pool.release(plot);
		}
		plotByOccupant.clear();
	}
}
