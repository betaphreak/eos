package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.civstudio.server.CivStudioProperties;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SimPauseGate;
import com.civstudio.settlement.ProvincePlotStore;

/**
 * {@link PlotService} without touching the (network-backed, expensive) generation path: the disk
 * cache is served verbatim, and an off-map id resolves to {@code null} (→ 404). Real generation is
 * exercised by a live server run (see {@code docs/plot-serving.md}).
 */
class PlotServiceTest {

	private static PlotService service(Path cacheDir) {
		CivStudioProperties props = new CivStudioProperties();
		props.getPlots().setCacheDir(cacheDir.toString());
		return new PlotService(new SimPauseGate(new SessionHost()), props);
	}

	@Test
	void servesDiskCacheHitVerbatimWithoutGenerating(@TempDir Path cacheDir) throws Exception {
		// a pre-seeded <id>.json.gz is returned as-is — no WorldMap/raster load, no generation
		byte[] blob = { 0x1f, (byte) 0x8b, 1, 2, 3, 4 }; // gzip magic + filler; opaque to the service
		// the service reads from the version-scoped subdir (ProvincePlotStore.GEN_VERSION)
		Path versioned = Files.createDirectories(cacheDir.resolve("v" + ProvincePlotStore.GEN_VERSION));
		Files.write(versioned.resolve("4411.json.gz"), blob);
		assertArrayEquals(blob, service(cacheDir).gz(4411));
	}

	@Test
	void offMapProvinceIsNull(@TempDir Path cacheDir) {
		// no cache file + an id not on the world map → generate() short-circuits to null (→ 404),
		// without loading the province raster
		assertNull(service(cacheDir).gz(Integer.MAX_VALUE));
	}
}
