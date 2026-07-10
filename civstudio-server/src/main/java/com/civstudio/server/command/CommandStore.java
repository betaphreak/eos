package com.civstudio.server.command;

import java.util.List;

/**
 * Durable storage for a session's tick-stamped command log (see {@link CommandLog}). Because a
 * session's whole run is a pure function of its {@link com.civstudio.server.SessionSpec spec}
 * and this ordered log, persisting the log <em>is</em> persisting the savegame: on a restart a
 * re-founded session {@linkplain #load(String) loads} its commands and replays them
 * deterministically.
 * <p>
 * Persistence is opt-in — with no datasource configured the server uses {@link NoOpCommandStore}
 * and sessions stay in-memory (the default). Configure {@code spring.datasource.url} (e.g. the
 * subscription's Postgres) to get the durable {@link JdbcCommandStore}.
 */
public interface CommandStore {

	/**
	 * Durably record a command submitted to a session, in submission order.
	 *
	 * @param sessionId the session the command belongs to
	 * @param command   the tick-stamped command
	 */
	void append(String sessionId, GameCommand command);

	/**
	 * Load a session's commands in applied order (the replay log), or an empty list if none.
	 *
	 * @param sessionId the session id
	 * @return the ordered commands to replay
	 */
	List<GameCommand> load(String sessionId);
}
