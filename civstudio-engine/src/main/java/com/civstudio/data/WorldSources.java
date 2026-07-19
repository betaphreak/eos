package com.civstudio.data;

/**
 * Ambient holder for the active {@link WorldSource}. Defaults to {@link ClasspathWorldSource} so the
 * engine reads its committed resources exactly as before; the composition root (server / a scenario's
 * {@code main} / a test) may {@link #set} a different source (Strapi / fixture) once at boot. Loaders
 * call {@link #current()} instead of {@code getClass().getResourceAsStream(...)}.
 *
 * <p>Deliberately a process-wide default rather than threaded state: several loaders are static
 * singletons (e.g. {@code UnitCatalog}) with no injection point, and every consumer reads the same
 * committed world data. It is set once at the composition root, not mutated during a run.
 */
public final class WorldSources {

	private static volatile WorldSource current = new ClasspathWorldSource();

	private WorldSources() {
	}

	/** The active source (never null). */
	public static WorldSource current() {
		return current;
	}

	/** Install the active source at the composition root; a null argument restores the classpath default. */
	public static void set(WorldSource source) {
		current = (source != null) ? source : new ClasspathWorldSource();
	}

	/** Restore the classpath default (for test teardown). */
	public static void reset() {
		current = new ClasspathWorldSource();
	}
}
