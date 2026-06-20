package com.civstudio.simulation;

import com.civstudio.settlement.Settlement;

/**
 * The process-wide registration point for a {@link ColonyPersistence} backend. By
 * default no handler is set, so {@link #bind} is a no-op and colonies keep their
 * default CSV output — plain {@code main()} runs and the test suite are unaffected.
 * The Spring Boot launcher installs a database-backed handler via {@link
 * #setHandler} before invoking a scenario, so every colony the scenario creates is
 * persisted with no per-scenario wiring.
 */
public final class Persistence {

	private static volatile ColonyPersistence handler;

	private Persistence() {
	}

	/**
	 * Register the persistence backend (or {@code null} to disable).
	 *
	 * @param h the handler
	 */
	public static void setHandler(ColonyPersistence h) {
		handler = h;
	}

	/** Whether a persistence backend is currently registered. */
	public static boolean isActive() {
		return handler != null;
	}

	/**
	 * Offer a freshly created colony to the registered backend, if any. Called by
	 * the {@link SimulationHarness} constructor.
	 *
	 * @param colony the colony just created
	 * @param cfg    the run configuration
	 */
	public static void bind(Settlement colony, SimulationConfig cfg) {
		ColonyPersistence h = handler;
		if (h != null)
			h.bind(colony, cfg);
	}
}
