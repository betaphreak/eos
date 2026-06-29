package com.civstudio.settlement;

import com.civstudio.geo.Terrain;

/**
 * One build <b>plot</b> in a {@link Settlement} — the occupiable unit of the
 * Civ4-style plot model that replaces the old disc {@code Slot}. A plot carries
 * its position on the travel-time ladder (its {@link #index()}), the {@link
 * Terrain} it sits on, and at most one {@link SlotOccupant} (a firm today; the
 * interface is the seam for housing and other buildings later). A plot is either
 * <b>vacant</b> ({@code occupant == null}) or taken by exactly one occupant.
 * <p>
 * In this cut (Phase 1) a plot's terrain is generated at founding/growth (see
 * {@link com.civstudio.geo.TerrainGenerator}) but is <b>not yet read</b> for
 * yield or travel — those couplings land in later phases (see {@code
 * docs/plots.md}). Occupancy is pure spatial bookkeeping: claiming or vacating a
 * plot moves no money and consumes no randomness.
 */
public final class Plot {

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
