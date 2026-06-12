package eos.settlement;

import eos.agent.Agent;

/**
 * One <b>effective</b> build slot in a {@link Settlement}: a place a single
 * occupant can stand on. Today the only occupants are firms (which are {@link
 * Agent}s), so the occupant is typed as an {@code Agent}; when permanent
 * housing and other buildings arrive this will widen to a common occupant
 * interface. A slot is either <b>vacant</b> ({@code occupant == null}) or taken
 * by exactly one occupant.
 * <p>
 * Slots are pure spatial bookkeeping — claiming or vacating one moves no money
 * and consumes no randomness — so adding them leaves the economic simulation
 * byte-identical.
 */
public final class Slot {

	// the occupant standing on this slot, or null if the slot is vacant
	private Agent occupant;

	/** Whether the slot is unoccupied. */
	public boolean isVacant() {
		return occupant == null;
	}

	/** The occupant on this slot, or {@code null} if it is vacant. */
	public Agent getOccupant() {
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
	public void occupy(Agent occupant) {
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
