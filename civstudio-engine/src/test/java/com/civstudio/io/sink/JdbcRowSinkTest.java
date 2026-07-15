package com.civstudio.io.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the JDBC backend against a real embedded H2 (PostgreSQL-compatibility mode): the factory
 * creates a typed table with the run-identity prefix, {@link JdbcRowSink} binds each {@link
 * ColumnType} correctly, and {@link CompositeRowSinkFactory} fans a row to two backends. Verifies the
 * seam works end to end without a running server — the shape the calibration harness uses.
 */
class JdbcRowSinkTest {

	private enum Role { LABORER } // an enum value → TEXT column stores its name()

	private static DataSource h2(String name) {
		JdbcDataSource ds = new JdbcDataSource();
		// in-memory, PostgreSQL mode, kept alive for the connection's/JVM's life
		ds.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
		ds.setUser("sa");
		return ds;
	}

	private static final ColumnSpec[] COLS = {
			ColumnSpec.date("Date"), ColumnSpec.text("colony"),
			ColumnSpec.integer("population"), ColumnSpec.real("cpi")
	};

	@Test
	void writesTypedRowsWithRunIdentity() throws Exception {
		DataSource ds = h2("run1");
		RowSinkFactory factory = new JdbcRowSinkFactory(ds, "run-1", 7654321L, "TwinSettlement");

		// two colonies register the SAME table concurrently-shaped (one CREATE, appended rows)
		RowSink a = factory.create("prices", "A_prices.csv", COLS);
		RowSink b = factory.create("prices", "B_prices.csv", COLS);
		a.writeRow(LocalDate.of(1444, 11, 11), "Alpha", 100, 1.25);
		b.writeRow(LocalDate.of(1444, 11, 11), Role.LABORER, 42, 0.5);
		a.close();
		b.close();

		try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
			// run identity landed as real columns on every row (all identifiers are quoted/exact-case)
			try (ResultSet rs = s.executeQuery(
					"SELECT \"run_id\", \"seed\", \"scenario\" FROM \"prices\" LIMIT 1")) {
				assertTrue(rs.next());
				assertEquals("run-1", rs.getString("run_id"));
				assertEquals(7654321L, rs.getLong("seed"));
				assertEquals("TwinSettlement", rs.getString("scenario"));
			}
			// typed values round-trip: date as DATE, enum as its name(), int and real
			try (ResultSet rs = s.executeQuery(
					"SELECT \"colony\", \"population\", \"cpi\", \"Date\" FROM \"prices\" ORDER BY \"population\" DESC")) {
				List<String> colonies = new ArrayList<>();
				assertTrue(rs.next());
				colonies.add(rs.getString("colony"));
				assertEquals(100, rs.getInt("population"));
				assertEquals(1.25, rs.getDouble("cpi"), 1e-9);
				assertEquals(LocalDate.of(1444, 11, 11), rs.getObject("Date", LocalDate.class));
				assertTrue(rs.next());
				colonies.add(rs.getString("colony"));
				assertEquals("LABORER", colonies.get(1), "an enum value is stored via name()");
				assertEquals(2, rowCount(c, "prices"), "both colonies' rows are in the one table");
			}
		}
	}

	@Test
	void compositeFansToBothBackends() throws Exception {
		DataSource ds1 = h2("comp1");
		DataSource ds2 = h2("comp2");
		RowSinkFactory composite = new CompositeRowSinkFactory(
				new JdbcRowSinkFactory(ds1, "r", 1L, "S"),
				new JdbcRowSinkFactory(ds2, "r", 1L, "S"));

		RowSink sink = composite.create("prices", "prices.csv", COLS);
		sink.writeRow(LocalDate.of(1444, 1, 1), "Alpha", 7, 3.0);
		sink.close();

		try (Connection c1 = ds1.getConnection(); Connection c2 = ds2.getConnection()) {
			assertEquals(1, rowCount(c1, "prices"), "the row reached the first backend");
			assertEquals(1, rowCount(c2, "prices"), "and the second");
		}
	}

	private static int rowCount(Connection c, String table) throws Exception {
		try (Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
			rs.next();
			return rs.getInt(1);
		}
	}
}
