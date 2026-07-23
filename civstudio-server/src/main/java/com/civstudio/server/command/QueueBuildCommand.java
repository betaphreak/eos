package com.civstudio.server.command;

import java.util.List;

import com.civstudio.server.HostedSession;
import com.civstudio.settlement.BuildEconomy;
import com.civstudio.settlement.Settlement;

import lombok.extern.java.Log;

/**
 * The <b>{@code queue_build}</b> player verb (docs/build-queue-plan.md B6): append
 * player-chosen building ids to a colony's ruler build queue — consumed ahead of the
 * heuristic, FIFO — or clear the pending orders first ({@code clear}, the
 * reorder-by-resubmit primitive). Ids that no longer qualify when their turn comes are
 * dropped at pick time by the engine, so a stale order can never wedge the queue.
 * <p>
 * A {@link GameCommand}: tick-stamped, applied at the deterministic top of its tick and
 * persisted through the {@link CommandCodec}, so a replay of the log reproduces the
 * exact same construction history. The colony is named (names are seed-stable across
 * replays); a {@code null} colony targets every build-economy colony (the
 * single-colony convenience, mirroring {@link SetTaxRateCommand}).
 */
@Log
public final class QueueBuildCommand implements GameCommand {

	private final long tick;
	private final String colony;
	private final List<String> items;
	private final boolean clear;

	/**
	 * @param tick   the authoritative tick to apply on
	 * @param colony the colony whose queue this feeds, or {@code null} for all
	 * @param items  the {@code BUILDING_*} ids to append, in order (may be empty with
	 *               {@code clear} — the pure-cancel shape)
	 * @param clear  whether to clear pending player orders before appending
	 */
	public QueueBuildCommand(long tick, String colony, List<String> items, boolean clear) {
		this.tick = tick;
		this.colony = colony;
		this.items = items == null ? List.of() : List.copyOf(items);
		this.clear = clear;
	}

	@Override
	public long tick() {
		return tick;
	}

	/** The colony this command feeds, or {@code null} for all (for the codec). */
	public String colony() {
		return colony;
	}

	/** The ordered building ids to append (for the codec). */
	public List<String> items() {
		return items;
	}

	/** Whether pending orders are cleared first (for the codec). */
	public boolean clear() {
		return clear;
	}

	@Override
	public void apply(HostedSession session) {
		for (Settlement c : session.colonies()) {
			if (this.colony != null && !this.colony.equals(c.getName()))
				continue;
			BuildEconomy be = c.getBuildEconomy();
			if (be == null)
				continue; // not a build-economy colony — nothing to feed
			if (clear)
				be.clearBuildOrders();
			if (!items.isEmpty())
				be.submitBuildOrders(items);
			log.info(() -> c.getName() + " queued " + items.size() + " building(s)"
					+ (clear ? " (queue cleared first)" : "") + " (tick " + tick + ").");
		}
	}
}
