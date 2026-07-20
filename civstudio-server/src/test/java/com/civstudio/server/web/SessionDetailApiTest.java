package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The session-detail read surface the admin page is built on (see
 * {@code docs/studio-control-plane-plan.md} §C1): the command-log route, and naming a colony other
 * than the POV one.
 * <p>
 * Two things are asserted that the rest of the read surface does not do: the command log is the one
 * session read that is <b>gated</b> (it records what an owner did), and {@code ?colony=} must reach
 * a colony that is not {@code colonies().get(0)} — the hard-wiring that made a Timeline's other
 * seats invisible over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"civstudio.demo.enabled=false",
		"civstudio.auth.trust-dev-user-header=true",
		"civstudio.auth.admins=super-admin" })
class SessionDetailApiTest {

	private static final int DHENIJANSAR = 4411;
	// a non-demo scenario founds the standard colony without the six caravans — lighter to run
	private static final String SCENARIO = "detail-test";

	@LocalServerPort
	int port;

	@Autowired
	SessionHost host;

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient client = HttpClient.newHttpClient();

	@AfterEach
	void cleanup() {
		host.stopAll();
		// the test classes share one cached context, so they share the registry — forget this
		// test's runs or its save slots stay spent for the next class
		for (var r : host.registry().all())
			host.forget(r.id());
	}

	@Test
	@Timeout(120)
	void commandLogIsOwnerGatedUnlikeEveryOtherSessionRead() throws Exception {
		HostedSession hs = host.create(new SessionSpec(5241L, SCENARIO, DHENIJANSAR), "alice");
		hs.startPaused();
		String path = "/api/sessions/" + hs.id() + "/commands";

		// spectating is public, but the record of what the owner DID is not
		assertEquals(401, get(path, null).statusCode(), "anonymous must be asked to sign in");
		assertEquals(403, get(path, "mallory").statusCode(), "a stranger may not read another's log");
		assertEquals(200, get(path, "alice").statusCode(), "the owner may read it");
		assertEquals(200, get(path, "super-admin").statusCode(), "an admin bypasses ownership");

		// contrast: the ungated reads on the same run stay open to anyone
		assertEquals(200, get("/api/sessions/" + hs.id() + "/colony", null).statusCode(),
				"colony composition is a public read");
	}

	@Test
	@Timeout(120)
	void unownedRunsCommandLogIsOpenToAnySignedInUser() throws Exception {
		// an unowned run (the demo) has no owner to protect — it mirrors who may COMMAND it
		HostedSession hs = host.create(new SessionSpec(5242L, SCENARIO, DHENIJANSAR));
		hs.startPaused();
		String path = "/api/sessions/" + hs.id() + "/commands";

		assertEquals(401, get(path, null).statusCode());
		assertEquals(200, get(path, "anyone").statusCode());
	}

	@Test
	@Timeout(180)
	void commandLogServesTheAppliedHistoryAndTheInFlightCount() throws Exception {
		HostedSession hs = host.create(new SessionSpec(5243L, SCENARIO, DHENIJANSAR), "alice");
		hs.startPaused();
		String path = "/api/sessions/" + hs.id() + "/commands";

		// pure spectator play: the seam is real but empty
		JsonNode empty = json.readTree(get(path, "alice").body());
		assertTrue(empty.path("history").isArray(), "history is an array");
		assertEquals(0, empty.path("history").size(), "nothing applied yet");
		assertEquals(0, empty.path("pending").asInt(), "nothing in flight yet");

		// submit a tax command, then step so it drains at the top of a tick
		HttpResponse<String> posted = client.send(
				HttpRequest.newBuilder(uri("/api/sessions/" + hs.id() + "/commands"))
						.header("Content-Type", "application/json")
						.header(CurrentUserResolver.DEV_USER_HEADER, "alice")
						.POST(HttpRequest.BodyPublishers.ofString(
								"{\"type\":\"setTaxRate\",\"lever\":\"BANK_PROFIT\",\"rate\":0.25}"))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(202, posted.statusCode(), "the command is accepted for a future tick");

		// the run is paused, so nothing drains on its own — step it past the command's tick
		hs.step(3);

		JsonNode applied = awaitHistory(path, "alice");
		assertEquals(1, applied.path("history").size(), "the applied command shows in the log");
		JsonNode row = applied.path("history").path(0);
		assertEquals("setTaxRate", row.path("type").asString());
		assertEquals("BANK_PROFIT", row.path("lever").asString());
		assertEquals(0.25, row.path("rate").asDouble(), 1e-9);
		assertTrue(row.path("tick").asLong() > 0, "an applied command carries the tick it landed on");
	}

	@Test
	@Timeout(120)
	void colonyParamNamesAColonyAndAnUnknownNameIs404() throws Exception {
		HostedSession hs = host.create(new SessionSpec(5244L, SCENARIO, DHENIJANSAR), "alice");
		hs.startPaused();
		String base = "/api/sessions/" + hs.id() + "/colony";

		// no param → the POV colony, exactly as before (the web rail depends on this)
		JsonNode pov = json.readTree(get(base, null).body());
		String name = pov.path("name").asString();
		assertNotNull(name);
		assertTrue(!name.isBlank(), "the POV colony is named");

		// naming that same colony explicitly resolves to it
		JsonNode named = json.readTree(get(base + "?colony=" + name, null).body());
		assertEquals(name, named.path("name").asString());

		// an unknown colony is a 404, never a silent fallback to the POV colony — answering a
		// question nobody asked is worse than saying no
		assertEquals(404, get(base + "?colony=Nowhere", null).statusCode());
	}

	// poll until the submitted command has drained into history (the session thread applies it)
	private JsonNode awaitHistory(String path, String user) throws Exception {
		for (int i = 0; i < 60; i++) {
			JsonNode body = json.readTree(get(path, user).body());
			if (body.path("history").size() > 0)
				return body;
			Thread.sleep(250);
		}
		throw new AssertionError("the command never drained into the applied history");
	}

	private HttpResponse<String> get(String path, String user) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
		if (user != null)
			b.header(CurrentUserResolver.DEV_USER_HEADER, user);
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
