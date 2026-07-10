package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

import tools.jackson.databind.ObjectMapper;

/**
 * Phase-1 session ownership (see {@code docs/authentication.md}): write endpoints
 * (control/commands) are gated on the session's owner, while an unowned/public session (the
 * demo) stays open to everyone and read/spectate endpoints are unaffected. The caller's
 * identity is supplied through the development {@code X-CivStudio-User} header, trusted here via
 * {@code civstudio.auth.trust-dev-user-header=true} (the Phase-1 stand-in for real login). The
 * demo is disabled so each test drives its own session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"civstudio.demo.enabled=false",
		"civstudio.auth.trust-dev-user-header=true" })
class SessionOwnershipTest {

	private static final int DHENIJANSAR = 4411;
	// a non-demo scenario founds the standard colony without the six caravans — lighter, and the
	// authz outcome is identical
	private static final String SCENARIO = "ownership-test";

	@LocalServerPort
	int port;

	@Autowired
	SessionHost host;

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient client = HttpClient.newHttpClient();

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	@Test
	void sessionKeyEncodesTheOwnerSoOwnedRunsNeverCollide() {
		SessionSpec spec = new SessionSpec(4242L, SCENARIO, DHENIJANSAR);
		// unowned keeps the plain spec id (the demo's stable "caravan-demo-<seed>")
		assertEquals(spec.id(), SessionHost.sessionKey(spec, null));
		assertEquals(spec.id(), SessionHost.sessionKey(spec, "  "));
		// different owners of the same spec get different ids; neither is the unowned id
		String alice = SessionHost.sessionKey(spec, "alice");
		String bob = SessionHost.sessionKey(spec, "bob");
		assertNotEquals(alice, bob);
		assertNotEquals(spec.id(), alice);
	}

	@Test
	@Timeout(120)
	void ownerCanWriteOwnedSessionOthersAreForbidden() throws Exception {
		HostedSession hs = host.create(new SessionSpec(4243L, SCENARIO, DHENIJANSAR), "alice");
		assertEquals("alice", hs.owner());
		hs.startPaused();

		// the owner may control and command it
		assertEquals(200, control(hs.id(), "alice"));
		assertEquals(202, command(hs.id(), "alice"));
		// a different authenticated user may not
		assertEquals(403, control(hs.id(), "bob"));
		assertEquals(403, command(hs.id(), "bob"));
		// nor may an anonymous caller
		assertEquals(403, control(hs.id(), null));
		assertEquals(403, command(hs.id(), null));
	}

	@Test
	@Timeout(120)
	void unownedSessionStaysOpenToEveryone() throws Exception {
		// owner == null (the server-seeded demo is founded exactly this way)
		HostedSession hs = host.create(new SessionSpec(4244L, SCENARIO, DHENIJANSAR));
		hs.startPaused();

		assertEquals(200, control(hs.id(), null), "an unowned session is open to anonymous callers");
		assertEquals(202, command(hs.id(), "whoever"), "and to any authenticated caller");
	}

	@Test
	@Timeout(120)
	void createEndpointMakesTheFounderTheOwner() throws Exception {
		// POST a session as alice; she becomes its owner, so bob is forbidden and she is not
		HttpResponse<String> created = send("POST", "/api/sessions", "alice",
				"{\"seed\":4245,\"scenario\":\"" + SCENARIO + "\",\"provinceId\":" + DHENIJANSAR + "}");
		assertEquals(201, created.statusCode());
		String id = json.readTree(created.body()).get("id").asText();

		assertEquals(403, control(id, "bob"));
		assertEquals(200, control(id, "alice"));
	}

	// POST a harmless control action (pause) as `user` (null = anonymous); return the status code
	private int control(String id, String user) throws Exception {
		return send("POST", "/api/sessions/" + id + "/control", user, "{\"action\":\"pause\"}")
				.statusCode();
	}

	// POST a valid setTaxRate command as `user` (null = anonymous); return the status code
	private int command(String id, String user) throws Exception {
		return send("POST", "/api/sessions/" + id + "/commands", user,
				"{\"type\":\"setTaxRate\",\"lever\":\"bankProfit\",\"rate\":0.3}").statusCode();
	}

	private HttpResponse<String> send(String method, String path, String user, String body)
			throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		if (user != null)
			b.header(CurrentUserResolver.DEV_USER_HEADER, user);
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
