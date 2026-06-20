package com.civstudio.settlement;

import com.civstudio.agent.Agent;

/**
 * One <b>effective</b> build slot in a {@link Settlement}: a place a single
 * {@link SlotOccupant occupant} can stand on. Today the only occupants are firms
 * (which are {@link Agent}s, hence {@code SlotOccupant}s); the interface
 * is the seam for housing, a village hall and other buildings to occupy slots
 * later. A slot is either <b>vacant</b> ({@code occupant == null}) or taken by
 * exactly one occupant.
 * <p>
 * Slots are pure spatial bookkeeping — claiming or vacating one moves no money
 * and consumes no randomness — so adding them leaves the economic simulation
 * byte-identical.
 */
public final class Slot {

	// the occupant standing on this slot, or null if the slot is vacant
	private SlotOccupant occupant;

	/** Whether the slot is unoccupied. */
	public boolean isVacant() {
		return occupant == null;
	}

	/** The occupant on this slot, or {@code null} if it is vacant. */
	public SlotOccupant getOccupant() {
		return occupant;
	}

	/**
	 * Place <tt>occupant</tt> on this slot.
	 *
	 * @param occupant
	 *            the occupant to place (non-null)
	 * @throws IllegalStateException
	 *             if the slot is already occupied
	 */
	public void occupy(SlotOccupant occupant) {
		if (occupant == null)
			throw new IllegalArgumentException("occupant must be non-null");
		if (this.occupant != null)
			throw new IllegalStateException(
					"slot already occupied by " + this.occupant);
		this.occupant = occupant;
	}

	/** Free the slot, removing any occupant. */
	public void vacate() {
		occupant = null;
	}
}
