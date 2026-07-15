package com.civstudio.simulation;

import java.time.LocalDate;

import com.civstudio.agent.RetinueConfig;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.io.sink.RowSinkFactory;
import com.civstudio.settlement.Settlement;

/**
 * Runs one standard (ruler-bearing) colony headless to a horizon and reports its outcome — the
 * reusable core of the MCP {@code run_scenario} calibration tool (see {@code docs/mcp-server.md}
 * Phase 1). It is the {@link CalibrationSweep} founding pattern made parameterizable: found a standard
 * colony at a given {@code (seed, province)} with a {@link SimulationConfig} (and optional {@link
 * RetinueConfig} food levers), install a {@link RowSinkFactory} so the printers' typed time series
 * lands wherever the caller wants (the SQL run store, CSV, or both), run, and read back the collapse
 * outcome.
 *
 * <p>Deterministic in {@code seed} — the store it writes is a reporting mirror, so it never perturbs
 * an RNG stream. The default sink stays CSV; a caller opts into SQL by passing a JDBC-backed factory.
 */
public final class CalibrationRun {

	private CalibrationRun() {
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
		SimulationHarness h = SimulationHarness.create(cfg, seed, provinceId);
		if (retinue != null)
			h.setRetinueConfig(retinue);
		Settlement colony = h.getColony();
		// found first, then install the sink and printers, so the printers bind to this sink
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		if (sink != null)
			colony.setSinkFactory(sink);
		h.addCommonPrinters();

		colony.run(steps > 0 ? steps : cfg.numStep());
		// flush and close every printer's sink — commits the JDBC batch (CSV autoflushes per row, but
		// the SQL sink only commits on close). colony.run() does not do this; SessionRunner would.
		colony.cleanUpPrinters();

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
