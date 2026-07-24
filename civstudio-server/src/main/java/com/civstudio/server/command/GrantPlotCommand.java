package com.civstudio.server.command;

import java.util.List;

import com.civstudio.agent.Agent;
import com.civstudio.agent.noble.Noble;
import com.civstudio.server.HostedSession;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.Settlement;

import lombok.extern.java.Log;

/**
 * The <b>{@code grantPlot}</b> ruler decree (docs/estate-system.md P3): the crown <b>enfeoffs</b> a
 * plot to one of its nobles — the plot becomes that noble's fief (its palace is raised there) and
 * every household resident on it becomes the noble's vassal. The player-driven counterpart of the
 * auto-grant on ennoblement; both route through {@link Settlement#grantFief}.
 * <p>
 * A {@link GameCommand}: tick-stamped, applied at the deterministic top of its tick and persisted
 * through the {@link CommandCodec}, so a replay reproduces the same enfeoffment history. The colony
 * is named (seed-stable across replays), the plot is its index in {@link
 * Settlement#getDistrictPlots()}, and the noble is its agent id. A grant that no longer resolves
 * when its tick comes (the plot is gone, the noble has died) is a no-op — never a wedge.
 */
@Log
public final class GrantPlotCommand implements GameCommand {

	private final long tick;
	private final String colony;
	private final int plotIndex;
	private final int nobleId;

	/**
	 * @param tick      the authoritative tick to apply on
	 * @param colony    the colony whose plot is granted, or {@code null} for the one colony
	 * @param plotIndex the plot's index in {@link Settlement#getDistrictPlots()}
	 * @param nobleId   the agent id of the noble the plot is granted to
	 */
	public GrantPlotCommand(long tick, String colony, int plotIndex, int nobleId) {
		this.tick = tick;
		this.colony = colony;
		this.plotIndex = plotIndex;
		this.nobleId = nobleId;
	}

	@Override
	public long tick() {
		return tick;
	}

	/** The colony this grant acts on, or {@code null} for the one colony (for the codec). */
	public String colony() {
		return colony;
	}

	/** The granted plot's index in the colony's plot map (for the codec). */
	public int plotIndex() {
		return plotIndex;
	}

	/** The agent id of the noble the plot is granted to (for the codec). */
	public int nobleId() {
		return nobleId;
	}

	@Override
	public void apply(HostedSession session) {
		for (Settlement c : session.colonies()) {
			if (this.colony != null && !this.colony.equals(c.getName()))
				continue;
			List<Plot> plots = c.getDistrictPlots();
			if (plotIndex < 0 || plotIndex >= plots.size())
				continue; // the plot is gone / out of range — a stale grant is a no-op
			Noble noble = null;
			for (Agent a : c.getAgents())
				if (a.isAlive() && a instanceof Noble n && n.getID() == nobleId) {
					noble = n;
					break;
				}
			if (noble == null)
				continue; // the noble has died — nothing to enfeoff
			c.grantFief(plots.get(plotIndex), noble);
			final Noble granted = noble;
			log.info(() -> c.getName() + " granted plot " + plotIndex + " in fief to noble #"
					+ granted.getID() + " (tick " + tick + ").");
		}
	}
}
