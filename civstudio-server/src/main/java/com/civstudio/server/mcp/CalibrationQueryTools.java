package com.civstudio.server.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The Phase-1 calibration <b>read</b> tools (see {@code docs/mcp-server.md} §Phase 1): schema
 * discovery and typed time-series queries over the SQL run store {@code run_scenario} / {@code sweep}
 * write to. Gated with the run tools on {@code civstudio.mcp.calibration.enabled} (dev only).
 *
 * <p>Identifiers the LLM supplies (table, column names) are <b>validated against the live schema</b>
 * and quoted; all filter values are bound as parameters — so a hallucinated name is rejected, not
 * injected. Reads never mutate anything.
 */
@Component
@ConditionalOnProperty("civstudio.mcp.calibration.enabled")
public class CalibrationQueryTools {

	private final CalibrationStore store;

	public CalibrationQueryTools(CalibrationStore store) {
		this.store = store;
	}

	@McpTool(name = "list_outputs",
			description = "Discover the run store's schema: each printer table with its typed columns, "
					+ "row count and Date range. Pass a runId to scope the counts/range to one run. Use "
					+ "this so query_timeseries calls name real tables and columns.")
	public List<TableInfo> listOutputs(
			@McpToolParam(description = "Scope counts/date-range to this run (optional)", required = false)
			String runId) {
		try (Connection c = store.dataSource().getConnection()) {
			List<TableInfo> out = new ArrayList<>();
			for (String table : tables(c)) {
				List<ColumnInfo> cols = columns(c, table);
				boolean hasDate = cols.stream().anyMatch(ci -> ci.name().equals("Date"));
				String where = runId == null ? "" : " WHERE \"run_id\" = ?";
				String sql = "SELECT COUNT(*)" + (hasDate ? ", MIN(\"Date\"), MAX(\"Date\")" : "")
						+ " FROM " + q(table) + where;
				try (PreparedStatement ps = c.prepareStatement(sql)) {
					if (runId != null)
						ps.setString(1, runId);
					try (ResultSet rs = ps.executeQuery()) {
						rs.next();
						long rows = rs.getLong(1);
						String min = hasDate ? str(rs.getObject(2)) : null;
						String max = hasDate ? str(rs.getObject(3)) : null;
						out.add(new TableInfo(table, cols, rows, min, max));
					}
				}
			}
			return out;
		} catch (SQLException e) {
			throw new IllegalStateException("failed to list outputs", e);
		}
	}

