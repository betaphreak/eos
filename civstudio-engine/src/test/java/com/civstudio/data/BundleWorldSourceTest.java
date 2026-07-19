package com.civstudio.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The bundle-backed WorldSource mechanics: serve resources[path], fall back to the classpath, versions. */
class BundleWorldSourceTest {

	private static final ObjectMapper M = new ObjectMapper();

	@Test
	void servesResourcesAndFallsBackToClasspath() throws Exception {
		JsonNode bundle = M.readTree("""
				{ "meta": {"mapVersion": 9, "contentVersion": "test-1"},
				  "resources": { "/map/countries.json": [ {"tag":"A01","name":"Lorent"} ] } }""");
		BundleWorldSource src = new BundleWorldSource(bundle);

		assertEquals(9, src.mapVersion());
		assertEquals("test-1", src.contentVersion());

		// served from the bundle, re-serialized so the loader's parser reads it identically
		try (InputStream in = src.open("/map/countries.json")) {
			JsonNode got = M.readTree(in);
			assertEquals(1, got.size());
			assertEquals("A01", got.get(0).get("tag").asString());
		}
		assertTrue(src.exists("/map/countries.json"));

		// a path absent from the bundle falls back to the classpath (/units.json is a real committed resource)
		assertTrue(src.exists("/units.json"));
		try (InputStream in = src.open("/units.json")) {
			assertNotNull(in);
		}

		// a genuinely-missing resource is null — optional-resource callers rely on this
		assertNull(src.open("/does-not-exist.json"));
		assertFalse(src.exists("/does-not-exist.json"));
	}
}
