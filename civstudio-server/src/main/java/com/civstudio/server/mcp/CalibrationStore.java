package com.civstudio.server.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * The local SQL run store the MCP calibration tools write to and read from — a single embedded H2
 * file database (PostgreSQL-compatibility mode) that accumulates every {@code run_scenario}'s typed
 * time series, told apart by {@code run_id} (see {@code docs/mcp-server.md} §Data backend). One
 * shared db (not one per seed) is what {@code compare_runs} / {@code sweep} want — a single {@code
 * GROUP BY run_id} query across runs.
 *
 * <p>Gated on {@code civstudio.mcp.calibration.enabled} so it exists only for the local calibration
 * loop (the {@code dev} profile turns it on) and never on the deployed demo, where founding and
 * running whole scenarios inside the web JVM has no place. H2 is loaded by JDBC URL (runtime scope),
 * so nothing here imports an H2 class.
 */
@Component
@ConditionalOnProperty("civstudio.mcp.calibration.enabled")
public class CalibrationStore {

	private final DataSource dataSource;
	private final String url;

	public CalibrationStore(
			@Value("${civstudio.mcp.calibration.db:output/calibration}") String dbPath) {
		// PostgreSQL mode so the same SQL works against the server's Postgres later; kept open for the
		// JVM's life (DB_CLOSE_DELAY=-1) so many short-lived sink connections share the one db. A
		// "mem:"-prefixed path is an in-memory db (tests); otherwise a file db under the working dir.
		String opts = ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
		if (dbPath.startsWith("mem:")) {
			this.url = "jdbc:h2:" + dbPath + opts;
		} else {
			ensureParentDir(dbPath); // H2 does not create missing parent directories
			this.url = "jdbc:h2:file:./" + dbPath + opts;
		}
		DriverManagerDataSource ds = new DriverManagerDataSource(url, "sa", "");
		ds.setDriverClassName("org.h2.Driver");
		this.dataSource = ds;
	}

	private static void ensureParentDir(String dbPath) {
		Path parent = Path.of(dbPath).getParent();
		if (parent == null)
			return;
		try {
			Files.createDirectories(parent);
		} catch (IOException e) {
			throw new UncheckedIOException("cannot create calibration store dir " + parent, e);
		}
	}

	/** The data source a {@code JdbcRowSinkFactory} / the query tools use. */
	public DataSource dataSource() {
		return dataSource;
	}

	/** The JDBC URL (reported back to the caller so it knows where the run landed). */
	public String url() {
		return url;
	}

	/**
	 * Record a run's reproducibility identity in the {@code run_meta} table — one row per {@code
	 * runId} carrying the seed, scenario and, crucially, the <b>content version</b> the run was
	 * founded against.
	 *
	 * <p>Since balance and scenarios ride the world bundle, a run is reproducible only as {@code seed
	 * + contentVersion + command log}: the same seed against a different content version is a
	 * different run. The printer tables denormalize {@code seed}/{@code scenario} onto every row but
	 * not the content version — so without this row a stored run silently loses which content it used,
	 * and can never be shown to reproduce. A {@code null} version (the classpath source carries none)
	 * is recorded as {@code NULL}, meaning <i>unknown</i> — never "the current one".
	 *
	 * @param runId          the run's stable id (primary key)
	 * @param seed           the run seed
	 * @param scenario       the scenario key
	 * @param contentVersion the content version founded against, or {@code null} if the source has none
	 */
	public void recordRun(String runId, long seed, String scenario, String contentVersion) {
		try (Connection c = dataSource.getConnection()) {
			try (Statement s = c.createStatement()) {
				s.execute("CREATE TABLE IF NOT EXISTS \"run_meta\" ("
						+ "\"run_id\" VARCHAR(64) PRIMARY KEY, \"seed\" BIGINT, "
						+ "\"scenario\" VARCHAR(128), \"content_version\" VARCHAR(128))");
			}
			// runId carries a random suffix, so a plain insert never collides; MERGE keeps it
			// idempotent anyway (a re-run under the same id updates rather than throws)
			try (PreparedStatement ps = c.prepareStatement("MERGE INTO \"run_meta\" "
					+ "(\"run_id\", \"seed\", \"scenario\", \"content_version\") KEY(\"run_id\") "
					+ "VALUES (?, ?, ?, ?)")) {
				ps.setString(1, runId);
				ps.setLong(2, seed);
				ps.setString(3, scenario);
				ps.setString(4, contentVersion); // null → SQL NULL (unknown)
				ps.executeUpdate();
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to record run_meta for " + runId, e);
		}
	}

	/** The content version a recorded run was founded against, or {@code null} if unknown/unrecorded. */
	public String contentVersionOf(String runId) {
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(
						"SELECT \"content_version\" FROM \"run_meta\" WHERE \"run_id\" = ?")) {
			ps.setString(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		} catch (SQLException e) {
			// run_meta may not exist yet (a store with no recorded runs) — unknown, not an error
			return null;
		}
	}
}
