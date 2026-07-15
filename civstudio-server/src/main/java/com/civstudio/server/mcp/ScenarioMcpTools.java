package com.civstudio.server.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.civstudio.agent.RetinueConfig;
import com.civstudio.io.sink.CompositeRowSinkFactory;
import com.civstudio.io.sink.CsvRowSinkFactory;
import com.civstudio.io.sink.JdbcRowSinkFactory;
import com.civstudio.io.sink.RowSinkFactory;
import com.civstudio.simulation.CalibrationRun;
import com.civstudio.simulation.SimulationConfig;

/**
 * The Phase-1 calibration MCP tools (see {@code docs/mcp-server.md} §Phase 1): run a scenario headless
 * and land its typed time series in the SQL run store the query tools read. Gated on {@code
 * civstudio.mcp.calibration.enabled} (the {@code dev} profile) so it rides the same {@code /mcp}
 * endpoint as the live-session read tools <b>only locally</b> — running whole scenarios inside the JVM
 * has no place on the deployed demo.
 *
 * <p>Following the chosen shape, {@code run_scenario} founds a <b>standard ruler colony</b> at a given
 * seed with {@link SimulationConfig}/{@link RetinueConfig} overrides (via {@link CalibrationRun}),
 * rather than reflectively invoking a scenario's {@code main}. {@code configOverrides} is a curated
 * whitelist — unknown keys are rejected, not silently ignored.
 */
@Component
@ConditionalOnProperty("civstudio.mcp.calibration.enabled")
public class ScenarioMcpTools {

	// the size-4 default province the ruler-colony scenarios found into (Dhenijansar)
	private static final int DEFAULT_PROVINCE = 4411;

	private final CalibrationStore store;

	public ScenarioMcpTools(CalibrationStore store) {
		this.store = store;
	}

	@McpTool(name = "list_scenarios",
			description = "The named base setups run_scenario can found, so the LLM picks a valid one.")
	public List<ScenarioInfo> listScenarios() {
		return List.of(new ScenarioInfo("standard",
				"A standard ruler-bearing colony (peasant pool, three-tier banks, export sector, "
						+ "ennobled aristocracy). Collapses by design as the pool reserve drains — the "
						+ "food-balance calibration target.",
				true));
	}

	@McpTool(name = "run_scenario",
			description = "Found a standard ruler colony at a seed and run it headless, writing its "
					+ "typed time series to the SQL run store (keyed by the returned runId) plus CSVs. "
					+ "Deterministic in seed. configOverrides tunes whitelisted SimulationConfig / "
					+ "peasant-pool fields; unknown keys are rejected.")
	public RunResult runScenario(
			@McpToolParam(description = "Scenario setup name from list_scenarios (currently 'standard')",
					required = true) String scenario,
			@McpToolParam(description = "Run seed — same seed reproduces the run exactly", required = true)
			long seed,
			@McpToolParam(description = "Days to run; omit to run the config's full duration",
					required = false) Integer steps,
			@McpToolParam(description = "World-map province id to found into (default 4411, Dhenijansar)",
					required = false) Integer provinceId,
			@McpToolParam(description = "Whitelisted overrides, e.g. {\"durationYears\":40,"
					+ "\"retinueSize\":450,\"reliefBudgetPerPeasant\":3.0}", required = false)
			Map<String, Double> configOverrides) {
		if (!"standard".equals(scenario))
			throw new IllegalArgumentException("unknown scenario '" + scenario
					+ "'; call list_scenarios (only 'standard' is available)");

		SimulationConfig cfg = applyConfig(SimulationConfig.DEFAULT, configOverrides);
		RetinueConfig retinue = applyRetinue(configOverrides);
		int province = provinceId == null ? DEFAULT_PROVINCE : provinceId;

		String runId = scenario + "-" + seed + "-" + UUID.randomUUID().toString().substring(0, 8);
		RowSinkFactory sink = new CompositeRowSinkFactory(
				new CsvRowSinkFactory("output/" + seed),
				new JdbcRowSinkFactory(store.dataSource(), runId, seed, scenario));

		CalibrationRun.Result r = CalibrationRun.run(cfg, seed, province, retinue, sink,
				steps == null ? 0 : steps);

		return new RunResult(runId, seed, scenario, r.finalDate().toString(), r.died(),
				r.deathDate() == null ? null : r.deathDate().toString(),
				r.laborers(), r.firms(), store.url());
	}

