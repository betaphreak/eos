package com.civstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.civstudio.era.Era;
import com.civstudio.settlement.Settlement;

/**
 * The ranked <b>Timeline</b> ({@code docs/spectator-lobby.md} Phase 3): one shared world that many
 * players found into, opened for joins, then run in lockstep until one colony stands.
 */
class TimelineTest {

	private static final int ANCHOR = 4411; // Dhenijansar — the first joiner's site
	private static final long RANKED_SEED = 7654321L;

	private final SessionHost host = new SessionHost();

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	private HostedSession openTimeline(long seed) {
		return host.create(SessionSpec.timeline(seed, ANCHOR), null); // house-owned
	}

	@Test
	@Timeout(180)
	void aTimelineIsBornEmptyAndFillsAsPlayersJoin() {
		HostedSession hs = openTimeline(RANKED_SEED);
		assertTrue(hs.isTimeline());
		assertEquals(SessionKind.TIMELINE, hs.kind(), "a Timeline is its own kind");
		assertEquals(ClockState.CREATED, hs.clock(), "it opens for joins, not running");
		assertTrue(hs.colonies().isEmpty(), "a Timeline founds no colony of its own");

		Settlement alice = host.joinTimeline(hs.id(), "alice");
		assertEquals(1, hs.colonies().size());
		assertEquals("alice", hs.ownerOf(alice.getName()), "her seat is hers, not the house's");
		assertEquals(ANCHOR, alice.getProvince().id(), "the first joiner takes the anchor");

		Settlement bob = host.joinTimeline(hs.id(), "bob");
		assertEquals(2, hs.colonies().size());
		assertEquals("bob", hs.ownerOf(bob.getName()));
		assertNotEquals(alice.getProvince().id(), bob.getProvince().id(),
				"rivals do not found on top of each other");
	}

	/**
	 * Each seat founds on <b>its own</b> economy, resolved from the race of the province it stands
	 * in — not on the run config's. The per-colony economy work exists so a Timeline can seat
	 * players of different races and have them run different economics; this pins the wiring that
	 * makes that possible, at the seat path rather than in the engine.
	 * <p>
	 * It deliberately does <em>not</em> assert that two seats differ: {@code Era.economy(Race)}
	 * still answers the human column for every race, so today they legitimately match. What must
	 * hold is that each colony carries the cell for the race it actually founded as, so authoring a
	 * non-human column is all it takes — no further wiring.
	 */
	@Test
	@Timeout(180)
	void eachSeatFoundsOnItsOwnRacesEconomy() {
		HostedSession hs = openTimeline(RANKED_SEED);
		Settlement alice = host.joinTimeline(hs.id(), "alice");
		Settlement bob = host.joinTimeline(hs.id(), "bob");

		for (Settlement seat : new Settlement[] { alice, bob }) {
			assertNotNull(seat.getEconomy(), seat.getName() + " founded with no economy");
			assertEquals(Era.MEDIEVAL.economy(seat.getFoundingRace()), seat.getEconomy(),
					seat.getName() + " must carry the cell for the race it founded as ("
							+ seat.getFoundingRace() + "), not the run config's");
		}
	}

	@Test
	@Timeout(180)
	void onePlayerGetsExactlyOneSeat() {
		HostedSession hs = openTimeline(1001L);
		Settlement first = host.joinTimeline(hs.id(), "alice");
		Settlement again = host.joinTimeline(hs.id(), "alice");

		assertSame(first, again, "joining twice returns the seat you already hold");
		assertEquals(1, hs.colonies().size(), "a double-click must not make you two players");
		assertSame(first, hs.colonyOf("alice"));
	}

	@Test
	@Timeout(180)
	void theRosterClosesWhenTheGunFires() {
		HostedSession hs = openTimeline(1002L);
		host.joinTimeline(hs.id(), "alice");
		host.joinTimeline(hs.id(), "bob");
		hs.startPaused(); // the gun

		// a latecomer cannot found into a world that is already running — the whole point of a Timeline
		assertThrows(IllegalStateException.class, () -> host.joinTimeline(hs.id(), "carol"));
	}

