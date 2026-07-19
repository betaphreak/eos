package com.civstudio.data;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Installs the committed world-bundle snapshot as the process-wide {@link WorldSource} for the whole
 * engine test suite, once, before any test class loads. Registered via
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 *
 * <p>Rationale: after the {@code generated/} resources were removed from the repo (studio is now the
 * authoritative content store), the default {@link ClasspathWorldSource} can no longer find
 * {@code /map/provinces.json}, {@code /units.json}, etc. Every test that boots world data would fail.
 * This listener points the engine at the committed {@code /world-bundle.json.gz} snapshot instead —
 * geonames and name tables, which are not in the bundle, still fall back to the classpath (they remain
 * committed). {@code launcherSessionOpened} runs before test discovery/execution, so it also fixes the
 * capture point of eager static loaders such as {@code UnitCatalog}.
 */
public final class FixtureWorldSourceInstaller implements LauncherSessionListener {

	/** The committed test-classpath snapshot; produce/refresh it with {@code tools/make-world-bundle.mjs}. */
	static final String SNAPSHOT = "/world-bundle.json.gz";

	@Override
	public void launcherSessionOpened(LauncherSession session) {
		WorldSources.set(FixtureWorldSource.fromClasspath(SNAPSHOT));
	}
}
