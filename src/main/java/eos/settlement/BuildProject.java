package eos.settlement;

import eos.agent.Agent;
import lombok.Getter;

/**
 * One funded construction task in a {@link Settlement}'s build queue. Growing the
 * colony by one size (the ring from size {@code n} to {@code n+1}) splits into
 * three of these, by who pays for them:
 * <ul>
 * <li>a {@link Kind#LAND} task the <b>requesting firm</b> pays for — the effective
 * slots it will stand on;</li>
 * <li>{@link Kind#ROAD} and {@link Kind#WALL} tasks the <b>ruler</b> pays for —
 * the ring's public-works overhead.</li>
 * </ul>
 * A {@link eos.agent.firm.BuilderFirm} drains build-units off the queue and bills
 * each task's {@link #getSponsor() sponsor} for the work it delivers; the ring's
 * new effective slots only open ({@link Settlement#completeFinishedRings()}) once
 * all three of its tasks are complete. Like a {@link Slot}, a project is pure
 * bookkeeping until the builder works it.
 */
@Getter
public final class BuildProject {

	/** Which part of a growth ring a task builds (and so who sponsors it). */
	public enum Kind {
		/** Effective slots, funded by the firm that will occupy them. */
		LAND,
		/** Road overhead, funded by the ruler (public works). */
		ROAD,
		/** Wall overhead, funded by the ruler (public works). */
		WALL
	}

	// the size this task helps the colony reach (the ring n -> ringSize)
	private final int ringSize;

	// which part of the ring this task builds
	private final Kind kind;

	// total build-units this task requires, and how many are still outstanding
	private final double workTotal;
	private double workRemaining;

	// who pays the builder for this task (the requesting firm for LAND, the ruler
	// for ROAD/WALL)
	private final Agent sponsor;

	/**
	 * Create a construction task.
	 *
	 * @param ringSize
	 *            the size this task helps the colony reach
	 * @param kind
	 *            which part of the ring it builds
	 * @param work
	 *            the build-units it requires (clamped to {@code >= 0})
	 * @param sponsor
	 *            who pays the builder for it
	 */
	public BuildProject(int ringSize, Kind kind, double work, Agent sponsor) {
		this.ringSize = ringSize;
		this.kind = kind;
		this.workTotal = Math.max(0, work);
		this.workRemaining = this.workTotal;
		this.sponsor = sponsor;
	}

	/**
	 * Apply up to <tt>units</tt> of work to this task.
	 *
	 * @param units
	 *            build-units offered (negative is treated as zero)
	 * @return the build-units actually consumed (never more than {@link
	 *         #getWorkRemaining()})
	 */
	public double advance(double units) {
		double done = Math.min(Math.max(0, units), workRemaining);
		workRemaining -= done;
		return done;
	}

	/** Whether all of this task's work has been delivered. */
	public boolean isComplete() {
		return workRemaining <= 1e-9;
	}
}
