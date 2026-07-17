package com.civstudio.server.command;

import com.civstudio.agent.ruler.Ruler;
import com.civstudio.server.HostedSession;
import com.civstudio.settlement.Settlement;

import lombok.extern.java.Log;

/**
 * The first interactive player action (see {@code docs/client-server.md} Phase B): set one of
 * the ruler's per-step tax levers on a colony. The rates already drive the treasury ({@link
 * Ruler#collectTaxes} taxes public-bank profit and noble income); this command lets a
 * spectator-turned-player move them at runtime.
 * <p>
 * <b>It names its colony</b> ({@code docs/spectator-lobby.md} Phase 2). A session can hold many
 * players' colonies — a royale Timeline does — so a player's lever must move <em>their</em>
 * colony and no one else's. A {@code null} colony means every ruler-bearing colony in the
 * session: the pre-Phase-2 behaviour, kept because it is what commands already persisted in the
 * log mean, and they must replay as they were issued.
 * <p>
 * It is a {@link GameCommand}, so it is tick-stamped and applied at the deterministic
 * <em>top of its tick</em>, before any colony acts — the mutation is a pure part of the
 * session's ordered command log, and two replays of the same log reach identical state. The
 * colony is named (not indexed) because names are drawn from the seed and so are stable across
 * a replay of the same spec.
 */
@Log
public final class SetTaxRateCommand implements GameCommand {

	/** Which tax lever this command moves. */
	public enum Lever {
		/** Fraction of each public bank's distributable profit skimmed into the treasury. */
		BANK_PROFIT,
		/** Fraction of each noble's income skimmed into the treasury. */
		NOBLE_INCOME
	}

	private final long tick;
	private final String colony;
	private final Lever lever;
	private final double rate;

	/**
	 * @param tick   the authoritative tick to apply on
	 * @param colony the colony whose lever this moves, or {@code null} for every ruler-bearing
	 *               colony in the session (what a pre-Phase-2 command in the log means)
	 * @param lever  which tax lever to set
	 * @param rate   the new rate (the ruler clamps it to [0, 1])
	 */
	public SetTaxRateCommand(long tick, String colony, Lever lever, double rate) {
		this.tick = tick;
		this.colony = colony;
		this.lever = lever;
		this.rate = rate;
	}

	/**
	 * A command that moves the lever on <b>every</b> colony in the session — the pre-Phase-2
	 * shape, for a single-colony run where naming the one colony adds nothing.
	 *
	 * @param tick  the authoritative tick to apply on
	 * @param lever which tax lever to set
	 * @param rate  the new rate (the ruler clamps it to [0, 1])
	 */
	public SetTaxRateCommand(long tick, Lever lever, double rate) {
		this(tick, null, lever, rate);
	}

	@Override
	public long tick() {
		return tick;
	}

	/** The colony this command moves, or {@code null} for all of them (for the persistence codec). */
	public String colony() {
		return colony;
	}

	/** Which lever this command sets (for the persistence codec). */
	public Lever lever() {
		return lever;
	}

	/** The target rate (for the persistence codec). */
	public double rate() {
		return rate;
	}

	@Override
	public void apply(HostedSession session) {
		for (Settlement c : session.colonies()) {
			// a named command moves ONE colony's lever: in a shared world the others belong to
			// other players, and their taxes are not this player's to set
			if (this.colony != null && !this.colony.equals(c.getName()))
				continue;
			Ruler ruler = c.getRuler();
			if (ruler == null)
				continue; // a ruler-less colony has no tax levers to move
			switch (lever) {
				case BANK_PROFIT -> ruler.setBankProfitTaxRate(rate);
				case NOBLE_INCOME -> ruler.setNobleIncomeTaxRate(rate);
			}
			log.info(() -> c.getName() + "'s ruler set " + lever + " tax rate to "
					+ String.format("%.3f", rate) + " (tick " + tick + ").");
		}
	}
}