	@McpTool(name = "sweep",
			description = "Run a scenario across a range of one parameter and report each run's "
					+ "collapse outcome — the calibration workhorse (replaces the CalibrationSweep dev "
					+ "tool). Every run also lands in the store (see the returned runIds) for deeper "
					+ "query_timeseries / compare_runs. Deterministic in seed.")
	public List<SweepPoint> sweep(
			@McpToolParam(description = "Scenario setup name (currently 'standard')", required = true)
			String scenario,
			@McpToolParam(description = "Parameter to vary: 'seed', or a whitelisted override key "
					+ "(e.g. 'retinueSize', 'reliefBudgetPerPeasant')", required = true) String param,
			@McpToolParam(description = "Values to sweep the parameter over", required = true)
			List<Double> values,
			@McpToolParam(description = "Base seed — used for every run unless param is 'seed'",
					required = true) long seed,
			@McpToolParam(description = "Days to run each; omit for the full duration", required = false)
			Integer steps,
			@McpToolParam(description = "Overrides held fixed across the sweep, e.g. "
					+ "{\"externalInflowPerStep\":1000,\"numNFirms\":8}", required = false)
			Map<String, Double> baseOverrides) {
		List<SweepPoint> out = new ArrayList<>();
		for (Double value : values) {
			long runSeed = seed;
			Map<String, Double> ov = baseOverrides == null ? new HashMap<>() : new HashMap<>(baseOverrides);
			if ("seed".equals(param))
				runSeed = value.longValue();
			else
				ov.put(param, value);
			RunResult r = runScenario(scenario, runSeed, steps, null, ov);
			out.add(new SweepPoint(value, r.runId(), r.seed(), r.died(),
					r.deathDate() == null ? -1 : Integer.parseInt(r.deathDate().substring(0, 4)),
					r.laborers(), r.firms()));
		}
		return out;
	}

	// apply the whitelisted SimulationConfig overrides; the peasant-pool keys are handled by
	// applyRetinue and skipped here, and any other key is rejected (not silently dropped)
	private static SimulationConfig applyConfig(SimulationConfig base, Map<String, Double> ov) {
		if (ov == null || ov.isEmpty())
			return base;
		SimulationConfig.SimulationConfigBuilder b = base.toBuilder();
		for (Map.Entry<String, Double> e : ov.entrySet()) {
			double v = e.getValue();
			switch (e.getKey()) {
				case "durationYears" -> b.durationYears((int) v);
				case "numEFirms" -> b.numEFirms((int) v);
				case "numNFirms" -> b.numNFirms((int) v);
				case "foundingLaborersPerNFirm" -> b.foundingLaborersPerNFirm((int) v);
				case "retinueSize" -> b.retinueSize((int) v);
				case "promotionRatio" -> b.promotionRatio(v);
				case "targetNobles" -> b.targetNobles((int) v);
				case "externalInflowPerStep" -> b.externalInflowPerStep(v);
				case "immigrationThreshold" -> b.immigrationThreshold(v);
				case "bankProfitTaxRate" -> b.bankProfitTaxRate(v);
				case "nobleIncomeTaxRate" -> b.nobleIncomeTaxRate(v);
				// peasant-pool levers → applyRetinue; accepted here as no-ops
				case "reliefBudgetPerPeasant", "bufferDays" -> { }
				default -> throw new IllegalArgumentException(
						"unknown/unsupported config override: " + e.getKey());
			}
		}
		return b.build();
	}

	// the peasant-pool food levers live on RetinueConfig (set via SimulationHarness.setRetinueConfig)
	private static RetinueConfig applyRetinue(Map<String, Double> ov) {
		if (ov == null)
			return null;
		RetinueConfig.RetinueConfigBuilder b = RetinueConfig.DEFAULT.toBuilder();
		boolean any = false;
		if (ov.containsKey("reliefBudgetPerPeasant")) {
			b.reliefBudgetPerPeasant(ov.get("reliefBudgetPerPeasant"));
			any = true;
		}
		if (ov.containsKey("bufferDays")) {
			b.bufferDays(ov.get("bufferDays").intValue());
			any = true;
		}
		return any ? b.build() : null;
	}

	/** One row of {@link #listScenarios()}. */
	public record ScenarioInfo(String name, String blurb, boolean isRulerColony) {}

	/**
	 * One point of a {@link #sweep}: the swept value, its run's id (for follow-up queries), and the
	 * collapse outcome — the same "held or collapsed, and when" the retired {@code CalibrationSweep}
	 * printed, but structured.
	 *
	 * @param value     the swept parameter value
	 * @param runId     the run's id in the store
	 * @param seed      the seed this run used
	 * @param died      whether the colony collapsed before the horizon
	 * @param deathYear the collapse year, or -1 if it survived
	 * @param laborers  living laborer households at the end
	 * @param firms     living firms at the end
	 */
	public record SweepPoint(double value, String runId, long seed, boolean died, int deathYear,
			int laborers, int firms) {}

	/**
	 * The outcome of a {@link #runScenario} call.
	 *
	 * @param runId     the run's id — pass to the query tools to read its series
	 * @param seed      the run seed
	 * @param scenario  the setup name
	 * @param finalDate the in-game date the run ended on
	 * @param died      whether the colony collapsed before the horizon
	 * @param deathDate the collapse date, or null if it survived
	 * @param laborers  living laborer households at the end
	 * @param firms     living firms at the end
	 * @param storeUrl  the JDBC URL of the run store the rows landed in
	 */
	public record RunResult(String runId, long seed, String scenario, String finalDate, boolean died,
			String deathDate, int laborers, int firms, String storeUrl) {}
}
