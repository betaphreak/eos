package com.civstudio.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point that runs a simulation with database persistence enabled.
 * Boot's role is strictly <b>infrastructure</b>: it auto-configures a pooled
 * {@code DataSource} (HikariCP), runs the Flyway migrations on startup, and exposes
 * a {@code JdbcTemplate}. The simulation domain itself stays plain-constructed and
 * unaware of Spring — a {@link SimRunner} (a {@code CommandLineRunner}) installs a
 * database-backed sink factory via {@link com.civstudio.simulation.Persistence} and
 * then invokes the chosen scenario's {@code run()}.
 * <p>
 * Usage:
 * <pre>
 *   mvn spring-boot:run
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--sim=SmallOpenEconomy
 * </pre>
 * Database connection settings live in {@code application.yml} (host/port/db,
 * non-secret) and the git-ignored {@code application-local.yml} (credentials).
 */
@SpringBootApplication
public class SimApplication {

	public static void main(String[] args) {
		// run the context (the CommandLineRunner executes the simulation during
		// run()), then exit with its status so the JVM terminates cleanly
		System.exit(
				SpringApplication.exit(SpringApplication.run(SimApplication.class, args)));
	}
}
