package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import tools.jackson.databind.ObjectMapper;

/**
 * Single-player save slots ({@code docs/spectator-lobby.md} Phase 4): a player gets a finite number
 * of runs in play, each starting paused and belonging to them alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"civstudio.demo.enabled=false",
		"civstudio.auth.trust-dev-user-header=true",
		"civstudio.auth.admins=super-admin" })
class SaveSlotTest {

	private static final int DHENIJANSAR = 4411;
	private static final String SCENARIO = "slot-test";

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

	/** Starting paused is the point: you land on the world and survey it before committing. */
	@Test
	@Timeout(180)
	void aNewRunStartsPausedButTheDemoRuns() throws Exception {
		HttpResponse<String> mine = create("alice", 8001);
		assertEquals(201, mine.statusCode());
		assertEquals("PAUSED", json.readTree(mine.body()).get("clockState").asText(),
				"a save slot waits for you to press play");

		// the demo (unowned) still runs on arrival — a demo nobody pressed play on is a dead demo
		HttpResponse<String> demo = create(null, 8002);
		assertEquals(201, demo.statusCode());
		assertEquals("RUNNING", json.readTree(demo.body()).get("clockState").asText());
	}

	@Test
	@Timeout(300)
	void aPlayerGetsFiveRunsInPlayAndNoMore() throws Exception {
		for (int i = 0; i < SessionHost.SAVE_SLOT_LIMIT; i++)
			assertEquals(201, create("bob", 8100 + i).statusCode(), "slot " + (i + 1) + " of 5");
		assertEquals(SessionHost.SAVE_SLOT_LIMIT, host.saveSlotsOf("bob").size());

		HttpResponse<String> sixth = create("bob", 8200);
		assertEquals(409, sixth.statusCode(), "a sixth run does not fit");
		assertTrue(sixth.body().contains("finish or delete one"), sixth.body());
		assertEquals(5, json.readTree(sixth.body()).get("limit").asInt());

		// ...but re-founding a run bob already has is not a new slot
		assertEquals(201, create("bob", 8100).statusCode(), "your own run is not a sixth run");
		// ...and another player's slots are their own business
		assertEquals(201, create("carol", 8300).statusCode(), "carol's shelf is empty");
	}

	/**
	 * A finished run frees its slot. Colonies collapse by design, so if a dead run held its slot
	 * forever, five collapses would lock a player out of the game permanently. Its record persists —
	 * the run happened — but the slot does not.
	 */
	@Test
	@Timeout(300)
	void aFinishedRunFreesItsSlotButKeepsItsRecord() throws Exception {
		for (int i = 0; i < SessionHost.SAVE_SLOT_LIMIT; i++)
			assertEquals(201, create("dave", 8400 + i).statusCode());
		assertEquals(409, create("dave", 8500).statusCode(), "full");

		// one of dave's colonies collapses (the registry is the authority on that)
		String dead = SessionHost.sessionKey(new SessionSpec(8400L, SCENARIO, DHENIJANSAR), "dave");
		host.registry().updateProgress(dead, "STOPPED", "ABANDONED", "Dhenijansar departed as a Caravan", 2639);

		assertEquals(SessionHost.SAVE_SLOT_LIMIT - 1, host.saveSlotsOf("dave").size(),
				"a finished run is a record, not a slot");
		assertEquals(201, create("dave", 8500).statusCode(), "so he can start another");
		assertTrue(host.registry().find(dead).orElseThrow().isFinished(),
				"and the finished run is still remembered");
	}

	/** A run merely STOPPED is still playable, so it still holds its slot. */
	@Test
	@Timeout(300)
	void aStoppedRunStillHoldsItsSlot() throws Exception {
		HttpResponse<String> made = create("erin", 8600);
		assertEquals(201, made.statusCode());
		String id = json.readTree(made.body()).get("id").asText();
		host.get(id).stop();

		assertEquals(1, host.saveSlotsOf("erin").size(),
				"stopped is not finished — the run is still hers to come back to");
	}

	@Test
	@Timeout(180)
	void onlyYourOwnRunsCanBeDeleted() throws Exception {
		HttpResponse<String> made = create("frank", 8700);
		String id = json.readTree(made.body()).get("id").asText();

		assertEquals(401, delete(id, null), "signed out deletes nothing");
		assertEquals(403, delete(id, "mallory"), "nor does someone else");
		assertEquals(1, host.saveSlotsOf("frank").size(), "still there");

		assertEquals(204, delete(id, "frank"), "your own run is yours to delete");
		assertEquals(0, host.saveSlotsOf("frank").size(), "the slot is free");
		assertTrue(host.registry().find(id).isEmpty(), "and the record is gone with it");
	}

	/**
	 * A Timeline is nobody's save slot. Deleting one would destroy its verdict and hand every player
	 * in it another attempt — the thing the registry exists to prevent — so not even an admin does it
	 * through this door.
	 */
	@Test
	@Timeout(180)
	void aTimelineIsNotASaveSlotAndCannotBeDeleted() throws Exception {
		HostedSession timeline = host.create(SessionSpec.timeline(8800L, DHENIJANSAR), null);
		host.joinTimeline(timeline.id(), "alice");

		assertEquals(403, delete(timeline.id(), "alice"), "a seat is not a save");
		assertEquals(403, delete(timeline.id(), "super-admin"), "not even for an admin");
		assertTrue(host.registry().find(timeline.id()).isPresent(), "the Timeline is untouched");
		assertFalse(host.saveSlotsOf("alice").stream().anyMatch(r -> r.id().equals(timeline.id())),
				"and a Timeline seat never counted against her slots");

		// the demo is nobody's either
		HostedSession demo = host.create(new SessionSpec(8900L, SCENARIO, DHENIJANSAR), null);
		assertEquals(403, delete(demo.id(), "alice"));
	}

	private HttpResponse<String> create(String user, long seed) throws Exception {
		return send("POST", "/api/sessions", user, "{\"seed\":" + seed + ",\"scenario\":\""
				+ SCENARIO + "\",\"provinceId\":" + DHENIJANSAR + "}");
	}

	private int delete(String id, String user) throws Exception {
		return send("DELETE", "/api/sessions/" + id, user, null).statusCode();
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
