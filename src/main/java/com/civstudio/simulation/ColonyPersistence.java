package com.civstudio.simulation;

import com.civstudio.settlement.Settlement;

/**
 * The hook a persistence backend implements to attach itself to a freshly created
 * colony — installing a database-backed {@link com.civstudio.io.sink.RowSinkFactory}
 * on it and recording its identity. Kept in the core (no Spring dependency) so the
 * simulation compiles and runs without the database; the Spring Boot launcher
 * registers an implementation via {@link Persistence#setHandler}. When no handler
 * is registered the colony keeps its default CSV sink factory.
 */
public interface ColonyPersistence {

	/**
	 * Bind a newly created colony to the persistence backend, called from the
	 * {@link SimulationHarness#SimulationHarness(SimulationConfig, Settlement)
	 * harness constructor} before any printer is registered. An implementation
	 * typically records the run (once) and the colony, then installs a row-sink
	 * factory on the colony via {@link Settlement#setSinkFactory}. The run's seed is
	 * available via {@code colony.getSession().getSeed()}.
	 *
	 * @param colony the colony just created
	 * @param cfg    the run configuration (name, location, dates)
	 */
	void bind(Settlement colony, SimulationConfig cfg);
}
