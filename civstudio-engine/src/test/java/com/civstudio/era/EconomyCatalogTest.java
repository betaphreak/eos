package com.civstudio.era;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.civstudio.data.WorldSource;
import com.civstudio.race.Race;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The era × race economy matrix as authored content ({@code docs/studio-control-plane-plan.md} §A1):
 * the compiled {@link Era} constants are the human column and the floor, content wins over them, and
 * a broken resource is refused rather than silently ignored.
 */
class EconomyCatalogTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	/** A source answering only {@code /balance/economies.json}, with the given body. */
	private static WorldSource sourceServing(String json) {
		return new WorldSource() {
			@Override
			public InputStream open(String path) {
				return EconomyCatalog.RESOURCE.equals(path)
						? new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
						: null;
			}

			@Override
			public boolean exists(String path) {
				return EconomyCatalog.RESOURCE.equals(path);
			}
		};
	}

	@Test
	void theCanonicalJsonRoundTripsBackToTheCompiledConstants() {
		String json = EconomyCatalog.canonicalJson();
		var read = MAPPER.readValue(json,
				new TypeReference<java.util.Map<String, java.util.Map<String, Era.Economy>>>() {
				});

		Era.Economy medieval = read.get("MEDIEVAL").get("HUMAN");
		assertEquals(Era.MEDIEVAL.economy(Race.HUMAN), medieval,
				"the emitted content must be what the engine currently runs on, or seeding the"
						+ " content store would silently retune every colony");
	}

	@Test
	void anUncalibratedEraIsNotEmitted() {
		assertTrue(EconomyCatalog.canonicalJson().contains("MEDIEVAL"));
		// INDUSTRIAL has no compiled Economy; emitting a null column would author "no tuning"
		assertTrue(!EconomyCatalog.canonicalJson().contains("INDUSTRIAL"),
				"an era with no economy must be absent from the content, not present-and-null");
	}

	@Test
	void aRaceWithNoColumnOfItsOwnFallsBackToTheHumanOneWithinTheMatrix() {
		// authored: a human column and a dwarven one. Elves have neither, so they read human.
		String json = """
				{"MEDIEVAL":{
				  "HUMAN":%s,
				  "DWARVEN":%s}}
				""".formatted(
				MAPPER.writeValueAsString(Era.MEDIEVAL.compiledEconomy()),
				MAPPER.writeValueAsString(Era.MEDIEVAL.compiledEconomy().toBuilder()
						.retinueSize(1200).build()));

		EconomyCatalog cat = loadWith(json);
		assertNotNull(cat.find(Era.MEDIEVAL, Race.DWARVEN));
		assertEquals(1200, cat.find(Era.MEDIEVAL, Race.DWARVEN).retinueSize(),
				"an authored race column must win");
		assertEquals(cat.find(Era.MEDIEVAL, Race.HUMAN), cat.find(Era.MEDIEVAL, Race.ELVEN),
				"a race with no column reads the human one inside the matrix");
		assertNull(cat.find(Era.RENAISSANCE, Race.HUMAN),
				"an unauthored era says nothing, leaving the compiled constant standing");
	}

	@Test
	void aMalformedResourceIsRefusedRatherThanSilentlyIgnored() {
		// the whole point of the strict contract: an economy that quietly reverts to the compiled
		// constants is a run that reports numbers it did not use
		IllegalStateException e =
				assertThrows(IllegalStateException.class, () -> loadWith("{ this is not json"));
		assertTrue(e.getMessage().contains(EconomyCatalog.RESOURCE),
				"the message should name the resource: " + e.getMessage());
	}

	@Test
	void anAbsentResourceLeavesEveryEraOnItsCompiledConstant() {
		// the offline / pre-cutover path, and the reason this change is behaviour-neutral until the
		// matrix is actually seeded
		EconomyCatalog empty = loadWith(null);
		assertTrue(empty.isEmpty());
		assertNull(empty.find(Era.MEDIEVAL, Race.HUMAN));
	}

	// drive the loader directly against a given body — it takes its source as a parameter, so this
	// touches neither the suite-wide WorldSource nor the shared static INSTANCE
	private static EconomyCatalog loadWith(String json) {
		return EconomyCatalog.load(json == null ? EMPTY_SOURCE : sourceServing(json));
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
}
