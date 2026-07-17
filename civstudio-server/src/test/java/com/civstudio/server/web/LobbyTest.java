package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.civstudio.server.HostedSession;
import com.civstudio.server.LobbyRoom;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Spectator Lobby ({@code docs/spectator-lobby.md} Phase 1): a chat room that belongs to no
 * session, and a session list that shows what you are allowed to see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"civstudio.demo.enabled=false",
		"civstudio.auth.trust-dev-user-header=true",
		"civstudio.auth.admins=super-admin" })
class LobbyTest {

	private static final int DHENIJANSAR = 4411;
	private static final String SCENARIO = "lobby-test";

	@LocalServerPort
	int port;

	@Autowired
	SessionHost host;

	@Autowired
	LobbyRoom room;

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient client = HttpClient.newHttpClient();

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	@Test
	@Timeout(60)
	void talkingNeedsSigningInWatchingDoesNot() throws Exception {
		assertEquals(401, chat(null, "hello"), "the signed-out may watch the lobby, not talk in it");
		assertEquals(202, chat("alice", "hello"));
		assertEquals(400, chat("alice", "   "), "an empty message is not a message");
	}

	@Test
	@Timeout(60)
	void thePostersNameIsResolvedServerSideNotTaken() throws Exception {
		HttpResponse<String> res = send("POST", "/api/lobby/chat", "alice",
				"{\"text\":\"hi\",\"user\":\"bob\"}");
		assertEquals(202, res.statusCode());
		assertEquals("alice", json.readTree(res.body()).get("user").asText(),
				"the body does not get to say who you are");
	}

	/** The lobby room is a ChatStore room under a reserved key — it cannot collide with a session. */
	@Test
	@Timeout(60)
	void theLobbyRoomIsNotASession() {
		assertTrue(LobbyRoom.ROOM.startsWith("@"),
				"the reserved key must be one no <scenario>-<seed> id can produce");
		SessionSpec spec = new SessionSpec(7001L, SCENARIO, DHENIJANSAR);
		assertFalse(spec.id().equals(LobbyRoom.ROOM));
	}

	/** Arriving in the lobby shows a conversation already in progress, not an empty box. */
	@Test
	@Timeout(60)
	void arrivingReplaysTheBacklog() throws Exception {
		chat("alice", "anyone watching the Timeline?");
		chat("bob", "bo is about to collapse");

		List<String> seen = new java.util.ArrayList<>();
		try (AutoCloseable sub = room.subscribe(m -> seen.add(m.user() + ": " + m.text()))) {
			assertTrue(seen.size() >= 2, "the backlog is replayed on arrival: " + seen);
			assertTrue(seen.get(seen.size() - 1).contains("about to collapse"), seen.toString());
			// ...and a later message reaches the listener that is already here
			chat("carol", "it did");
			assertTrue(seen.get(seen.size() - 1).contains("it did"), seen.toString());
		}
	}

	@Test
	@Timeout(60)
	void unsubscribingStopsTheMessages() throws Exception {
		List<String> seen = new java.util.ArrayList<>();
		AutoCloseable sub = room.subscribe(m -> seen.add(m.text()));
		int atSubscribe = seen.size();
		sub.close();
		chat("alice", "into the void");
		assertEquals(atSubscribe, seen.size(), "a closed listener hears nothing more");
	}

	/**
	 * The list is the lobby's window on what is running: public runs for everyone, your own save
	 * slots only for you.
	 */
	@Test
	@Timeout(240)
	void theListShowsPublicRunsToAllAndPrivateOnesOnlyToTheirOwner() throws Exception {
		HostedSession demo = host.create(new SessionSpec(7002L, SCENARIO, DHENIJANSAR), null);
		HostedSession timeline = host.create(SessionSpec.timeline(7003L, DHENIJANSAR), null);
		HostedSession aliceSlot = host.create(new SessionSpec(7004L, SCENARIO, DHENIJANSAR), "alice");

		// signed out: the public runs, and nobody's private slot
		List<String> anon = ids(list(null));
		assertTrue(anon.contains(demo.id()), "the demo is public: " + anon);
		assertTrue(anon.contains(timeline.id()), "a Timeline is public — watching the top run is the point");
		assertFalse(anon.contains(aliceSlot.id()), "alice's save slot is not the world's business");

		// bob sees the same as anyone: not alice's
		assertFalse(ids(list("bob")).contains(aliceSlot.id()));
		// alice sees hers
		assertTrue(ids(list("alice")).contains(aliceSlot.id()), "your own run is yours to see");
		// an admin sees everything — they are the one asked to fix it
		assertTrue(ids(list("super-admin")).contains(aliceSlot.id()));
	}

	/** A row carries what a lobby row draws: kind, date, watchers, and a Timeline's contest. */
	@Test
	@Timeout(240)
	void aRowSaysWhatKindOfRunItIsAndHowTheContestStands() throws Exception {
		HostedSession timeline = host.create(SessionSpec.timeline(7005L, DHENIJANSAR), null);
		host.joinTimeline(timeline.id(), "alice");
		host.joinTimeline(timeline.id(), "bob");
		host.create(new SessionSpec(7006L, SCENARIO, DHENIJANSAR), "alice");

		JsonNode t = row(list("alice"), timeline.id());
		assertEquals("timeline", t.get("kind").asText());
		assertEquals(2, t.get("seats").asInt(), "two players founded");
		assertEquals(2, t.get("standing").asInt(), "and both are still standing");
		assertEquals(0, t.get("watching").asInt(), "nobody has opened a feed on it");
		assertNotNull(t.get("date").asText(), "a lobby row shows the in-game date");
		assertFalse(t.get("mine").asBoolean(), "a Timeline is the house's, not a player's");

		JsonNode slot = row(list("alice"), SessionHost.sessionKey(
				new SessionSpec(7006L, SCENARIO, DHENIJANSAR), "alice"));
		assertEquals("single-player", slot.get("kind").asText());
		assertTrue(slot.get("mine").asBoolean(), "alice's own run is marked as hers");
	}

	private JsonNode list(String user) throws Exception {
		return json.readTree(send("GET", "/api/sessions", user, null).body());
	}

	private static List<String> ids(JsonNode rows) {
		List<String> out = new java.util.ArrayList<>();
		rows.forEach(r -> out.add(r.get("id").asText()));
		return out;
	}

	private static JsonNode row(JsonNode rows, String id) {
		for (JsonNode r : rows)
			if (r.get("id").asText().equals(id))
				return r;
		throw new AssertionError("no row for " + id + " in " + rows);
	}

	private int chat(String user, String text) throws Exception {
		return send("POST", "/api/lobby/chat", user, "{\"text\":\"" + text + "\"}").statusCode();
	}

	private HttpResponse<String> send(String method, String path, String user, String body)
			throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(body));
		if (user != null)
			b.header("X-CivStudio-User", user);
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}
}
