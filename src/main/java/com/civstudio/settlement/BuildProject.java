package com.civstudio.settlement;

import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.Agent;
import lombok.Getter;

/**
 * One funded construction task in a {@link Settlement}'s build queue: the work to
 * open a single new {@link Plot} (clear its land and prepare it for an occupant),
 * funded by the firm that requested it. Under the plot model a colony grows one
 * plot at a time, so each project corresponds to exactly one plot to append (the
 * disc model's per-ring {@code ROAD}/{@code WALL} public works are gone, so the
 * ruler no longer funds growth — it is now fully firm-funded).
 * <p>
 * A {@link BuilderFirm} drains build-units off the queue and bills each task's
 * {@link #getSponsor() sponsor} for the work it delivers; the plot is appended and
 * its waiting occupant seated ({@link Settlement#completeFinishedPlots()}) once the
 * task is complete. Like a {@link Plot}, a project is pure bookkeeping until the
 * builder works it.
 */
@Getter
public final class BuildProject {

	// the ladder index of the plot this task opens (the next plot appended)
	private final int plotIndex;

	// total build-units this task requires, and how many are still outstanding
	private final double workTotal;
	private double workRemaining;

	// who pays the builder for this task (the firm that requested the plot)
	private final Agent sponsor;

	/**
	 * Create a construction task to open one plot.
	 *
	 * @param plotIndex
	 *            the ladder index of the plot this task opens
	 * @param work
	 *            the build-units it requires (clamped to {@code >= 0})
	 * @param sponsor
	 *            who pays the builder for it (the requesting firm)
	 */
	public BuildProject(int plotIndex, double work, Agent sponsor) {
		this.plotIndex = plotIndex;
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
