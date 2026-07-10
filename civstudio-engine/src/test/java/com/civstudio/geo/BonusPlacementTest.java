package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.util.Rng;

/**
 * The C2C-ported per-province bonus placement (slice 8, {@code docs/c2c-generator-port.md}
 * §8): resources are laid by placement order at target densities with group spacing, so
 * a province grows a <b>diverse</b> resource set and clustered bonuses actually
 * <b>cluster</b> (adjacent same-type tiles) — behaviour the old flat 8% uniform pick
 * could not produce. Eligibility itself is covered by {@link ProvincePlotFieldTest}.
 */
class BonusPlacementTest {

	private static long key(int x, int y) {
		return ((long) x << 20) | (y & 0xFFFFF);
	}

	@Test
	void bonusesAreDiverseAndClusterOnALargeProvince() throws Exception {
		// Tsunapileed — a big arid province, so many resource classes are eligible and the
		// grouped desert bonuses (peyote, prickly pear, …) form clusters
		Province p = WorldMap.load().findByName("Tsunapileed").orElseThrow();
		ProvincePlotField field = ProvincePlotField.generate(
				p, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(3));

		Map<String, Set<Long>> byType = new HashMap<>();
		int placed = 0;
		for (ProvincePlot pl : field.plots())
			if (pl.bonus() != null) {
				placed++;
				byType.computeIfAbsent(pl.bonus().type(), k -> new HashSet<>()).add(key(pl.x(), pl.y()));
			}

		// diverse, and a plausible (not saturating) share of the plots carry a resource
		assertTrue(byType.size() >= 15, "expected a diverse resource set, got " + byType.size() + " kinds");
		double density = placed / (double) field.size();
		assertTrue(density > 0.02 && density < 0.5,
				"resource density should be plausible, got " + density);

		// at least one resource forms a cluster: two of the same type 8-adjacent
		boolean clustered = false;
		for (Set<Long> positions : byType.values()) {
			for (long k : positions) {
				int x = (int) (k >> 20), y = (int) (k & 0xFFFFF);
				for (int dx = -1; dx <= 1 && !clustered; dx++)
					for (int dy = -1; dy <= 1; dy++)
						if ((dx != 0 || dy != 0) && positions.contains(key(x + dx, y + dy))) {
							clustered = true;
							break;
						}
				if (clustered)
					break;
			}
			if (clustered)
				break;
		}
		assertTrue(clustered, "at least one grouped bonus should place adjacent same-type tiles");
	}
}
