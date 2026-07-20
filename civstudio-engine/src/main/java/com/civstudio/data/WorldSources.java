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

	/**
	 * The content version of the active source, or {@code null} when it has none (the classpath
	 * default, whose content is whatever was committed).
	 * <p>
	 * <b>Reproducibility is {@code seed + contentVersion + command log}</b>: the seed fixes every
	 * random draw, but the *world* the run happened in — and, once balance data rides the bundle, the
	 * numbers it was tuned with — comes from the content store, which changes independently of the
	 * code. Recording it against a run is what makes "re-run this" a checkable claim rather than a
	 * hope; a run recorded without one can never be shown to reproduce.
	 * <p>
	 * The {@code instanceof} lives here so callers do not each repeat it.
	 *
	 * @return the active content version, or {@code null} if the source does not carry one
	 */
	public static String contentVersion() {
		return current instanceof BundleWorldSource bundle ? bundle.contentVersion() : null;
	}
}
