package com.civstudio.io.sink;

/**
 * The SQL type of a {@link ColumnSpec} — the logical type a printer's column
 * carries, used by a {@link RowSink} to build a typed schema (the JDBC sink maps
 * these to Postgres column types and binds values accordingly). The CSV sink
 * ignores the type and renders by value, so this matters only for the database
 * backend.
 */
public enum ColumnType {
	/** An in-game date ({@link java.time.LocalDate}) → Postgres {@code date}. */
	DATE,
	/** A string / enum label → Postgres {@code text}. */
	TEXT,
	/** An integer count → Postgres {@code integer}. */
	INT,
	/** A floating-point measure → Postgres {@code double precision}. */
	REAL
}
