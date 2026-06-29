package com.civstudio.settlement;

import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.Agent;
import com.civstudio.geo.Improvement;
import lombok.Getter;

/**
 * One funded construction task in a {@link Settlement}'s build queue: the work to
 * open a single new {@link Plot} — clear any feature and raise the {@link
 * Improvement} the requesting firm operates (an {@code NFirm} → a {@code FARM}) —
 * funded by that firm. Under the plot model a colony grows one plot at a time, so
 * each project corresponds to exactly one plot to append (the disc model's per-ring
 * {@code ROAD}/{@code WALL} public works are gone, so the ruler no longer funds
 * growth — it is now fully firm-funded).
 * <p>
 * The {@link #getPlot() plot} (its terrain + feature) is fixed when the task is
 * queued, so its {@link #getWorkTotal() work total} can be costed from the
 * improvement's build cost plus the feature's clear cost, scaled by the terrain's
 * build modifier (see {@link Settlement#requestGrowth}). A {@link BuilderFirm} drains
 * build-units off the queue and bills each task's {@link #getSponsor() sponsor} for
 * the work it delivers; once complete the plot is developed (its improvement raised),
 * appended, and its waiting occupant seated ({@link
 * Settlement#completeFinishedPlots()}). Like a {@link Plot}, a project is pure
 * bookkeeping until the builder works it.
 */
@Getter
public final class BuildProject {

	// the plot this task opens — its land (terrain + feature) is fixed at queue time
	private final Plot plot;

	// the improvement to raise on completion (a FARM for a necessity firm), or null
	// if the requester operates none
	private final Improvement improvement;

	// total build-units this task requires, and how many are still outstanding
	private final double workTotal;
	private double workRemaining;

	// who pays the builder for this task (the firm that requested the plot)
	private final Agent sponsor;

	/**
	 * Create a construction task to open one plot.
	 *
	 * @param plot
	 *            the plot this task opens (its terrain + feature fixed)
	 * @param improvement
	 *            the improvement to raise on completion, or {@code null}
	 * @param work
	 *            the build-units it requires (clamped to {@code >= 0})
	 * @param sponsor
	 *            who pays the builder for it (the requesting firm)
	 */
	public BuildProject(Plot plot, Improvement improvement, double work, Agent sponsor) {
		this.plot = plot;
		this.improvement = improvement;
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
