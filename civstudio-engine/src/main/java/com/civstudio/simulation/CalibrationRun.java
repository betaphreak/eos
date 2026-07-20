package com.civstudio.simulation;

import java.time.LocalDate;
import java.util.function.UnaryOperator;

import com.civstudio.agent.RetinueConfig;
import com.civstudio.balance.BalanceProfile;
import com.civstudio.era.Era;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.io.SimLog;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.RowSink;
import com.civstudio.io.sink.RowSinkFactory;
import com.civstudio.settlement.Settlement;

/**
 * Runs one standard (ruler-bearing) colony headless to a horizon and reports its outcome — the
 * reusable core of the MCP {@code run_scenario} calibration tool (see {@code docs/mcp-server.md}
 * Phase 1). It is the developer parameter-sweep founding pattern (the retired {@code CalibrationSweep},
 * now the MCP {@code sweep} tool) made parameterizable: found a standard colony at a given {@code
 * (seed, province)} with a {@link SimulationConfig} (and optional {@link RetinueConfig} food levers),
 * install a {@link RowSinkFactory} so the printers' typed time series lands wherever the caller wants
 * (the SQL run store, CSV, or both), run, and read back the collapse outcome.
 *
 * <p>Deterministic in {@code seed} — the store it writes is a reporting mirror, so it never perturbs
 * an RNG stream. The default sink stays CSV; a caller opts into SQL by passing a JDBC-backed factory.
 */
public final class CalibrationRun {

	// the schema of the mirrored event log (the factory prefixes run_id/seed/scenario)
	private static final ColumnSpec[] LOG_COLUMNS = {
			ColumnSpec.text("Date"), ColumnSpec.integer("Level"),
			ColumnSpec.text("Severity"), ColumnSpec.text("Message")
	};

	private CalibrationRun() {
	}

	// JUL level → severity label (WARNING = 900, SEVERE = 1000), matching SessionEventLog
	private static String severity(int level) {
		return level >= 1000 ? "error" : level >= 900 ? "warn" : "info";
	}

	private static void closeQuietly(AutoCloseable c) {
		if (c == null)
			return;
		try {
			c.close();
		} catch (Exception ignored) {
			// unsubscribe/close is best-effort
		}
	}

	/**
	 * The outcome of one run.
	 *
	 * @param finalDate  the in-game date the run ended on (the last simulated day)
	 * @param died       whether the colony collapsed before the horizon
	 * @param deathDate  the collapse date, or {@code null} if it survived
	 * @param laborers   living laborer households at the end
	 * @param firms      living firms at the end
	 */
	public record Result(LocalDate finalDate, boolean died, LocalDate deathDate,
			int laborers, int firms) {
	}

	/**
	 * Found a standard colony and run it.
	 *
	 * @param cfg        the run configuration (overrides already applied to a base)
	 * @param seed       the run's seed — same seed ⇒ identical run
	 * @param provinceId the world-map province to found into
	 * @param retinue    optional peasant-pool config (food levers); {@code null} = default
	 * @param sink       the row-sink factory the printers write through; {@code null} = the
	 *                   colony's default (CSV)
	 * @param steps      days to run; {@code <= 0} runs the config's full duration
	 * @return the run outcome
	 */
	public static Result run(SimulationConfig cfg, long seed, int provinceId,
			RetinueConfig retinue, RowSinkFactory sink, int steps) {
		return run(cfg, seed, provinceId, retinue, null, sink, steps);
	}

	/**
	 * As {@link #run(SimulationConfig, long, int, RetinueConfig, RowSinkFactory, int)}, tuning the
	 * colony's own economy before it founds.
	 *
	 * @param economy optional adjustment to the colony's {@linkplain Settlement#getEconomy()
	 *                economy} — the home of the price/balance/tax/pool numbers since they stopped
	 *                riding the run config; {@code null} leaves the colony on its race's cell
	 */
	public static Result run(SimulationConfig cfg, long seed, int provinceId,
			RetinueConfig retinue, UnaryOperator<Era.Economy> economy, RowSinkFactory sink,
			int steps) {
		return run(cfg, seed, provinceId, retinue, economy, null, sink, steps);
	}

	/**
	 * As {@link #run(SimulationConfig, long, int, RetinueConfig, UnaryOperator, RowSinkFactory, int)},
	 * founding on a named {@link BalanceProfile} — the agent-behaviour tuning.
	 *
	 * <p>Application order is base-to-specific: the profile is the founding bundle, then the economy
	 * tuning (a separate axis — the profile does not carry the economy), then the ad-hoc {@code
	 * retinue} override on top (the profile's own retinue config is its floor). So a caller can pick a
	 * profile <em>and</em> nudge one peasant-pool lever without the two fighting.
	 *
	 * @param profile optional agent-behaviour tuning; {@code null} leaves the colony on the defaults
	 */
	public static Result run(SimulationConfig cfg, long seed, int provinceId,
			RetinueConfig retinue, UnaryOperator<Era.Economy> economy, BalanceProfile profile,
			RowSinkFactory sink, int steps) {
		SimulationHarness h = SimulationHarness.create(cfg, seed, provinceId);
		if (profile != null)
			h.setBalanceProfile(profile);
		if (economy != null)
			h.tuneEconomy(economy);
		if (retinue != null)
			h.setRetinueConfig(retinue);
		Settlement colony = h.getColony();
		// install the sink before founding, so the printers (bound in addCommonPrinters) use it and
		// the sim_log tap below can be opened up front to catch the founding lines too
		if (sink != null)
			colony.setSinkFactory(sink);

		// mirror the run's event log into the store as a "sim_log" table (Date/Level/Severity/Message
		// + the factory's run identity), so get_event_log can read a finished run — the same SimLog
		// tap HostedSession uses for the live feed. Written through the same sink (CSV + DB).
		RowSink logSink = sink == null ? null : sink.create("sim_log", "sim_log.csv", LOG_COLUMNS);
		AutoCloseable logTap = logSink == null ? null : SimLog.tap(colony,
				e -> logSink.writeRow(e.date(), e.level(), severity(e.level()), e.message()));
		SimLog.bind(colony); // route this thread's log records (founding + run) to this colony

		h.foundStandardColony();
		h.addCommonPrinters();

		colony.run(steps > 0 ? steps : cfg.numStep());
		// flush and close every printer's sink — commits the JDBC batch (CSV autoflushes per row, but
		// the SQL sink only commits on close). colony.run() does not do this; SessionRunner would.
		colony.cleanUpPrinters();
		closeQuietly(logTap);
		if (logSink != null)
			logSink.close();

		int laborers = 0, firms = 0;
		for (var a : colony.getAgents()) {
			if (!a.isAlive())
				continue;
			if (a instanceof Laborer)
				laborers++;
			else if (a instanceof Firm)
				firms++;
		}
		return new Result(colony.getDate(), colony.isDead(),
				colony.isDead() ? colony.getDeathDate() : null, laborers, firms);
	}
}
