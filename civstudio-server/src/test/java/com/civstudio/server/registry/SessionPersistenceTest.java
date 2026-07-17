package com.civstudio.server.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.settlement.Settlement;

/**
 * With a datasource configured (H2 here), runs and seats are written to the actual database — the
 * Phase-6 contract ({@code docs/spectator-lobby.md}). Verifies that {@code PersistenceConfig} wires
 * the {@link JdbcSessionRegistry}, that {@link SessionHost} records what it does, and — the point of
 * the whole phase — that <b>a redeploy cannot hand a ranked player a second attempt</b>.
 */
@SpringBootTest(properties = {
		"civstudio.demo.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:registry;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=" })
class SessionPersistenceTest {

	private static final int DHENIJANSAR = 4411;

	@Autowired
	SessionHost host;

	@Autowired
	SessionRegistry registry;

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	@Test
	void aDatasourceMakesTheRegistryDurable() {
		assertInstanceOf(JdbcSessionRegistry.class, registry,
				"with a datasource the registry must be the durable one, or ranked silently resets");
	}

	@Test
	@Timeout(180)
	void foundingARunWritesItDown() {
		HostedSession hs = host.create(new SessionSpec(6001L, "registry-test", DHENIJANSAR), "alice");

		SessionRecord r = registry.find(hs.id()).orElseThrow();
		assertEquals("registry-test", r.scenario());
		assertEquals(6001L, r.seed());
		assertEquals(DHENIJANSAR, r.provinceId());
		assertEquals("alice", r.owner());
		assertEquals("CREATED", r.state(), "recorded before it can end");
		assertTrue(registry.all().stream().anyMatch(x -> x.id().equals(hs.id())));
	}

	@Test
	@Timeout(180)
	void joiningATimelineWritesTheSeat() {
		HostedSession hs = host.create(SessionSpec.timeline(6002L, DHENIJANSAR), null);
		Settlement alice = host.joinTimeline(hs.id(), "alice");
		Settlement bob = host.joinTimeline(hs.id(), "bob");

		List<SeatRecord> seats = registry.seats(hs.id());
		assertEquals(2, seats.size());
		assertEquals("alice", seats.get(0).userId(), "seats are recorded in join order");
		assertEquals(alice.getName(), seats.get(0).colonyName());
		assertEquals(alice.getProvince().id(), seats.get(0).provinceId());
		assertEquals("bob", seats.get(1).userId());
		assertEquals(bob.getProvince().id(), seats.get(1).provinceId());

		assertEquals("alice", registry.seatOf(hs.id(), "alice").orElseThrow().userId());
		assertTrue(registry.seatOf(hs.id(), "carol").isEmpty());
	}

	/**
	 * The whole reason this phase exists. A redeploy throws away the live session map; if the seat
	 * only lived there, a ranked player would quietly get a second attempt. The durable record is
	 * what refuses them.
	 */
	@Test
	@Timeout(300)
	void aRedeployCannotHandAPlayerASecondSeat() {
		SessionSpec spec = SessionSpec.timeline(6003L, DHENIJANSAR);
		HostedSession before = host.create(spec, null);
		host.joinTimeline(before.id(), "alice");
		String id = before.id();

		// the redeploy: this process forgets every live session, the database does not
		host.stopAll();
		HostedSession after = host.create(spec, null);
		assertEquals(id, after.id(), "the same spec comes back under the same id");
		assertTrue(after.colonies().isEmpty(), "the live world is genuinely gone");

		SessionRegistry.SeatTakenException denied = assertThrows(
				SessionRegistry.SeatTakenException.class, () -> host.joinTimeline(id, "alice"));
		assertTrue(denied.getMessage().contains("alice"), denied.getMessage());
		// ...and the record still knows exactly what she held
		assertNotNull(registry.seatOf(id, "alice").orElseThrow().colonyName());
		// a player who never joined is still welcome
		assertNotNull(host.joinTimeline(id, "bob"));
	}

	/** A finished run's outcome is a column: it survives without replaying a single tick. */
	@Test
	@Timeout(600)
	void theOutcomeOfAFinishedRunIsRecorded() {
		HostedSession hs = host.create(SessionSpec.caravanDemo(555L, DHENIJANSAR), null);
		hs.setTickRateMillis(0);
		hs.start();

		long deadline = System.nanoTime() + 540_000L * 1_000_000L;
		while (!hs.isTerminal() && System.nanoTime() < deadline)
			Thread.onSpinWait();
		assertEquals(HostedSession.State.GAME_OVER, hs.state(), "the demo colony collapses");

		SessionRecord r = registry.find(hs.id()).orElseThrow();
		assertEquals("GAME_OVER", r.state(), "the end is written down, not just broadcast");
		assertEquals(hs.endReason(), r.endReason());
		assertEquals(hs.tick(), r.tick(), "how far it got");
		assertTrue(r.isTerminal());
	}
}
