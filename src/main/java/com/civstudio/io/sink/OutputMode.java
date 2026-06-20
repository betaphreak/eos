package com.civstudio.io.sink;

/**
 * Where a run's printer output goes. {@link #CSV} is the default (files under
 * {@code output/}); {@link #DB} writes only to Postgres; {@link #BOTH} writes to
 * both. Selected by the Spring Boot launcher via configuration.
 */
public enum OutputMode {
	/** CSV files only — the default, used by plain {@code main()} runs and tests. */
	CSV,
	/** Database tables only. */
	DB,
	/** Both CSV files and database tables. */
	BOTH
}
