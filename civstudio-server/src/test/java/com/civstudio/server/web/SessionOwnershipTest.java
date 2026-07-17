package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
		"civstudio.auth.trust-dev-user-header=true",
		"civstudio.auth.admins=super-admin" })
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
		// these classes share one cached Spring context, so they share the registry: forget this
		// test's runs or its save slots are still spent when the next class runs
		for (var r : host.registry().all())
			host.forget(r.id());
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
		// a different authenticated user may not (control or command)
		assertEquals(403, control(hs.id(), "bob"));
		assertEquals(403, command(hs.id(), "bob"));
		// an anonymous caller is unauthenticated → 401 on both write endpoints
		assertEquals(401, control(hs.id(), null));
		assertEquals(401, command(hs.id(), null));
	}

	@Test
	@Timeout(120)
	void unownedSessionWritesRequireAuthentication() throws Exception {
		// owner == null (the server-seeded demo is founded exactly this way)
		HostedSession hs = host.create(new SessionSpec(4244L, SCENARIO, DHENIJANSAR));
		hs.startPaused();

		// both control and commands require a signed-in user, even on the unowned demo
		assertEquals(401, control(hs.id(), null), "anonymous cannot control the session");
		assertEquals(401, command(hs.id(), null), "anonymous cannot command the session");
		// any authenticated user may drive an unowned session
		assertEquals(200, control(hs.id(), "anyone"), "any authenticated user can control the demo");
		assertEquals(202, command(hs.id(), "whoever"), "and command it");
	}

	@Test
	@Timeout(120)
	void adminMayWriteAnyOwnedSession() throws Exception {
		// alice owns it; bob (a plain user) is forbidden, but super-admin (allow-listed) may write
		HostedSession hs = host.create(new SessionSpec(4246L, SCENARIO, DHENIJANSAR), "alice");
		hs.startPaused();

		assertEquals(403, control(hs.id(), "bob"), "a non-owner is forbidden");
		assertEquals(200, control(hs.id(), "super-admin"), "an admin bypasses ownership on control");
		assertEquals(202, command(hs.id(), "super-admin"), "an admin bypasses ownership on commands");
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

	/**
	 * Phase 2 ({@code docs/spectator-lobby.md}): a command names its COLONY, and it is that
	 * colony's owner who may submit it. An unclaimed colony belongs to whoever owns the run, so a
	 * single-player session behaves exactly as it did before.
	 */
	@Test
	@Timeout(120)
	void aCommandIsGatedOnTheColonysOwnerNotTheRuns() throws Exception {
		HostedSession hs = host.create(new SessionSpec(4247L, SCENARIO, DHENIJANSAR), "alice");
		hs.startPaused();
		String colony = hs.colonies().get(0).getName();

		// unclaimed: the colony answers with the run's owner
		assertEquals("alice", hs.ownerOf(colony));
		assertEquals(202, commandColony(hs.id(), "alice", colony), "the owner may move her lever");
		assertEquals(403, commandColony(hs.id(), "bob", colony), "a stranger may not");
		assertEquals(401, commandColony(hs.id(), null, colony), "nor may anonymous");
		assertEquals(202, commandColony(hs.id(), "super-admin", colony),
				"an admin bypasses colony ownership");

		// a command naming a colony the session does not have is a 404, not a silent no-op
		assertEquals(404, commandColony(hs.id(), "alice", "Nowhere"));
	}

	/**
	 * The seat, not the run: a colony CLAIMED by a player belongs to that player even though the
	 * run belongs to someone else — the shape a royale Timeline needs (a house-owned session full
	 * of players' colonies).
	 */
	@Test
	@Timeout(120)
	void aClaimedColonyBelongsToItsPlayerNotTheRunsOwner() throws Exception {
		// a house-owned (unowned) session — as a Timeline will be
		HostedSession hs = host.create(new SessionSpec(4248L, SCENARIO, DHENIJANSAR), null);
		hs.startPaused();
		String colony = hs.colonies().get(0).getName();

		// unclaimed + unowned run: open to any signed-in user (today's demo behaviour)
		assertEquals(202, commandColony(hs.id(), "carol", colony));

		hs.claimColony(colony, "bob"); // bob takes the seat
		assertEquals("bob", hs.ownerOf(colony));
		assertEquals(202, commandColony(hs.id(), "bob", colony), "the seat's owner may command it");
		assertEquals(403, commandColony(hs.id(), "carol", colony),
				"a claimed colony is no longer anyone's to command");

		// a seat cannot be taken from under its owner, nor claimed twice by different users
		assertThrows(IllegalStateException.class, () -> hs.claimColony(colony, "carol"));
		hs.claimColony(colony, "bob"); // idempotent for the same user
		assertThrows(IllegalArgumentException.class, () -> hs.claimColony("Nowhere", "bob"));
	}

	/**
	 * A Timeline over the wire ({@code docs/spectator-lobby.md} Phase 3): joining takes a seat, the
	 * roster closes at the gun, and the shared clock is admins-only — nobody pauses the world their
	 * rivals are living in.
	 */
	@Test
	@Timeout(180)
	void aTimelineIsJoinedByPlayersAndClockedOnlyByAdmins() throws Exception {
		HostedSession hs = host.create(SessionSpec.timeline(4249L, DHENIJANSAR), null);

		// anonymous may watch, never take a seat
		assertEquals(401, join(hs.id(), null));
		assertEquals(201, join(hs.id(), "alice"));
		assertEquals(201, join(hs.id(), "bob"));
		assertEquals(201, join(hs.id(), "alice"), "idempotent — she still holds one seat");
		assertEquals(2, hs.colonies().size(), "two players, two colonies");

		// the clock belongs to everyone, so no player may touch it — even one seated in it
		assertEquals(403, control(hs.id(), "alice"), "a player cannot pause the shared world");
		assertEquals(403, control(hs.id(), "bob"));
		assertEquals(401, control(hs.id(), null));

		// ...but each player commands their OWN colony
		String aliceColony = host.get(hs.id()).colonyOf("alice").getName();
		assertEquals(202, commandColony(hs.id(), "alice", aliceColony));
		assertEquals(403, commandColony(hs.id(), "bob", aliceColony),
				"bob does not set alice's taxes");

		// the gun is an admin's to fire, and it closes the roster
		assertEquals(200, send("POST", "/api/sessions/" + hs.id() + "/control", "super-admin",
				"{\"action\":\"start\"}").statusCode());
		assertEquals(409, join(hs.id(), "carol"), "the roster is closed once it runs");
	}

	// POST a join as `user` (null = anonymous); return the status code
	private int join(String id, String user) throws Exception {
		return send("POST", "/api/sessions/" + id + "/join", user, "{}").statusCode();
	}

	// POST a setTaxRate command naming `colony`, as `user` (null = anonymous); return the status
	private int commandColony(String id, String user, String colony) throws Exception {
		return send("POST", "/api/sessions/" + id + "/commands", user,
				"{\"type\":\"setTaxRate\",\"colony\":\"" + colony
						+ "\",\"lever\":\"bankProfit\",\"rate\":0.3}").statusCode();
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
