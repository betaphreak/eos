package com.civstudio.settlement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.civstudio.geo.Bonus;
import com.civstudio.geo.Feature;
import com.civstudio.geo.Improvement;
import com.civstudio.geo.PlotType;
import com.civstudio.geo.Terrain;
import com.civstudio.good.ResourceType;
import com.civstudio.tech.Sector;

/**
 * One build <b>plot</b> in a {@link Settlement} — the occupiable unit of the
 * Civ4-style plot model that replaces the old disc {@code Slot}. A plot carries
 * its position on the travel-time ladder (its {@link #index()}), the {@link
 * Terrain} it sits on, its {@link PlotType} relief (flat/hill/peak), an optional
 * wild {@link Feature} overlay, the {@link Improvement} an on-plot firm has raised
 * on it, the {@link Building center buildings} standing on it (distinct from the tile
 * improvement — see below), and at most one {@link PlotOccupant} (a firm today; the
 * interface is the seam for housing and other buildings later). A plot is either <b>vacant</b>
 * ({@code occupant == null}) or taken by exactly one occupant; a {@link
 * PlotType#PEAK peak} is {@link #isWorkable() unworkable} and never seated.
 * <p>
 * As of Phase 3 a plot's land is the three Civ4 legs: its base {@code terrain}, an
 * optional {@code feature} (forest/jungle/…), and the {@code improvement} a firm
 * builds on it (an {@code NFirm} raises a {@code FARM}). Raising a farm <b>clears</b>
 * any feature ({@link #isCleared()}), so a developed farm's {@link #yields()} are
 * terrain + improvement (+ a food {@link #bonus() resource}'s food yield, if any); an
 * <b>uncleared, feature-bearing</b> plot is {@link
 * #isWild() wild} — the seam the future forage firm works (a {@code CAMP} reading
 * terrain + feature, no clearing). The terrain {@link #yieldFactor(Sector) yield
 * factor} is read into the on-plot firm's TFP (food only this cut — {@link
 * Sector#NECESSITY}). Occupancy and development move no money and consume no
 * randomness. See {@code docs/plots.md}.
 */
public final class Plot {

	/**
	 * Per-yield-index <b>reference</b> the raw Food/Production/Commerce yields are
	 * divided by to get a TFP <b>multiplier</b> (not a raw yield): chosen so a plot
	 * at the reference yield lands at factor 1.0, bounding the disturbance to the
	 * model's existing calibration. {@code food} is calibrated so the default
	 * (Dhenijansar) colony's aggregate food TFP stays near its pre-rework value — as
	 * of Phase 3 against the <b>developed farm</b> (terrain food + {@code FARM} +2),
	 * so the reference is Dhenijansar's expected terrain food (≈ 1.4) plus the farm's
	 * +2 (see {@code docs/plots.md} <i>Calibration</i>); production/commerce are
	 * placeholders — they are dormant this cut (only food is live; see {@link
	 * Settlement#plotYieldFactor}).
	 */
	private static final double[] YIELD_REFERENCE = { 3.4, 2.0, 2.0 };

	/**
	 * The floor on a plot's yield factor — a small &epsilon; so a zero-yield plot
	 * (e.g. a desert with 0 food) produces poorly but not at literally zero.
	 */
	private static final double YIELD_FLOOR = 0.1;

	// the plot's position on the travel-time ladder (0 = the first plot; the commute
	// cost T(index) is read by plotTravelTime). For a legacy/province-less colony the
	// plot's own intrinsic claim order; for a plot drawn from a shared province pool
	// it is the per-settlement claim rank, assigned by setIndex when claimed (-1 until).
	private int index;

	// the plot's raster pixel position in the province silhouette, or -1 for a plot
	// not sourced from the province field (a legacy/province-less plot). See
	// docs/province-plots.md.
	private final int x;
	private final int y;

	// the resource on this plot (from the province field's bonus stage), or null. A
	// necessity-class (food) bonus adds its food yield in yields() — the live cut of the
	// bonus -> consumer-good connection (Bonus.resourceType()); enjoyment and strategic
	// bonuses stay dormant, as their sectors are gated off in Settlement.plotYieldFactor.
	private final Bonus bonus;

