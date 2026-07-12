package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Verifies the world map loads from the committed {@code /provinces.json}
 * resource, that its neighbor adjacency is a self-consistent undirected graph,
 * and that a known province (the founding default, Dhenijansar) carries the
 * geography the geography note pins. See {@code docs/geography.md}.
 */
class WorldMapTest {

	@Test
	void loadsTheCommittedSnapshot() {
		WorldMap map = WorldMap.load();
		// pins the committed export snapshot (full Anbennar world, keyed on the
		// distinct province_id after the two double-imported dups were merged)
		assertEquals(5264, map.size());
		assertTrue(map.settleableProvinces().size() < map.size(),
				"some provinces are water, not all settleable");
	}

	@Test
	void adjacencyIsSymmetricAndResolves() {
		WorldMap map = WorldMap.load();
		for (Province p : map.provinces())
			for (int n : p.neighbors()) {
				assertTrue(map.contains(n),
						"province " + p.id() + " neighbor " + n + " missing");
				assertTrue(map.neighbors(n).contains(p.id()),
						"edge " + p.id() + " -> " + n + " is not mirrored");
			}
	}

	@Test
	void dhenijansarHasItsPinnedGeography() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertEquals(4411, d.id()); // its province_id (the used id), not a DB surrogate
		assertEquals(23.16, d.latitude(), 1e-6);
		assertEquals(76.43, d.longitude(), 1e-6);
		assertEquals(74, d.plots());
		assertEquals(16, d.waterPlots());
		assertEquals(ProvinceType.LAND, d.type());
		assertTrue(d.isSettleable());
		assertTrue(d.isCoastal());
		assertEquals("rahen_coast_region", d.regionKey());
		assertEquals(Set.of(4385, 4405, 4410, 4412), new HashSet<>(d.neighbors()));
	}

	@Test
	void loadsThePoliticalLayer() {
		WorldMap map = WorldMap.load();
		// Wesdam (province 10) is owned by A04, West Damerian, Regent Court
		Province wesdam = map.province(10);
		assertEquals("A04", wesdam.ownerTag());
		assertEquals("A04", wesdam.controllerTag());
		assertEquals("west_damerian", wesdam.culture());
		assertEquals("regent_court", wesdam.religion());

		// the owner tag resolves to a country carrying a hex map colour
		Country a04 = map.country("A04").orElseThrow();
		assertEquals("Wesdam", a04.name());
		assertTrue(a04.color().matches("#[0-9a-f]{6}"), "country colour is #rrggbb");

		// culture and religion resolve to metadata records with colours
		Culture wd = map.culture("west_damerian").orElseThrow();
		assertEquals("anbennarian", wd.group());
		assertTrue(wd.color().matches("#[0-9a-f]{6}"));
		Religion rc = map.religion("regent_court").orElseThrow();
		assertEquals("cannorian", rc.group());
		assertTrue(rc.color().matches("#[0-9a-f]{6}"));

		// the derived membership indices are populated and Wesdam is in each
		assertTrue(map.provincesOwnedBy("A04").contains(wesdam));
		assertTrue(map.provincesOfCulture("west_damerian").contains(wesdam));
		assertTrue(map.provincesOfReligion("regent_court").contains(wesdam));
		assertFalse(map.owners().isEmpty(), "some countries own provinces");
		// not every province is owned (seas, uncolonized wasteland)
		assertTrue(map.provincesOwnedBy("A04").size() < map.size());
	}

	@Test
	void loadsTheTradeGoodLayer() {
		WorldMap map = WorldMap.load();
		// Wesdam (province 10) produces cloth at the start bookmark
		Province wesdam = map.province(10);
		assertEquals("cloth", wesdam.tradeGood());

		// the good key resolves to a record with a display name, hex colour and category
		TradeGood cloth = map.tradeGood("cloth").orElseThrow();
		assertEquals("Cloth", cloth.name());
		assertEquals(TradeGoodClass.MANUFACTURED, cloth.category());
		assertTrue(cloth.color().matches("#[0-9a-f]{6}"), "trade-good colour is #rrggbb");
		// the Anbennar magical goods land in the MAGICAL bucket
		assertEquals(TradeGoodClass.MAGICAL, map.tradeGood("mithril").orElseThrow().category());

		// the derived membership index is populated and Wesdam is in it
		assertTrue(map.provincesOfTradeGood("cloth").contains(wesdam));
		assertFalse(map.tradeGoodKeys().isEmpty(), "some provinces produce a trade good");
		// not every province has a good (uncolonized/undiscovered ones are null)
		assertTrue(map.provincesOfTradeGood("cloth").size() < map.size());
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesOfTradeGood("cloth").clear());

		// the 'unknown' placeholder is normalized to null, never a stamped good
		assertTrue(map.tradeGood("unknown").isEmpty(), "'unknown' is not a real good");
		assertTrue(map.provincesOfTradeGood("unknown").isEmpty());
		boolean sawNull = false;
		for (Province p : map.provinces())
			if (p.tradeGood() == null) {
				sawNull = true;
				break;
			}
		assertTrue(sawNull, "some provinces have no trade good");
	}

	@Test
	void loadsDevelopmentAndTheCityFlag() {
		WorldMap map = WorldMap.load();
		// Dhenijansar is a city_terrain province: it keeps its LAND type but carries the
		// city flag and its game-start development (ADM/DIP/MIL = 12/12/6 = 30). See
		// docs/urban-plots.md.
		Province dh = map.findByName("Dhenijansar").orElseThrow();
		assertEquals(ProvinceType.LAND, dh.type(), "a city keeps its land terrain");
		assertTrue(dh.city(), "Dhenijansar is flagged a city");
		assertEquals(12, dh.baseTax());
		assertEquals(12, dh.baseProduction());
		assertEquals(6, dh.baseManpower());
		assertEquals(30, dh.development(), "development sums ADM+DIP+MIL");

		// an ordinary province has development but is not a city
		Province wesdam = map.province(10);
		assertFalse(wesdam.city(), "Wesdam is not a city_terrain province");
		assertTrue(wesdam.development() > 0, "an owned province has base development");

		// the flag is sparse (only the 113 city_terrain provinces carry it)
		long cities = map.provinces().stream().filter(Province::city).count();
		assertEquals(113, cities, "the city_terrain provinces");
	}

	@Test
	void pathWalksTheNeighborGraph() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		int neighbor = d.neighbors().get(0);
		// a direct neighbor is a two-node path
		assertEquals(List.of(d.id(), neighbor), map.path(d.id(), neighbor));
		// to self is the singleton
		assertEquals(List.of(d.id()), map.path(d.id(), d.id()));
	}

	@Test
	void neighborsAreImmutableAndEndpointsValidated() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertThrows(UnsupportedOperationException.class,
				() -> d.neighbors().add(999));
		assertThrows(IllegalArgumentException.class, () -> map.province(-1));
		assertThrows(IllegalArgumentException.class, () -> map.path(d.id(), -1));
	}

	@Test
	void regionKeyIsAStableStringOrNull() {
		WorldMap map = WorldMap.load();
		boolean sawNull = false;
		for (Province p : map.provinces()) {
			String key = p.regionKey();
			if (key == null)
				sawNull = true; // open-ocean provinces have no region
			else
				assertFalse(key.isBlank(), "province " + p.id() + " has a blank region key");
		}
		assertTrue(sawNull, "some provinces have no region");
	}

	@Test
	void loadsTheAreaRegionAndContinentTiers() {
		WorldMap map = WorldMap.load();
		// pins the committed snapshots (non-empty Anbennar blocks; the 7
		// geographic continents, the utility pseudo-continents skipped)
		assertEquals(1570, map.areas().size());
		assertEquals(178, map.regions().size());
		assertEquals(31, map.superRegions().size());
		// the 7 geographic continents (a fixed enum; utility blocks excluded)
		assertEquals(7, Continent.values().length);
		assertEquals(7, map.continents().size());
		assertThrows(IllegalArgumentException.class, () -> map.area("nope"));
		assertThrows(IllegalArgumentException.class, () -> map.region("nope"));
		assertThrows(IllegalArgumentException.class, () -> map.superRegion("nope"));
		// the utility pseudo-continents are not enum values
		assertThrows(IllegalArgumentException.class,
				() -> Continent.fromKey("debug_continent"));
	}

	@Test
	void dhenijansarRollsUpToItsSuperRegion() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		SuperRegion sr = map.superRegionOf(d.id()).orElseThrow();
		assertEquals("rahen_superregion", sr.rawKey());
		assertEquals("Rahen", sr.name());
		// restrict_charter must not have leaked in as a "region"
		assertFalse(sr.regionKeys().contains("restrict_charter"));
		// the super-region nests its region and (transitively) the province
		Region region = map.regionOf(d.id()).orElseThrow();
		assertTrue(map.regionsInSuperRegion("rahen_superregion").contains(region));
		assertTrue(map.provincesInSuperRegion("rahen_superregion").contains(d));
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInSuperRegion("rahen_superregion").clear());
	}

	@Test
	void dhenijansarSitsOnItsContinent() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertEquals(Continent.ASIA, d.continent());
		Continent c = map.continentOf(d.id()).orElseThrow();
		assertEquals(Continent.ASIA, c);
		assertEquals("asia", c.rawKey());
		assertEquals("Haless", c.displayName());   // Anbennar landmass for the EU4 "asia" partition
		assertTrue(map.provincesInContinent(Continent.ASIA).contains(d));
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInContinent(Continent.ASIA).clear());
	}

	@Test
	void climateWinterMonsoonOverlays() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		// Dhenijansar: temperate (unclassified), no winter, normal monsoon
		assertEquals(Climate.TEMPERATE, d.climate());
		assertEquals(WinterSeverity.NONE, d.winter());
		assertEquals(Monsoon.NORMAL, d.monsoon());
		assertEquals(Climate.TEMPERATE, map.climateOf(d.id()));
		assertEquals(Monsoon.NORMAL, map.monsoonOf(d.id()));
		// the partitions are populated and the attributes are never null
		assertFalse(map.provincesInClimate(Climate.TROPICAL).isEmpty());
		assertFalse(map.provincesInMonsoon(Monsoon.NORMAL).isEmpty());
		for (Province p : map.provinces()) {
			assertNotNull(p.climate());
			assertNotNull(p.winter());
			assertNotNull(p.monsoon());
		}
	}

	@Test
	void impassableProvinceBlocksSettlingAndCaravans() {
		WorldMap map = WorldMap.load();
		// 1779 is wasteland in climate.txt -> ProvinceType.IMPASSABLE
		Province waste = map.province(1779);
		assertEquals(ProvinceType.IMPASSABLE, waste.type());
		assertFalse(waste.isSettleable());
		assertFalse(waste.isPassable());
		assertFalse(map.settleableProvinces().contains(waste));
		// caravans can neither route into nor out of impassable wasteland
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertTrue(map.path(d.id(), 1779).isEmpty());
		assertTrue(map.path(1779, d.id()).isEmpty());
	}

	@Test
	void dhenijansarSitsInItsAreaAndRegion() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		// province -> area -> region hierarchy resolves both ways
		assertEquals("inner_rahen_area", d.areaKey());
		Area area = map.areaOf(d.id()).orElseThrow();
		assertEquals("inner_rahen_area", area.rawKey());
		assertEquals("Inner Rahen", area.name());
		assertTrue(map.provincesInArea("inner_rahen_area").contains(d));

		Region region = map.regionOf(d.id()).orElseThrow();
		assertEquals("rahen_coast_region", region.rawKey());
		assertEquals("Rahen Coast", region.name());
		assertTrue(map.areasInRegion("rahen_coast_region").contains(area));
		// region membership is the union of its areas' provinces
		assertTrue(map.provincesInRegion("rahen_coast_region").contains(d));
	}

	@Test
	void provinceRegionKeyMatchesTheAreaTier() {
		WorldMap map = WorldMap.load();
		// the exporter re-derives each province's region from its area, so the
		// committed region_key must equal what the area tier resolves (no stale
		// DB-derived values remain — e.g. province 5726 was reconciled)
		for (Province p : map.provinces()) {
			String viaArea = map.regionOf(p.id()).map(Region::rawKey).orElse(null);
			assertEquals(viaArea, p.regionKey(),
					"province " + p.id() + " region_key disagrees with its area tier");
		}
	}

	@Test
	void hierarchyIsInternallyConsistent() {
		WorldMap map = WorldMap.load();
		// a province resolved up the tier must appear back in that tier's
		// membership (areas are the source of truth — the province's own
		// region_key is the legacy DB value and a handful are stale, so we check
		// the area tier's internal consistency, not agreement with region_key)
		boolean sawArea = false, sawRegion = false;
		for (Province p : map.provinces()) {
			Optional<Area> a = map.areaOf(p.id());
			if (a.isPresent()) {
				assertTrue(map.provincesInArea(a.get().rawKey()).contains(p),
						"province " + p.id() + " missing from its area");
				sawArea = true;
			}
			Optional<Region> r = map.regionOf(p.id());
			if (r.isPresent()) {
				assertTrue(map.provincesInRegion(r.get().rawKey()).contains(p),
						"province " + p.id() + " missing from its region");
				sawRegion = true;
			}
		}
		assertTrue(sawArea, "some province resolves an area");
		assertTrue(sawRegion, "some province resolves a region via its area");
	}

	@Test
	void membershipListsAreImmutable() {
		WorldMap map = WorldMap.load();
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInRegion("rahen_coast_region").clear());
		assertThrows(UnsupportedOperationException.class,
				() -> map.areasInRegion("rahen_coast_region").clear());
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInArea("inner_rahen_area").clear());
	}

	@Test
	void seaProvinceIsNotSettleable() {
		WorldMap map = WorldMap.load();
		boolean sawWater = false;
		for (Province p : map.provinces())
			if (!p.type().isLand()) {   // water + impassable — everything that isn't dry land
				assertFalse(p.isSettleable());
				sawWater = true;
			}
		assertTrue(sawWater);
	}

	@Test
	void specialAdjacenciesAreMergedIntoTheGraph() {
		WorldMap map = WorldMap.load();
		assertFalse(map.adjacencies().isEmpty(), "the EU4 adjacencies loaded");
		// the Dwarovar tunnel Dwarovrod 24 (2884) <-> 25 (2885) is a real, bidirectional graph edge
		// though the two are not visually adjacent (from map/adjacencies.csv, not the raster pixels),
		// so routing/caravans can traverse it; its cost is a positive great-circle distance
		assertTrue(map.neighbors(2884).contains(2885), "the Dwarovar tunnel is a neighbour edge");
		assertTrue(map.neighbors(2885).contains(2884), "the tunnel edge is bidirectional");
		assertFalse(map.province(2884).neighbors().contains(2885),
				"the tunnel is NOT a raster pixel neighbour (it is an adjacency)");
		assertTrue(map.edgeKm(2884, 2885) > 0, "the tunnel edge has a distance cost");
	}
}
