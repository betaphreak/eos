package com.civstudio.server.render;

import java.util.List;

/**
 * A read-only projection of the crown's <b>build queue</b> (see {@code
 * docs/build-queue-plan.md} B4/B6): what the ruler is building at the city center, how far
 * along it is, and what the player has ordered behind it.
 * <p>
 * The per-tick read side of the {@code queue_build} verb. The snapshot already carried the
 * <em>interrupt</em> ({@code awaitingBuildChoice} + candidates — the pause-and-choose modal);
 * this carries the queue itself, so the city screen can show it without waiting for the queue to
 * run dry.
 *
 * @param active     the catalog id being built at the center, or {@code null} while the queue is
 *                   idle
 * @param cost       the active item's total work in hammers ({@code 0} when idle)
 * @param remaining  the hammers still owed on it ({@code 0} when idle)
 * @param pending    the player-ordered ids waiting behind it, in queue order (empty on an
 *                   unattended colony — its heuristic picks one at a time)
 * @param ratePerDay trailing hammers donated to the queue per day (EWMA) — the divisor that turns
 *                   {@code remaining} into an ETA. {@code 0} for a colony donating nothing
 * @param awaiting   whether this colony's queue is awaiting a player choice (the B6 interrupt
 *                   predicate — true only on a seated session with the heuristic off)
 */
public record BuildQueueView(String active, double cost, double remaining, List<String> pending,
		double ratePerDay, boolean awaiting) {

	/** The projection for a colony with no build economy at all. */
	public static final BuildQueueView NONE =
			new BuildQueueView(null, 0, 0, List.of(), 0, false);
}