	// the river classification code on this plot (from the province field): 0 = no river;
	// low digit = width level 1..4 (narrow → wide), tens digit = downstream flow direction
	// 1..8 (0 = sink/mouth), hundreds digit = node marker (1 source, 2 confluence, 3 split) —
	// see ProvinceRaster.classifyRiver + RiverFlow / docs/river-rendering.md §1/§3. river()
	// reads it as a boolean for the caravan land-routing corridors, which ford any river at a
	// full day's march (see docs/land-routing.md / docs/caravan-march.md §6); riverWidth()/
	// flowDir()/riverNode() decode the fields (flowDir is the seam for river navigation).
	// 0 for a province-less plot.
	private final int river;

	// the real heightmap elevation (0..255) at this plot, or 0 for a province-less plot.
	// A raster lookup (from the province field), for hillshading and future elevation-
	// sensitive gameplay; orthogonal to plotType's flat/hill/peak class.
	private final int elevation;

	// the 8-bit sea mask on this plot — which of the 8 neighbours are water: low nibble = edges
	// (1=E,2=W,4=S,8=N), high nibble = corners (16=NW,32=NE,64=SE,128=SW); 0 = inland. From the
	// province field's global land/sea raster — the web draws the coastline from it (the corners
	// index the Civ4 coastscalemask blend) and it is the seam for future coastal gameplay
	// (ports / sea trade). See docs/coastlines.md. 0 for a province-less plot.
	private final int coast;

	// the settlement that has claimed this plot out of the shared province pool, or null
	// while the plot is free (province-owned). Hybrid ownership: claiming transfers the
	// plot to the settlement. Null for a legacy/province-less plot (no shared pool).
	private Settlement owner;

	// the ground this plot sits on (its base Food/Production/Commerce yield); the
	// baseline terrain for a province-less colony. Never null.
	private final Terrain terrain;

	// the plot's relief (flat/hill/peak), orthogonal to its terrain. A hill adds a
	// production bonus; a peak is unworkable. Never null (FLAT by default).
	private final PlotType plotType;

	// the wild feature overlaying the terrain (forest, jungle, …), or null if the
	// plot is bare. Fixed at generation; removed in effect once the plot is cleared
	// (the field is kept for the wild/cleared record — see isWild/isCleared).
	private final Feature feature;

	// the improvement a firm has raised on this plot (a FARM for a necessity firm),
	// or null until one is built — a durable land investment that survives the firm
	// that built it (a vacated developed plot keeps its improvement, so re-seating is
	// free). Set by raiseImprovement.
	private Improvement improvement;

	// whether the plot has been cleared for cultivation (its feature removed to raise
	// a farm). A wild plot is feature-bearing and not cleared; a forage CAMP is raised
	// without clearing, so cleared stays false there.
	private boolean cleared;

	// the occupant standing on this plot, or null if the plot is vacant
	private PlotOccupant occupant;

	// the center buildings standing on this plot — Civ4-style city buildings, distinct
	// from the single tile `improvement` leg above and from the `occupant` firm. Phase 1
	// tracks them but nothing populates the list and they never enter yields(), so the
	// data model lands byte-identical; the tech-gated auto-build trigger (which calls
	// addBuilding) and the building effect are later phases. See docs/plots.md.
	private final List<Building> buildings = new ArrayList<>();

	/**
	 * Create a vacant, undeveloped plot at the given ladder index.
	 *
	 * @param index    the plot's position on the travel-time ladder
	 * @param terrain  the ground it sits on (non-null)
	 * @param plotType the relief (flat/hill/peak; non-null)
	 * @param feature  the wild feature overlaying it, or {@code null} if bare
	 */
	public Plot(int index, Terrain terrain, PlotType plotType, Feature feature) {
		this(index, -1, -1, 0, terrain, plotType, feature, null, 0, 0);
	}

	/**
	 * Create a vacant, undeveloped plot at a raster position in a province field, with
	 * its resource — a plot for the shared province pool. Its ladder {@link #index()}
	 * is unset ({@code -1}) until a settlement claims it (see {@link #setIndex(int)}).
	 *
	 * @param x        the raster x of the plot in the province silhouette
	 * @param y        the raster y of the plot in the province silhouette
	 * @param river    the river classification code (0 = none; low digit width 1..4, tens
	 *                 digit node marker) — see {@link #riverCode()}
	 * @param terrain  the ground it sits on (non-null)
	 * @param plotType the relief (flat/hill/peak; non-null)
	 * @param feature   the wild feature overlaying it, or {@code null} if bare
	 * @param bonus     the resource on it, or {@code null}
	 * @param elevation the real heightmap elevation (0..255)
	 * @param coast     the 4-bit sea-edge mask (1=E, 2=W, 4=S, 8=N; 0 = inland)
	 */
	public Plot(int x, int y, int river, Terrain terrain, PlotType plotType,
			Feature feature, Bonus bonus, int elevation, int coast) {
		this(-1, x, y, river, terrain, plotType, feature, bonus, elevation, coast);
	}

