package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.render.SessionSnapshot;

import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the Spring MVC REST + SSE surface end to end against a booted context on a random
 * port (see {@code docs/spring-boot-migration.md}): the session list, the SSE snapshot feed, the
 * taxation command, CORS, and the map bundle. The demo session is disabled so each test drives its
 * own paused session deterministically; {@link #cleanup()} clears the shared host between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "civstudio.demo.enabled=false")
class ServerApiTest {

	private static final int DHENIJANSAR = 4411;

	@LocalServerPort
	int port;

	@Autowired
	SessionHost host;

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient client = HttpClient.newHttpClient();

	@AfterEach
	void cleanup() {
		host.stopAll(); // clear any sessions a test created, so they don't leak into one another
	}

	@Test
	@Timeout(120)
	void streamsSnapshotFramesOverSse() throws Exception {
		HostedSession hs = host.create(SessionSpec.caravanDemo(111L, DHENIJANSAR));
		hs.startPaused(); // emits the tick-0 snapshot; a subscriber gets it immediately

		// the session list reports the hosted session
		HttpResponse<String> list = client.send(
				HttpRequest.newBuilder(uri("/api/sessions")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, list.statusCode());
		assertTrue(list.body().contains(hs.id()), "the session should be listed");

		// subscribe to the SSE stream
		HttpResponse<java.io.InputStream> stream = client.send(
				HttpRequest.newBuilder(uri("/api/sessions/" + hs.id() + "/stream"))
						.timeout(Duration.ofSeconds(30)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200, stream.statusCode());
		assertTrue(stream.headers().firstValue("Content-Type").orElse("")
				.startsWith("text/event-stream"));

		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
			// first frame is the cached tick-0 snapshot pushed on subscribe
			SessionSnapshot first = json.readValue(nextData(r), SessionSnapshot.class);
			assertEquals(hs.id(), first.sessionId());
			assertEquals(6, first.caravans().size(), "the demo streams six caravans");
			assertEquals(1, first.colonies().size());

			// stepping produces a fresh frame at a later tick
			hs.step(2);
			SessionSnapshot next;
			do {
				next = json.readValue(nextData(r), SessionSnapshot.class);
			} while (next.tick() <= first.tick());
			assertTrue(next.tick() >= 1, "the streamed tick advances after stepping");
		}
	}

	@Test
	@Timeout(120)
	void taxRateCommandOverHttpMovesTheRulerLever() throws Exception {
		HostedSession hs = host.create(SessionSpec.caravanDemo(222L, DHENIJANSAR));
		hs.startPaused(); // paused at tick 0; the command lands on the next tick
		assertNotNull(hs.colonies().get(0).getRuler(), "the demo colony has a ruler");

		// POST a real player command; the endpoint accepts it and reports the apply tick
		HttpResponse<String> ok = client.send(
				HttpRequest.newBuilder(uri("/api/sessions/" + hs.id() + "/commands"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(
								"{\"type\":\"setTaxRate\",\"lever\":\"bankProfit\",\"rate\":0.3}"))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(202, ok.statusCode());
		assertTrue(ok.body().contains("\"accepted\":true"), ok.body());

		// step past the command's tick; the ruler's lever moves to the commanded rate
		hs.step(3);
		long deadline = System.nanoTime() + 60_000_000_000L;
		while (hs.colonies().get(0).getRuler().getBankProfitTaxRate() < 0.3
				&& System.nanoTime() < deadline)
			Thread.sleep(5);
		assertEquals(0.3, hs.colonies().get(0).getRuler().getBankProfitTaxRate(), 1e-9,
				"the HTTP command should have moved the bank-profit tax lever");

		// an unknown command type is rejected, not silently accepted
		HttpResponse<String> bad = client.send(
				HttpRequest.newBuilder(uri("/api/sessions/" + hs.id() + "/commands"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"bogus\"}"))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(400, bad.statusCode());
	}

	@Test
	@Timeout(120)
	void corsAllowsTheSiteOriginAndPreflightsThePost() throws Exception {
		HostedSession hs = host.create(SessionSpec.caravanDemo(333L, DHENIJANSAR));
		hs.startPaused();

		// a production site origin is echoed in Access-Control-Allow-Origin
		HttpResponse<String> allowed = client.send(
				HttpRequest.newBuilder(uri("/api/sessions"))
						.header("Origin", "https://anbennar.civstudio.com").GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals("https://anbennar.civstudio.com",
				allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

		// an unknown origin gets no CORS grant (Spring rejects the cross-origin request)
		HttpResponse<String> denied = client.send(
				HttpRequest.newBuilder(uri("/api/sessions"))
						.header("Origin", "https://evil.example").GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
				"an unlisted origin must not be granted CORS");

		// the JSON POST's preflight is answered (200 + methods) so the browser proceeds
		HttpResponse<String> pre = client.send(
				HttpRequest.newBuilder(uri("/api/sessions/" + hs.id() + "/commands"))
						.header("Origin", "https://anbennar.civstudio.com")
						.header("Access-Control-Request-Method", "POST")
						.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, pre.statusCode());
		assertTrue(pre.headers().firstValue("Access-Control-Allow-Methods")
				.orElse("").contains("POST"));
		assertEquals("https://anbennar.civstudio.com",
				pre.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
	}

	@Test
	@Timeout(120)
	void serveBundleReturnsTheMapBundleJsonAndGzips() throws Exception {
		// plain GET: JSON with the province backbone + engine-derived + merged asset fields, and the
		// site origin is echoed for CORS
		HttpResponse<String> res = client.send(
				HttpRequest.newBuilder(uri("/api/bundle"))
						.header("Origin", "https://anbennar.civstudio.com").GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
		assertEquals("https://anbennar.civstudio.com",
				res.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
		var bundle = json.readTree(res.body());
		int provinceCount = bundle.get("provinces").size();
		assertTrue(provinceCount > 4000, "expected the whole world, got " + provinceCount + " provinces");
		for (String key : new String[] { "geo", "geoNames", "adjacencies", "terrainColors", "plotIndex" })
			assertTrue(bundle.has(key), "bundle missing key: " + key);

		// with Accept-Encoding: gzip the server ships the gzipped copy; it gunzips to the same bundle
		HttpResponse<byte[]> gz = client.send(
				HttpRequest.newBuilder(uri("/api/bundle"))
						.header("Accept-Encoding", "gzip").GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals("gzip", gz.headers().firstValue("Content-Encoding").orElse(null));
		try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz.body()))) {
			assertEquals(provinceCount, json.readTree(in).get("provinces").size());
		}
	}

	@Test
	@Timeout(120)
	void actuatorHealthIsUpAndCorsAllowsTheSite() throws Exception {
		// the static site polls this during its loading screen and reads status==UP; it does so
		// cross-origin, so the CORS header must be present (Actuator CORS is its own config).
		HttpResponse<String> res = client.send(
				HttpRequest.newBuilder(uri("/actuator/health"))
						.header("Origin", "https://anbennar.civstudio.com").GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"status\":\"UP\""), res.body());
		assertEquals("https://anbennar.civstudio.com",
				res.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	// read the next SSE "data:" line's payload (Spring writes "data:<json>", no leading space)
	private static String nextData(BufferedReader r) throws IOException {
		String line;
		while ((line = r.readLine()) != null)
			if (line.startsWith("data:")) {
				String d = line.substring("data:".length());
				return d.startsWith(" ") ? d.substring(1) : d;
			}
		throw new IOException("stream ended before a data frame");
	}
}
