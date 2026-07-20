package com.civstudio.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.civstudio.data.WorldSource;
import com.civstudio.market.WeddingConfig;

/**
 * {@link BalanceProfiles} — balance profiles as authored content ({@code
 * docs/studio-control-plane-plan.md} §A2/A3): the compiled default round-trips through Jackson, the
 * content overrides it per key, an absent resource is behaviour-neutral, and a malformed one is
 * refused. Mirrors {@code EconomyCatalogTest}.
 */
class BalanceProfilesTest {

	private static WorldSource sourceServing(String json) {
		return new WorldSource() {
			@Override
			public InputStream open(String path) {
				return BalanceProfiles.RESOURCE.equals(path)
						? new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
						: null;
			}

			@Override
			public boolean exists(String path) {
				return BalanceProfiles.RESOURCE.equals(path);
			}
		};
	}

	private static final WorldSource EMPTY_SOURCE = new WorldSource() {
		@Override
		public InputStream open(String path) {
			return null;
		}

		@Override
		public boolean exists(String path) {
			return false;
		}
	};

	@Test
	void theDefaultProfileRoundTripsThroughJackson() {
		// A2's ship criterion: read(write(DEFAULT)).equals(DEFAULT). If any config record does not
		// serialize cleanly (a non-canonical accessor, an unmapped enum), this is where it shows.
		String json = BalanceProfiles.canonicalJson();
		BalanceProfiles cat = BalanceProfiles.load(sourceServing(json));
		assertEquals(BalanceProfile.DEFAULT, cat.get(BalanceProfiles.DEFAULT_KEY),
				"the compiled default must survive a JSON round-trip, or seeding the content store"
						+ " would silently retune every agent");
	}

	@Test
	void contentOverridesTheDefaultAndAddsNamedProfiles() {
		// author a "default" that disables weddings, plus a second named profile
		BalanceProfile noWeddings = BalanceProfile.DEFAULT.toBuilder()
				.wedding(WeddingConfig.DEFAULT.toBuilder().capacity(0).build()).build();
		String json = "{"
				+ "\"default\":" + BalanceProfiles.MAPPER.writeValueAsString(noWeddings) + ","
				+ "\"lavish\":" + BalanceProfiles.MAPPER.writeValueAsString(BalanceProfile.DEFAULT)
				+ "}";

		BalanceProfiles cat = BalanceProfiles.load(sourceServing(json));
		assertEquals(0, cat.get("default").wedding().capacity(),
				"authored content must override the compiled default");
		assertTrue(cat.keys().contains("lavish"), "a named profile must load");
		assertEquals(BalanceProfile.DEFAULT, cat.get("lavish"));
	}

	@Test
	void anUnknownKeyFoundsOnTheDefaultsRatherThanFailing() {
		BalanceProfiles cat = BalanceProfiles.load(EMPTY_SOURCE);
		assertSame(BalanceProfile.DEFAULT, cat.get("no-such-profile"));
		assertSame(BalanceProfile.DEFAULT, cat.get(null));
	}

	@Test
	void anAbsentResourceLeavesOnlyTheCompiledDefault() {
		BalanceProfiles cat = BalanceProfiles.load(EMPTY_SOURCE);
		assertEquals(java.util.Set.of(BalanceProfiles.DEFAULT_KEY), cat.keys());
		assertEquals(BalanceProfile.DEFAULT, cat.get(BalanceProfiles.DEFAULT_KEY));
	}

	@Test
	void aMalformedResourceIsRefusedRatherThanSilentlyIgnored() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> BalanceProfiles.load(sourceServing("{ not json")));
		assertTrue(e.getMessage().contains(BalanceProfiles.RESOURCE),
				"the message should name the resource: " + e.getMessage());
	}
}