	/**
	 * Create a vacant province-field plot with <b>no river</b> — the convenience overload
	 * for a plot on dry land (and for tests).
	 *
	 * @param x        the raster x of the plot in the province silhouette
	 * @param y        the raster y of the plot in the province silhouette
	 * @param terrain  the ground it sits on (non-null)
	 * @param plotType the relief (flat/hill/peak; non-null)
	 * @param feature  the wild feature overlaying it, or {@code null} if bare
	 * @param bonus    the resource on it, or {@code null}
	 */
	public Plot(int x, int y, Terrain terrain, PlotType plotType, Feature feature, Bonus bonus) {
		this(-1, x, y, 0, terrain, plotType, feature, bonus, 0, 0);
	}

	private Plot(int index, int x, int y, int river, Terrain terrain, PlotType plotType,
			Feature feature, Bonus bonus, int elevation, int coast) {
		if (terrain == null)
			throw new IllegalArgumentException("terrain must be non-null");
		if (plotType == null)
			throw new IllegalArgumentException("plotType must be non-null");
		this.index = index;
		this.x = x;
		this.y = y;
		this.river = river;
		this.elevation = elevation;
		this.coast = coast;
		this.terrain = terrain;
		this.plotType = plotType;
		this.feature = feature;
		this.bonus = bonus;
	}

	/** The plot's position on the travel-time ladder ({@code -1} until claimed, for a pool plot). */
	public int index() {
		return index;
	}

	/** Set the plot's ladder index — its claim rank, assigned when a settlement claims it. */
	public void setIndex(int index) {
		this.index = index;
	}

	/** The plot's raster x in the province silhouette, or {@code -1} if not from a province field. */
	public int x() {
		return x;
	}

	/** The plot's raster y in the province silhouette, or {@code -1} if not from a province field. */
	public int y() {
		return y;
	}

	/**
	 * The resource on this plot, or {@code null}. A necessity-class (food) bonus
	 * contributes its food yield to {@link #yields()}; enjoyment/strategic bonuses
	 * stay dormant (their sectors are gated off this cut).
	 */
	public Bonus bonus() {
		return bonus;
	}

	/** The settlement that has claimed this plot from the shared pool, or {@code null} if free. */
	public Settlement owner() {
		return owner;
	}

	/** Set (or clear, with {@code null}) the settlement owning this plot. Called by the province pool. */
	public void setOwner(Settlement owner) {
		this.owner = owner;
	}

	/** The ground this plot sits on. */
	public Terrain terrain() {
		return terrain;
	}

	/** The plot's relief (flat/hill/peak). */
	public PlotType plotType() {
		return plotType;
	}

	/** Whether a river runs through this plot (fording it costs a caravan a full day). */
	public boolean river() {
		return river != 0;
	}

	/**
	 * The packed river classification code on this plot: {@code 0} = no river; low digit =
	 * width level {@code 1..4}, tens digit = downstream flow direction {@code 1..8}, hundreds
	 * digit = node marker ({@code 1} source, {@code 2} confluence, {@code 3} split). Decode
	 * with {@link #riverWidth()}/{@link #flowDir()}/{@link #riverNode()}. Persisted for the
	 * web map (whose ribbon tapers by width) and, via {@code flowDir}, for river navigation;
	 * see {@link com.civstudio.geo.RiverFlow} and {@code docs/river-rendering.md} §1/§3.
	 */
	public int riverCode() {
		return river;
	}

	/** The river width level on this plot ({@code 0} = no river, {@code 1..4} narrow→wide). */
	public int riverWidth() {
		return river % 10;
	}

	/**
	 * The downstream flow direction on this plot as an 8-neighbour code {@code 1..8} (E, NE,
	 * N, NW, W, SW, S, SE), or {@code 0} for no river or a local sink/mouth. Derived by
	 * {@link com.civstudio.geo.RiverFlow} — the seam for caravan river-navigation.
	 */
	public int flowDir() {
		return (river / 10) % 10;
	}