	@McpTool(name = "query_timeseries",
			description = "Read a run's typed time series: the chosen columns of a printer table, "
					+ "filtered to a runId (or seed) and an optional inclusive Date range, ordered by "
					+ "Date. Column/table names are validated against the schema (see list_outputs).")
	public List<Map<String, Object>> queryTimeseries(
			@McpToolParam(description = "Printer table name (from list_outputs)", required = true)
			String table,
			@McpToolParam(description = "Columns to return, in order", required = true) List<String> columns,
			@McpToolParam(description = "Run to read (preferred); or pass seed", required = false)
			String runId,
			@McpToolParam(description = "Seed to read, if no runId (may match several runs)",
					required = false) Long seed,
			@McpToolParam(description = "Inclusive start Date, ISO-8601", required = false) String from,
			@McpToolParam(description = "Inclusive end Date, ISO-8601", required = false) String to) {
		if (runId == null && seed == null)
			throw new IllegalArgumentException("pass a runId or a seed to select the run");
		try (Connection c = store.dataSource().getConnection()) {
			Set<String> real = columnNames(c, table);
			if (real.isEmpty())
				throw new IllegalArgumentException("no such table in the store: " + table);
			for (String col : columns)
				if (!real.contains(col))
					throw new IllegalArgumentException("no column '" + col + "' in " + table
							+ " (use list_outputs)");
			boolean hasDate = real.contains("Date");

			StringBuilder sql = new StringBuilder("SELECT ");
			for (int i = 0; i < columns.size(); i++)
				sql.append(i == 0 ? "" : ", ").append(q(columns.get(i)));
			sql.append(" FROM ").append(q(table)).append(" WHERE ");
			List<Object> params = new ArrayList<>();
			if (runId != null) {
				sql.append("\"run_id\" = ?");
				params.add(runId);
			} else {
				sql.append("\"seed\" = ?");
				params.add(seed);
			}
			if (hasDate && from != null) {
				sql.append(" AND \"Date\" >= ?");
				params.add(LocalDate.parse(from));
			}
			if (hasDate && to != null) {
				sql.append(" AND \"Date\" <= ?");
				params.add(LocalDate.parse(to));
			}
			if (hasDate)
				sql.append(" ORDER BY \"Date\"");

			try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
				for (int i = 0; i < params.size(); i++)
					ps.setObject(i + 1, params.get(i));
				try (ResultSet rs = ps.executeQuery()) {
					List<Map<String, Object>> rows = new ArrayList<>();
					while (rs.next()) {
						Map<String, Object> row = new LinkedHashMap<>();
						for (String col : columns)
							row.put(col, jsonValue(rs.getObject(col)));
						rows.add(row);
					}
					return rows;
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to query " + table, e);
		}
	}

	@McpTool(name = "compare_runs",
			description = "Compare two runs' series for the same table/columns — per numeric column the "
					+ "final, min and max of each run and the delta of finals (the 'did this knob help?' "
					+ "primitive). Non-numeric columns are skipped in the stats.")
	public CompareResult compareRuns(
			@McpToolParam(description = "First run id", required = true) String runIdA,
			@McpToolParam(description = "Second run id", required = true) String runIdB,
			@McpToolParam(description = "Printer table name", required = true) String table,
			@McpToolParam(description = "Numeric columns to compare", required = true) List<String> columns) {
		List<Map<String, Object>> a = queryTimeseries(table, columns, runIdA, null, null, null);
		List<Map<String, Object>> b = queryTimeseries(table, columns, runIdB, null, null, null);
		List<ColumnDelta> deltas = new ArrayList<>();
		for (String col : columns) {
			Double aF = finalOf(a, col), bF = finalOf(b, col);
			deltas.add(new ColumnDelta(col, aF, bF,
					(aF != null && bF != null) ? bF - aF : null,
					minOf(a, col), maxOf(a, col), minOf(b, col), maxOf(b, col)));
		}
		return new CompareResult(runIdA, runIdB, table, a.size(), b.size(), deltas);
	}

	// --- schema helpers (validated, quoted) ---

	private static Set<String> tables(Connection c) throws SQLException {
		Set<String> t = new LinkedHashSet<>();
		try (ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] { "TABLE" })) {
			while (rs.next())
				t.add(rs.getString("TABLE_NAME"));
		}
		return t;
	}

	private static List<ColumnInfo> columns(Connection c, String table) throws SQLException {
		List<ColumnInfo> cols = new ArrayList<>();
		try (ResultSet rs = c.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
			while (rs.next())
				cols.add(new ColumnInfo(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")));
		}
		return cols;
	}

	private static Set<String> columnNames(Connection c, String table) throws SQLException {
		Set<String> names = new LinkedHashSet<>();
		for (ColumnInfo ci : columns(c, table))
			names.add(ci.name());
		return names;
	}

	// quote an identifier already validated against the live schema
	private static String q(String id) {
		return '"' + id.replace("\"", "\"\"") + '"';
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	// dates render as plain ISO date strings; numbers/strings pass through for JSON. Read the date
	// fields directly (toLocalDate) — a java.sql.Date rendered via its millis is timezone-shifted
	// (it can even roll to the previous day), which is wrong for an in-game calendar date.
	private static Object jsonValue(Object o) {
		if (o instanceof LocalDate d)
			return d.toString();
		if (o instanceof java.sql.Date d)
			return d.toLocalDate().toString();
		if (o instanceof java.sql.Timestamp t)
			return t.toLocalDateTime().toLocalDate().toString();
		return o;
	}

	private static Double finalOf(List<Map<String, Object>> rows, String col) {
		for (int i = rows.size() - 1; i >= 0; i--) {
			Double v = num(rows.get(i).get(col));
			if (v != null)
				return v;
		}
		return null;
	}

	private static Double minOf(List<Map<String, Object>> rows, String col) {
		Double m = null;
		for (Map<String, Object> r : rows) {
			Double v = num(r.get(col));
			if (v != null && (m == null || v < m))
				m = v;
		}
		return m;
	}

	private static Double maxOf(List<Map<String, Object>> rows, String col) {
		Double m = null;
		for (Map<String, Object> r : rows) {
			Double v = num(r.get(col));
			if (v != null && (m == null || v > m))
				m = v;
		}
		return m;
	}

	private static Double num(Object o) {
		return (o instanceof Number n) ? n.doubleValue() : null;
	}

	/** One column of a {@link TableInfo}: its name and SQL type. */
	public record ColumnInfo(String name, String type) {}

	/** A printer table in the store: its columns, row count and Date span. */
	public record TableInfo(String table, List<ColumnInfo> columns, long rowCount,
			String minDate, String maxDate) {}

	/** Per-column comparison of two runs (finals, ranges, delta of finals). */
	public record ColumnDelta(String column, Double aFinal, Double bFinal, Double delta,
			Double aMin, Double aMax, Double bMin, Double bMax) {}

	/** The result of {@link #compareRuns}. */
	public record CompareResult(String runIdA, String runIdB, String table, int rowsA, int rowsB,
			List<ColumnDelta> columns) {}
}
