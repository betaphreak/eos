package com.civstudio.settlement;

import com.civstudio.geo.Terrain;
import com.civstudio.tech.Sector;

/**
 * One build <b>plot</b> in a {@link Settlement} — the occupiable unit of the
 * Civ4-style plot model that replaces the old disc {@code Slot}. A plot carries
 * its position on the travel-time ladder (its {@link #index()}), the {@link
 * Terrain} it sits on, and at most one {@link SlotOccupant} (a firm today; the
 * interface is the seam for housing and other buildings later). A plot is either
 * <b>vacant</b> ({@code occupant == null}) or taken by exactly one occupant.
 * <p>
 * As of Phase 2 the plot's terrain {@link #yieldFactor(Sector) yield factor} is
 * read into the on-plot firm's TFP (food only this cut — {@link Sector#NECESSITY});
 * the travel-time coupling is still Phase 2b. Occupancy is pure spatial
 * bookkeeping: claiming or vacating a plot moves no money and consumes no
 * randomness. See {@code docs/plots.md}.
 */
public final class Plot {

	/**
	 * Per-yield-index <b>reference</b> the raw Food/Production/Commerce yields are
	 * divided by to get a TFP <b>multiplier</b> (not a raw yield): chosen so a plot
	 * at the reference yield lands at factor 1.0, bounding the disturbance to the
	 * model's existing calibration. {@code food} is calibrated so the default
	 * (Dhenijansar) colony's aggregate food TFP stays near its pre-rework value (see
	 * {@code docs/plots.md} <i>Calibration</i>); production/commerce are placeholders
	 * — they are dormant this cut (only food is live; see {@link
	 * Settlement#plotYieldFactor}).
	 */
	private static final double[] YIELD_REFERENCE = { 1.4, 2.0, 2.0 };

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

	// the occupant standing on this plot, or null if the plot is vacant
	private SlotOccupant occupant;

	/**
	 * Create a vacant plot at the given ladder index on the given terrain.
	 *
	 * @param index   the plot's position on the travel-time ladder
	 * @param terrain the ground it sits on (non-null)
	 */
	public Plot(int index, Terrain terrain) {
		if (terrain == null)
			throw new IllegalArgumentException("terrain must be non-null");
		this.index = index;
		this.terrain = terrain;
	}

	/** The plot's position on the travel-time ladder. */
	public int index() {
		return index;
	}

	/** The ground this plot sits on. */
	public Terrain terrain() {
		return terrain;
	}

	/**
	 * The plot's raw {@code [food, production, commerce]} yield triple. In this cut
	 * that is just the terrain's yield; hill bonus, feature and improvement
	 * contributions are added in later phases.
	 *
	 * @return the plot's yields (length 3)
	 */
	public int[] yields() {
		return terrain.yields();
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
	public SlotOccupant getOccupant() {
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
	public void occupy(SlotOccupant occupant) {
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
