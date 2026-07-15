package com.civstudio.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end coverage of the Phase-1 read tools over a real (in-memory) run store: a {@code sweep}
 * populates the store with two runs, then {@code list_outputs} / {@code query_timeseries} /
 * {@code compare_runs} read them back. Also checks the identifier validation rejects a bad column.
 * No Spring — the tools are plain objects over a {@link CalibrationStore}.
 */
class CalibrationQueryToolsTest {

	@Test
	@Timeout(600)
	void sweepPopulatesTheStoreAndTheReadToolsQueryIt() {
		CalibrationStore store = new CalibrationStore("mem:qtest");
		ScenarioMcpTools run = new ScenarioMcpTools(store);
		CalibrationQueryTools query = new CalibrationQueryTools(store);

		// sweep retinueSize over two values → two runs in the store
		List<ScenarioMcpTools.SweepPoint> points = run.sweep("standard", "retinueSize",
				List.of(150.0, 300.0), 4242L, 45, Map.of("durationYears", 40.0));
		assertEquals(2, points.size());
		String runA = points.get(0).runId();
		String runB = points.get(1).runId();
		assertNotNull(runA);
		assertTrue(points.get(0).laborers() > 0, "the first sweep run founded laborers");

		// list_outputs: the printer tables, with a Date span; find one with a numeric series column
		List<CalibrationQueryTools.TableInfo> outputs = query.listOutputs(runA);
		assertFalse(outputs.isEmpty(), "the sweep should have created printer tables");
		CalibrationQueryTools.TableInfo table = outputs.stream()
				.filter(t -> t.rowCount() > 0 && hasDate(t) && numericCol(t) != null)
				.findFirst().orElseThrow(() -> new AssertionError("no queryable dated table found"));
		assertNotNull(table.minDate(), "a dated table reports its Date span");
		String metric = numericCol(table);

		// query_timeseries: the Date + metric series for run A, ordered by Date
		List<Map<String, Object>> series = query.queryTimeseries(table.table(),
				List.of("Date", metric), runA, null, null, null);
		assertFalse(series.isEmpty(), "the run's series should have rows");
		assertTrue(series.get(0).containsKey("Date") && series.get(0).containsKey(metric));
		// Date is a plain ISO calendar date — not a timezone-shifted java.sql.Date timestamp
		assertTrue(series.get(0).get("Date").toString().matches("\\d{4}-\\d{2}-\\d{2}"),
				"Date should render as YYYY-MM-DD, got " + series.get(0).get("Date"));

		// an unknown column is rejected (validated against the live schema), not injected
		assertThrows(IllegalArgumentException.class, () -> query.queryTimeseries(table.table(),
				List.of("no_such_column"), runA, null, null, null));

		// compare_runs: the metric's finals/ranges for both runs
		CalibrationQueryTools.CompareResult cmp =
				query.compareRuns(runA, runB, table.table(), List.of(metric));
		assertEquals(1, cmp.columns().size());
		assertEquals(metric, cmp.columns().get(0).column());
		assertTrue(cmp.rowsA() > 0 && cmp.rowsB() > 0, "both runs contributed rows to the compare");

		// get_event_log: the run's persisted SimLog, filterable
		List<Map<String, Object>> log = query.getEventLog(runA, null, null, null, null, null, null);
		assertFalse(log.isEmpty(), "the run's event log should be persisted to the store");
		assertTrue(log.get(0).containsKey("message") && log.get(0).containsKey("severity"));
		assertTrue(query.getEventLog(runA, null, null, null, null, "founded", null).size() >= 1,
				"the founding line should be captured");
		assertTrue(query.getEventLog(runA, null, null, null, null, "zzz-no-such-line", null).isEmpty(),
				"a nonsense grep filters everything out");
	}

	private static boolean hasDate(CalibrationQueryTools.TableInfo t) {
		return t.columns().stream().anyMatch(c -> c.name().equals("Date"));
	}

	// a column that isn't the identity/date and whose SQL type is numeric
	private static String numericCol(CalibrationQueryTools.TableInfo t) {
		for (CalibrationQueryTools.ColumnInfo c : t.columns()) {
			String n = c.name();
			if (n.equals("Date") || n.equals("run_id") || n.equals("seed") || n.equals("scenario"))
				continue;
			String type = c.type().toUpperCase(Locale.ROOT);
			if (type.contains("INT") || type.contains("DOUBLE") || type.contains("REAL")
					|| type.contains("NUMERIC") || type.contains("DECIMAL"))
				return n;
		}
		return null;
	}
}