	@Test
	@Timeout(180)
	void everyColonyStartsOnTheSameDay() {
		HostedSession hs = openTimeline(1003L);
		Settlement alice = host.joinTimeline(hs.id(), "alice");
		Settlement bob = host.joinTimeline(hs.id(), "bob");

		assertEquals(alice.getStartDate(), bob.getStartDate(), "everyone starts at the gun");
		assertEquals(hs.date(), alice.getStartDate(), "and the session's clock starts with them");
	}

	/**
	 * The empty-Timeline trap: {@code allDead()} over an empty roster is vacuously true, so a
	 * Timeline nobody joined would break on the loop's first pass and report itself WON. Refuse the
	 * gun instead.
	 */
	@Test
	@Timeout(60)
	void aTimelineNobodyJoinedCannotBeStarted() {
		HostedSession hs = openTimeline(1004L);
		IllegalStateException e = assertThrows(IllegalStateException.class, hs::startPaused);
		assertTrue(e.getMessage().contains("no player has joined"), e.getMessage());
		assertFalse(hs.isFinished(), "an unjoined Timeline is not a Timeline someone won");
	}

	@Test
	@Timeout(180)
	void joiningIsOnlyForTimelines() {
		HostedSession demo = host.create(SessionSpec.caravanDemo(1005L, ANCHOR), null);
		assertThrows(IllegalArgumentException.class, () -> host.joinTimeline(demo.id(), "alice"));
		assertThrows(IllegalArgumentException.class, () -> host.joinTimeline("no-such-session", "alice"));

		HostedSession hs = openTimeline(1006L);
		assertThrows(IllegalArgumentException.class, () -> host.joinTimeline(hs.id(), null),
				"a seat belongs to a player, so an anonymous join is meaningless");
	}

	/**
	 * A Timeline ends when the CONTEST does — one colony standing — not when everyone is dead. Its
	 * end reason is a verdict, and the survivor is the winner.
	 */
	@Test
	@Timeout(600)
	void aTimelineEndsWhenOneColonyStandsAndNamesTheWinner() {
		HostedSession hs = openTimeline(RANKED_SEED);
		Settlement alice = host.joinTimeline(hs.id(), "alice");
		Settlement bob = host.joinTimeline(hs.id(), "bob");
		hs.startPaused();
		assertEquals(2, hs.survivors());

		// run it out: both colonies collapse eventually, so the Timeline ends the moment the FIRST
		// of them does — at that point one stands, and the contest is decided
		hs.step(20_000);
		long deadline = System.nanoTime() + 540_000L * 1_000_000L;
		while (!hs.isTerminal() && System.nanoTime() < deadline)
			Thread.onSpinWait();

		assertTrue(hs.isFinished(), "clock " + hs.clock() + " outcome " + hs.outcome()
				+ " at tick " + hs.tick() + " with " + hs.survivors() + " standing");
		assertEquals(ClockState.STOPPED, hs.clock());
		// one colony standing is a WON verdict; both dying on the same day is LOST (no colony survived)
		assertEquals(hs.survivors() >= 1 ? Outcome.WON : Outcome.LOST, hs.outcome());
		assertTrue(hs.survivors() <= 1, "the Timeline ran on past its decision");
		assertNotNull(hs.endReason());
		assertTrue(hs.endReason().contains("wins the Timeline")
				|| hs.endReason().contains("no colony survived"), hs.endReason());
		// the winner is the one still standing (both colonies are the demo's, so one outlives)
		if (hs.survivors() == 1) {
			String winner = alice.isDead() ? bob.getName() : alice.getName();
			assertTrue(hs.endReason().contains(winner), "expected " + winner + " in: " + hs.endReason());
		}
	}
}
