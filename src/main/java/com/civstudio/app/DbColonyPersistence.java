package com.civstudio.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.civstudio.io.sink.CompositeRowSinkFactory;
import com.civstudio.io.sink.CsvRowSinkFactory;
import com.civstudio.io.sink.OutputMode;
import com.civstudio.io.sink.RowSinkFactory;
import com.civstudio.io.sink.jdbc.JdbcRowSinkFactory;
import com.civstudio.simulation.ColonyPersistence;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.settlement.Settlement;

/**
 * The database-backed {@link ColonyPersistence}: records the run once and each
 * colony, then installs a {@link JdbcRowSinkFactory} (or a {@link
 * CompositeRowSinkFactory} for {@link OutputMode#BOTH}) on the colony so its
 * printers write to Postgres. Registered with {@link
 * com.civstudio.simulation.Persistence} by {@link SimRunner} for the duration of a
 * run; Spring injects the pooled {@link JdbcTemplate}.
 */
@Component
public class DbColonyPersistence implements ColonyPersistence {

	private final JdbcTemplate jdbc;
	private final OutputMode outputMode;

	// the scenario name, set by the runner before the run; recorded on the run row
	private volatile String scenario = "unknown";
	// the run row id, created once (lazily) on the first colony bound this run
	private volatile Long runId;
	private final Object runLock = new Object();

	public DbColonyPersistence(JdbcTemplate jdbc,
			@Value("${eos.output-mode:BOTH}") OutputMode outputMode) {
		this.jdbc = jdbc;
		this.outputMode = outputMode;
	}

	/** Record the scenario name for the run row (called before the run starts). */
	public void setScenario(String scenario) {
		this.scenario = scenario;
	}

	@Override
	public void bind(Settlement colony, SimulationConfig cfg) {
		long rid = ensureRun(colony.getSession().getSeed());
		long cid = jdbc.queryForObject(
				"INSERT INTO settlements(run_id, name, founding_date, latitude, longitude)"
						+ " VALUES (?, ?, ?, ?, ?) RETURNING id",
				Long.class, rid, colony.getName(), cfg.startDate(), cfg.latitude(),
				cfg.longitude());

		RowSinkFactory jdbcFactory = new JdbcRowSinkFactory(jdbc, rid, cid);
		RowSinkFactory factory = outputMode == OutputMode.BOTH
				? new CompositeRowSinkFactory(new CsvRowSinkFactory(), jdbcFactory)
				: jdbcFactory;
		colony.setSinkFactory(factory);
	}

	// insert the run row once per JVM run (the first colony bound triggers it),
	// returning its generated id; subsequent colonies of the same run share it
	private long ensureRun(long seed) {
		Long r = runId;
		if (r != null)
			return r;
		synchronized (runLock) {
			if (runId == null)
				runId = jdbc.queryForObject(
						"INSERT INTO runs(scenario, seed) VALUES (?, ?) RETURNING id",
						Long.class, scenario, seed);
			return runId;
		}
	}

	/** Stamp the run row finished, if one was created. */
	public void finish() {
		Long r = runId;
		if (r != null)
			jdbc.update("UPDATE runs SET finished_at = now() WHERE id = ?", r);
	}
}
