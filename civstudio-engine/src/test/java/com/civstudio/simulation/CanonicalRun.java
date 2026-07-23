package com.civstudio.simulation;

/**
 * The <b>shared canonical run</b> (the post-default-flip suite speedup): ONE full
 * 25-year {@link HomogeneousEconomy} run at seed {@value #SEED}, computed on first
 * demand and shared by every test class that asserts invariants on the finished
 * default colony — determinism makes the sharing sound (the same seed always yields
 * the identical harness), and it converts N four-minute full runs into one.
 * <p>
 * Callers must treat the harness as <b>read-only history</b>: assert on it, never
 * advance or mutate it. A test that needs to drive a colony forward builds its own.
 */
public final class CanonicalRun {

	/** The canonical seed — the default scenario's, shared with the server demo. */
	public static final long SEED = 7654321;

	private static SimulationHarness harness;

	private CanonicalRun() {
	}

	/** The finished canonical run (computed once, then shared; synchronized for parallel classes). */
	public static synchronized SimulationHarness get() {
		if (harness == null)
			harness = HomogeneousEconomy.run();
		return harness;
	}
}
