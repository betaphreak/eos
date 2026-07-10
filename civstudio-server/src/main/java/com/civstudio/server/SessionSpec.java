package com.civstudio.server;

/**
 * The identity and founding parameters of a hosted session — the <b>savegame key</b>.
 * Because the engine is seed-reproducible, a session's whole run is a pure function of
 * this spec and its ordered {@link com.civstudio.server.command.CommandLog command log}
 * (see {@code docs/client-server.md}): the same spec replays deterministically, so the
 * spec + log <em>is</em> the resume format. The {@code seed} is the reproducibility root;
 * the {@code scenario} + {@code provinceId} pin the founding so two clients asking for the
 * same spec land in the same world.
 *
 * @param seed       the random-number seed — the run's reproducibility root
 * @param scenario   the founding scenario id (see {@link #CARAVAN_DEMO})
 * @param provinceId the world-map province the demo colony founds into
 */
public record SessionSpec(long seed, String scenario, int provinceId) {

	/** The Phase-A demo scenario: one standard colony plus six directed caravans. */
	public static final String CARAVAN_DEMO = "caravan-demo";

	public SessionSpec {
		if (scenario == null || scenario.isBlank())
			throw new IllegalArgumentException("a session spec needs a scenario id");
	}

	/**
	 * The stable, human-readable session id derived from this spec ({@code
	 * <scenario>-<seed>}) — the key the {@link SessionHost} registers under and a client
	 * subscribes to.
	 *
	 * @return the session id
	 */
	public String id() {
		return scenario + "-" + seed;
	}

	/** The Phase-A caravan demo spec at the given seed and founding province. */
	public static SessionSpec caravanDemo(long seed, int provinceId) {
		return new SessionSpec(seed, CARAVAN_DEMO, provinceId);
	}
}
