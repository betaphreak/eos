package com.civstudio.server.render;

import java.util.List;

import com.civstudio.server.command.CommandLog;
import com.civstudio.server.command.GameCommand;
import com.civstudio.server.command.SetTaxRateCommand;

/**
 * Projects a session's {@link CommandLog} to the render records — the single place that knows how a
 * {@link GameCommand} becomes a {@link CommandView}. Mirrors {@code CommandCodec}'s one known type.
 */
public final class CommandProjections {

	private CommandProjections() {
	}

	/**
	 * Project one command. A type without a codec still yields {@code {tick, type}} — the log must
	 * survive a command it does not recognise, since the log outlives any one build.
	 *
	 * @param c the command
	 * @return its typed row
	 */
	public static CommandView of(GameCommand c) {
		if (c instanceof SetTaxRateCommand s)
			return new CommandView(s.tick(), "setTaxRate", s.lever().name(), s.rate());
		return new CommandView(c.tick(), c.getClass().getSimpleName(), null, null);
	}

	/**
	 * Project a whole log: the applied history plus the count still in flight.
	 *
	 * @param log the session's command log
	 * @return the log's render view
	 */
	public static CommandLogView of(CommandLog log) {
		List<CommandView> history = log.history().stream().map(CommandProjections::of).toList();
		return new CommandLogView(history, log.pendingCount());
	}
}
