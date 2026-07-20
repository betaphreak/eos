package com.civstudio.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the Phase-1 calibration tools end to end without Spring: {@link ScenarioMcpTools} founds a
 * standard colony via the engine, writes its typed time series to an in-memory H2 run store through
 * the {@link com.civstudio.io.sink.JdbcRowSinkFactory}, and the rows come back tagged with the run's
 * id. The argument-validation cases stay fast (they reject before founding anything).
 */
class ScenarioMcpToolsTest {

	private ScenarioMcpTools tools() {
		return new ScenarioMcpTools(new CalibrationStore("mem:caltest"));
	}

	@Test
	void rejectsUnknownScenario() {
		assertThrows(IllegalArgumentException.class,
				() -> tools().runScenario("bogus", 1L, 1, null, null, null));
	}

	@Test
	void rejectsUnknownConfigOverride() {
		// an unknown override key is rejected, not silently ignored (fails before founding)
		assertThrows(IllegalArgumentException.class,
				() -> tools().runScenario("standard", 1L, 1, null, null, Map.of("noSuchLever", 1.0)));
	}

	@Test
	void listsTheStandardSetup() {
		ScenarioMcpTools.ScenarioCatalog catalog = tools().listScenarios();
		assertEquals(1, catalog.scenarios().size());
		assertEquals("standard", catalog.scenarios().get(0).name());
		assertTrue(catalog.scenarios().get(0).isRulerColony());
		assertTrue(catalog.profiles().contains("default"),
				"the default balance profile is always offered");
	}

	@Test
	@Timeout(300)
	void runsAndWritesTypedRowsToTheStore() throws Exception {
		CalibrationStore store = new CalibrationStore("mem:caltest-run");
		ScenarioMcpTools tools = new ScenarioMcpTools(store);

		// a short run with an override applied; deterministic in the seed
		ScenarioMcpTools.RunResult r = tools.runScenario("standard", 424242L, 60, 4411,
				"default", Map.of("durationYears", 40.0, "reliefBudgetPerPeasant", 3.0));

		assertNotNull(r.runId());
		assertEquals(424242L, r.seed());
		assertNotNull(r.finalDate(), "the run reports the in-game date it ended on");
		assertTrue(r.storeUrl().startsWith("jdbc:h2:"));

		// the printers' rows landed in the store, every one tagged with this run's id
		try (Connection c = store.dataSource().getConnection()) {
			List<String> tables = new ArrayList<>();
			try (ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "%",
					new String[] { "TABLE" })) {
				while (rs.next())
					tables.add(rs.getString("TABLE_NAME"));
			}
			assertFalse(tables.isEmpty(), "run_scenario should have created printer tables");
			int rowsForRun = 0;
			for (String t : tables)
				rowsForRun += countForRun(c, t, r.runId());
			assertTrue(rowsForRun > 0, "the run's typed time series should be in the store");
		}
	}

	private static int countForRun(Connection c, String table, String runId) throws Exception {
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM \"" + table + "\" WHERE \"run_id\" = ?")) {
			ps.setString(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}
}
