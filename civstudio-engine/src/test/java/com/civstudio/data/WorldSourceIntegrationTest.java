package com.civstudio.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.tech.TechTree;

/**
 * End-to-end Phase-4 check: boot the real on-demand loaders through a {@link FixtureWorldSource} built
 * from a saved {@code /api/world-bundle} snapshot, and confirm they load the same counts they do off the
 * classpath — i.e. the studio bundle → engine chain is faithful.
 *
 * <p>Skipped unless {@code -Dworldbundle.fixture=<bundle.json[.gz]>} points at a snapshot (the full
 * bundle is a gitignored/CI-produced artifact, not committed), so it's inert in CI until that pipeline
 * exists. Only covers the on-demand loaders — {@code UnitCatalog} is an eager static singleton that
 * captures the source at class-load, so it can't be re-sourced mid-JVM in the shared test fork.
 */
class WorldSourceIntegrationTest {

	@Test
	void bootsOnDemandLoadersThroughFixtureBundle() {
		String fx = System.getProperty("worldbundle.fixture");
		assumeTrue(fx != null && Files.exists(Path.of(fx)),
				"set -Dworldbundle.fixture=<bundle.json[.gz]> to run this end-to-end check");
		try {
			// baseline off the classpath, then re-load through the bundle and assert identical counts —
			// proves the studio bundle → engine chain is faithful without hard-coding magic numbers.
			WorldSources.reset();
			int cpProvinces = WorldMap.load().size();
			int cpTerrains = TerrainRegistry.load().terrains().size();
			int cpTechs = TechTree.load().size();

			WorldSources.set(new FixtureWorldSource(Path.of(fx)));
			assertEquals(cpProvinces, WorldMap.load().size(), "provinces via bundle");
			assertEquals(cpTerrains, TerrainRegistry.load().terrains().size(), "terrains via bundle");
			assertEquals(cpTechs, TechTree.load().size(), "kept techs via bundle");
		} finally {
			WorldSources.reset();
		}
	}
}
