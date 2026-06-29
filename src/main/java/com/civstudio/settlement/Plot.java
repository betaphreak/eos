package com.civstudio.settlement;

import com.civstudio.geo.Feature;
import com.civstudio.geo.Improvement;
import com.civstudio.geo.PlotType;
import com.civstudio.geo.Terrain;
import com.civstudio.tech.Sector;

/**
 * One build <b>plot</b> in a {@link Settlement} — the occupiable unit of the
 * Civ4-style plot model that replaces the old disc {@code Slot}. A plot carries
 * its position on the travel-time ladder (its {@link #index()}), the {@link
 * Terrain} it sits on, its {@link PlotType} relief (flat/hill/peak), an optional
 * wild {@link Feature} overlay, the {@link Improvement} an on-plot firm has raised
 * on it, and at most one {@link PlotOccupant} (a firm today; the interface is the
 * seam for housing and other buildings later). A plot is either <b>vacant</b>
 * ({@code occupant == null}) or taken by exactly one occupant; a {@link
 * PlotType#PEAK peak} is {@link #isWorkable() unworkable} and never seated.
 * <p>
 * As of Phase 3 a plot's land is the three Civ4 legs: its base {@code terrain}, an
 * optional {@code feature} (forest/jungle/…), and the {@code improvement} a firm
 * builds on it (an {@code NFirm} raises a {@code FARM}). Raising a farm <b>clears</b>
 * any feature ({@link #isCleared()}), so a developed farm's {@link #yields()} are
 * terrain + improvement; an <b>uncleared, feature-bearing</b> plot is {@link
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

	// the plot's position on the travel-time ladder (0 = the first plot; the
	// commute cost T(index) is read in a later phase). Assigned in claim order.
	private final int index;

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

	/**
	 * Create a vacant, undeveloped plot at the given ladder index.
	 *
	 * @param index    the plot's position on the travel-time ladder
	 * @param terrain  the ground it sits on (non-null)
	 * @param plotType the relief (flat/hill/peak; non-null)
	 * @param feature  the wild feature overlaying it, or {@code null} if bare
	 */
	public Plot(int index, Terrain terrain, PlotType plotType, Feature feature) {
		if (terrain == null)
			throw new IllegalArgumentException("terrain must be non-null");
		if (plotType == null)
			throw new IllegalArgumentException("plotType must be non-null");
		this.index = index;
		this.terrain = terrain;
		this.plotType = plotType;
		this.feature = feature;
	}

	/** The plot's position on the travel-time ladder. */
	public int index() {
		return index;
	}

	/** The ground this plot sits on. */
	public Terrain terrain() {
		return terrain;
	}

	/** The plot's relief (flat/hill/peak). */
	public PlotType plotType() {
		return plotType;
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
	 * change once one is built. (The hill production bonus is dormant until a
	 * production firm sits on a plot — only food is live this cut.)
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
	public void occupy(PlotOccupant occupant) {
		if (occupant == null)
			throw new IllegalArgumentException("occupant must be non-null");
		if (this.occupant != null)
			throw new IllegalStateException(
					"plot already occupied by " + this.occupant);
		this.occupant = occupant;
	}

	/** Free the plot, removing any occupant. */
	public void vacate() {
		occupant = null;
	}
}