	/** The river node marker ({@code 0} plain/none, {@code 1} source, {@code 2} confluence, {@code 3} split). */
	public int riverNode() {
		return river / 100;
	}

	/** The real heightmap elevation (0..255) at this plot; 0 for a province-less plot. */
	public int elevation() {
		return elevation;
	}

	/**
	 * The 8-bit sea mask on this plot: which of the 8 neighbours border water. Low nibble =
	 * orthogonal edges ({@code 1}=E, {@code 2}=W, {@code 4}=S, {@code 8}=N); high nibble =
	 * diagonal corners ({@code 16}=NW, {@code 32}=NE, {@code 64}=SE, {@code 128}=SW), which
	 * index the Civ4 coastscalemask blend ({@code coast >> 4}). {@code 0} = inland. Persisted
	 * for the web coastline and the seam for coastal gameplay. See {@code docs/coastlines.md}.
	 */
	public int coast() {
		return coast;
	}

	/** Whether this plot borders water on any edge (a coastal plot). */
	public boolean isCoastal() {
		return coast != 0;
	}

	/** Whether a firm can occupy this plot (false for a {@link PlotType#PEAK peak}). */
	public boolean isWorkable() {
		return plotType.isWorkable();
	}

	/** The wild feature overlaying the terrain, or {@code null} if the plot is bare. */
	public Feature feature() {
		return feature;
	}

	/** The improvement raised on this plot, or {@code null} if undeveloped. */
	public Improvement improvement() {
		return improvement;
	}

	/** Whether the plot has been cleared for cultivation (its feature removed). */
	public boolean isCleared() {
		return cleared;
	}

	/**
	 * Whether the plot is <b>wild</b>: it carries a feature and has not been cleared
	 * — the uncleared, feature-bearing land the future forage firm works (a {@code
	 * CAMP} gathering off it, no clearing). A bare plot or a cleared farm is not wild.
	 *
	 * @return {@code true} if the plot is uncleared and feature-bearing
	 */
	public boolean isWild() {
		return feature != null && !cleared;
	}

	/**
	 * The work to <b>clear</b> this plot's feature (the Civ4 feature-removal cost), or
	 * {@code 0} if the plot is bare or already cleared. Added to the build cost when a
	 * farm is raised on feature-bearing land.
	 *
	 * @return the feature-clearance work, or 0 if there is nothing to clear
	 */
	public double clearCost() {
		return isWild() ? feature.clearCost() : 0;
	}

	/**
	 * Raise an improvement on this plot, optionally <b>clearing</b> its feature first
	 * (a farm needs cleared land; a forage camp does not). Once developed the plot's
	 * {@link #yields()} include the improvement.
	 *
	 * @param improvement  the improvement built (a {@code FARM}, {@code CAMP}, …)
	 * @param clearFeature whether raising it clears the plot's feature
	 */
	public void raiseImprovement(Improvement improvement, boolean clearFeature) {
		this.improvement = improvement;
		if (clearFeature)
			this.cleared = true;
	}

	/**
	 * The plot's raw {@code [food, production, commerce]} yield triple: its terrain,
	 * plus the {@link PlotType#productionBonus() hill production bonus}, plus the
	 * feature's yield change while the plot is still {@link #isWild() wild} (a cleared
	 * plot's feature is gone), plus the {@link #improvement() improvement}'s yield
	 * change once one is built, plus a {@link #bonus() resource}'s food yield when it
	 * is a necessity-class (food) bonus. (The hill production bonus and non-food
	 * bonuses are dormant until a production/commerce firm sits on a plot — only food
	 * is live this cut.)
	 *
	 * @return the plot's yields (a fresh length-3 array)
	 */
	public int[] yields() {
		int[] out = terrain.yields().clone();
		out[1] += plotType.productionBonus();
		if (isWild())
			for (int i = 0; i < 3; i++)
				out[i] += feature.yieldChange(i);
		if (improvement != null)
			for (int i = 0; i < 3; i++)
				out[i] += improvement.yieldChange(i);
		// a food (necessity-class) bonus adds its food yield — the live cut of the
		// bonus -> consumer-good connection (BonusClass.resourceType()), so a necessity
		// firm on wheat/cattle/fish land is the more productive for it. Enjoyment and
		// strategic bonuses stay dormant, as their sectors are in plotYieldFactor.
		if (bonus != null && bonus.resourceType() == ResourceType.NECESSITY)
			out[0] += bonus.yieldChange(0);
		return out;
	}

