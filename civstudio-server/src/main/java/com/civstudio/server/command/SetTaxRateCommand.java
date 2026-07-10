package com.civstudio.server.command;

import com.civstudio.agent.ruler.Ruler;
import com.civstudio.server.HostedSession;
import com.civstudio.settlement.Settlement;

import lombok.extern.java.Log;

/**
 * The first interactive player action (see {@code docs/client-server.md} Phase B): set one of
 * the ruler's per-step tax levers on every ruler-bearing colony in a session. The rates
 * already drive the treasury ({@link Ruler#collectTaxes} taxes public-bank profit and noble
 * income); this command lets a spectator-turned-player move them at runtime.
 * <p>
 * It is a {@link GameCommand}, so it is tick-stamped and applied at the deterministic
 * <em>top of its tick</em>, before any colony acts — the mutation is a pure part of the
 * session's ordered command log, and two replays of the same log reach identical state.
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
	private final Lever lever;
	private final double rate;

	/**
	 * @param tick  the authoritative tick to apply on
	 * @param lever which tax lever to set
	 * @param rate  the new rate (the ruler clamps it to [0, 1])
	 */
	public SetTaxRateCommand(long tick, Lever lever, double rate) {
		this.tick = tick;
		this.lever = lever;
		this.rate = rate;
	}

	@Override
	public long tick() {
		return tick;
	}

	@Override
	public void apply(HostedSession session) {
		for (Settlement colony : session.colonies()) {
			Ruler ruler = colony.getRuler();
			if (ruler == null)
				continue; // a ruler-less colony has no tax levers to move
			switch (lever) {
				case BANK_PROFIT -> ruler.setBankProfitTaxRate(rate);
				case NOBLE_INCOME -> ruler.setNobleIncomeTaxRate(rate);
			}
			log.info(() -> "Ruler set " + lever + " tax rate to "
					+ String.format("%.3f", rate) + " (tick " + tick + ").");
		}
	}
}
