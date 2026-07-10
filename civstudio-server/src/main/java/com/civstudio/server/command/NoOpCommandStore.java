package com.civstudio.server.command;

import java.util.List;

/**
 * The default {@link CommandStore}: keeps nothing. Used when no datasource is configured, so
 * sessions run purely in-memory (the pre-persistence behavior) and nothing changes for local
 * runs, tests, or a deployment without a database.
 */
public final class NoOpCommandStore implements CommandStore {

	@Override
	public void append(String sessionId, GameCommand command) {
		// intentionally nothing — persistence is disabled
	}

	@Override
	public List<GameCommand> load(String sessionId) {
		return List.of();
	}
}