	/**
	 * The plot's <b>TFP multiplier</b> for a sector — its raw yield in that sector's
	 * Civ4 yield channel (Food for {@link Sector#NECESSITY}, Production for {@link
	 * Sector#CAPITAL}, Commerce for {@link Sector#EXPORT}/{@link Sector#ENJOYMENT})
	 * divided by the per-index {@link #YIELD_REFERENCE reference}, floored at {@link
	 * #YIELD_FLOOR}. A plot at the reference yield gives factor 1.0. The staging of
	 * which sectors are live (only food this cut) is applied by the caller — see
	 * {@link Settlement#plotYieldFactor}.
	 *
	 * @param sector the firm's sector
	 * @return the plot's yield factor for that sector
	 */
	public double yieldFactor(Sector sector) {
		int idx = yieldIndex(sector);
		return Math.max(YIELD_FLOOR, yields()[idx] / YIELD_REFERENCE[idx]);
	}

	// the Civ4 yield channel (0 = food, 1 = production, 2 = commerce) a sector reads
	private static int yieldIndex(Sector sector) {
		return switch (sector) {
			case NECESSITY -> 0;
			case CAPITAL -> 1;
			case EXPORT, ENJOYMENT -> 2;
		};
	}

	/** Whether the plot is unoccupied. */
	public boolean isVacant() {
		return occupant == null;
	}

	/** The occupant on this plot, or {@code null} if it is vacant. */
	public PlotOccupant getOccupant() {
		return occupant;
	}

	/**
	 * Place <tt>occupant</tt> on this plot.
	 *
	 * @param occupant
	 *            the occupant to place (non-null)
	 * @throws IllegalStateException
	 *             if the plot is already occupied
	 */
	public synchronized void occupy(PlotOccupant occupant) {
		if (occupant == null)
			throw new IllegalArgumentException("occupant must be non-null");
		if (this.occupant != null)
			throw new IllegalStateException(
					"plot already occupied by " + this.occupant);
		this.occupant = occupant;
	}

	/**
	 * Atomically place <tt>occupant</tt> if the plot is vacant — the thread-safe
	 * check-then-act for transient claims that bypass the pool's synchronized claim
	 * path (a caravan's nightly camp). Two bands camping in the same province the
	 * same night thus cannot double-occupy a plot; the loser simply tries the next.
	 *
	 * @param occupant
	 *            the occupant to place (non-null)
	 * @return whether the plot was vacant and is now occupied by {@code occupant}
	 */
	public synchronized boolean tryOccupy(PlotOccupant occupant) {
		if (occupant == null)
			throw new IllegalArgumentException("occupant must be non-null");
		if (this.occupant != null)
			return false;
		this.occupant = occupant;
		return true;
	}

	/** Free the plot, removing any occupant. */
	public synchronized void vacate() {
		occupant = null;
	}

	/**
	 * The {@link Building center buildings} standing on this plot — an unmodifiable live
	 * view. Distinct from the single tile {@link #improvement()} leg: buildings are
	 * Civ4-style city buildings with no {@link #yields()} footprint (their effect is
	 * deferred — see {@code docs/plots.md}). Empty until {@link #addBuilding} places one;
	 * in this phase nothing does.
	 *
	 * @return the buildings on this plot (possibly empty, never {@code null})
	 */
	public List<Building> buildings() {
		return Collections.unmodifiableList(buildings);
	}

	/**
	 * Whether a building with the given eos-native id stands on this plot.
	 *
	 * @param id the building id
	 * @return {@code true} if such a building is present
	 */
	public boolean hasBuilding(String id) {
		for (Building b : buildings)
			if (b.id().equals(id))
				return true;
		return false;
	}

	/**
	 * Add a center building to this plot. The tech-gated auto-build trigger (a later
	 * phase) calls this once a building's prerequisite is met; nothing does yet, so the
	 * collection stays empty in this phase and the data model is byte-identical.
	 *
	 * @param building the building to place (non-null)
	 */
	public void addBuilding(Building building) {
		if (building == null)
			throw new IllegalArgumentException("building must be non-null");
		buildings.add(building);
	}
}
