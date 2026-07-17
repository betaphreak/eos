package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.GameSession;

/**
 * The {@link Realm} taxonomy and the realm stamped onto the committed {@code provinces.json} —
 * loaded end-to-end through the {@link WorldMap} (so this also exercises Jackson's {@code null →
 * Realm.NONE} default). See {@code docs/realms.md}.
 */
class RealmTest {

	private final WorldMap world = new GameSession(1).getWorldMap();

	@Test
	void continentMapsToRealm() {
		assertSame(Realm.AELANTIR, Realm.fromContinent(Continent.NORTH_AMERICA));
		assertSame(Realm.AELANTIR, Realm.fromContinent(Continent.SOUTH_AMERICA));
		assertSame(Realm.HINUILANDS, Realm.fromContinent(Continent.OCEANIA));
		assertSame(Realm.HALCANN, Realm.fromContinent(Continent.EUROPE));
		assertSame(Realm.HALCANN, Realm.fromContinent(Continent.SERPENTSPINE));
		assertSame(Realm.NONE, Realm.fromContinent(null));
	}

	@Test
	void rawKeyRoundTrips() {
		assertSame(Realm.NONE, Realm.fromKey(null)); // an absent realm key
		assertSame(Realm.HALCANN, Realm.fromKey("halcann"));
		assertSame(Realm.HINUILANDS, Realm.fromKey("hinuilands"));
		assertThrows(IllegalArgumentException.class, () -> Realm.fromKey("atlantis"));
		assertEquals(null, Realm.NONE.rawKey());
		assertTrue(Realm.HALCANN.isPlayable() && Realm.AELANTIR.isPlayable());
		assertFalse(Realm.HINUILANDS.isPlayable() || Realm.NONE.isPlayable());
	}

	@Test
	void everyProvinceHasARealmAndTheyPartitionTheMap() {
		int total = world.provinces().size();
		int summed = 0;
		for (Realm r : Realm.values())
			summed += world.provincesOfRealm(r).size();
		assertEquals(total, summed, "realm buckets must partition every province exactly once");
		for (Province p : world.provinces())
			assertNotNull(p.realm(), "realm is never null (defaults to NONE)");
	}

	@Test
	void knownProvincesResolveAsDesigned() {
		// Hinuilands is painted for exactly two provinces (docs/realms.md §Hinuilands is not painted)
		assertSame(Realm.HINUILANDS, world.province(3060).realm()); // Vyr Pas
		assertSame(Realm.HINUILANDS, world.province(3061).realm()); // Vyr Cirentyn
		assertEquals(2, world.provincesOfRealm(Realm.HINUILANDS).size());
		// the three quirks are dropped from their realm (§Three quirk provinces)
		assertSame(Realm.NONE, world.province(6237).realm()); // South Toreiel (LAND, but realm-less)
		assertSame(Realm.NONE, world.province(6238).realm()); // North Toreiel
		assertSame(Realm.NONE, world.province(1808).realm()); // Ekyunimoy (Antarctic ice)
		// the Phase 0 portal waypoints land in Halcann via their europe continent (§The model)
		for (int id : new int[] { 7025, 7027, 7030, 7033 })
			assertSame(Realm.HALCANN, world.province(id).realm(), "portal waypoint " + id);
	}

	@Test
	void settleableSetExcludesRealmlessLand() {
		List<Province> settleable = world.settleableProvinces();
		for (Province p : settleable)
			assertNotSame(Realm.NONE, p.realm(), p.name() + " is realm-less but settleable");
		// the Toreiels are settleable-typed LAND yet realm-less, so they must be excluded
		assertTrue(world.province(6237).isSettleable(), "precondition: South Toreiel is LAND");
		assertFalse(settleable.contains(world.province(6237)), "realm-less Toreiel must not be a site");
	}
}
