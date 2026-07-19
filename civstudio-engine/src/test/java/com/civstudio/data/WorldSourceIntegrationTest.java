package com.civstudio.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.tech.TechTree;

/**
 * End-to-end check that the real on-demand loaders boot through a bundle-backed {@link WorldSource}:
 * the committed {@code /world-bundle.json.gz} snapshot (installed suite-wide by
 * {@link FixtureWorldSourceInstaller}) drives {@link WorldMap}, {@link TerrainRegistry} and
 * {@link TechTree} to non-empty data — i.e. the studio → world-bundle → engine chain is intact and
 * {@link BundleWorldSource} re-serializes each dataset in a shape the parsers still read.
 *
 * <p>Since {@code generated/} was removed from the repo there is no classpath baseline to compare
 * against; per-dataset faithfulness is asserted at seed time ({@code studio/scripts/verify-bundle.js})
 * and against prod. This test only guards that the fixture actually loads. {@code UnitCatalog} is an
 * eager static singleton that captures the source at class-load, so it is not re-sourced here.
 */
class WorldSourceIntegrationTest {

	@Test
	void bootsOnDemandLoadersThroughFixtureBundle() {
		// The suite installs FixtureWorldSource globally; assert the loaders come up non-empty through it.
		assertTrue(WorldSources.current() instanceof BundleWorldSource,
				"suite should boot from the committed world-bundle fixture");
		assertTrue(WorldMap.load().size() > 0, "provinces via bundle");
		assertTrue(WorldMap.load().countries().size() > 0, "countries via bundle");
		assertTrue(TerrainRegistry.load().terrains().size() > 0, "terrains via bundle");
		assertTrue(TechTree.load().size() > 0, "kept techs via bundle");
	}
}
