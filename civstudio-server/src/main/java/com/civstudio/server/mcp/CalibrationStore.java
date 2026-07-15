package com.civstudio.server.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
