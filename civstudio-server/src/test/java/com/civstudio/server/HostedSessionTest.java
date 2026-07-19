package com.civstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.civstudio.server.command.GameCommand;
import com.civstudio.server.render.CaravanView;
import com.civstudio.server.render.SessionSnapshot;

/**
 * Drives the Phase-A spectator spine end to end without a transport: found the caravan-demo
 * session on a {@link SessionHost}, single-step it deterministically, and assert the snapshot
 * projection, the caravans' movement, and the command log's tick-exact application (see
 * {@code docs/client-server.md}). Single-stepping (not the wall-clock rate) keeps these tests
 * deterministic and fast.
 */
class HostedSessionTest {

	private static final int DHENIJANSAR = 4411;

	// block until the session has emitted a snapshot at or beyond `tick`, or fail on timeout
	private static SessionSnapshot awaitSnapshot(HostedSession hs, long tick, long timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		while (true) {
			SessionSnapshot snap = hs.currentSnapshot();
			if (snap != null && snap.tick() >= tick)
				return snap;
			if (System.nanoTime() > deadline)
				fail("timed out waiting for tick " + tick + " (at "
						+ (snap == null ? "none" : snap.tick()) + ")");
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("interrupted");
			}
		}
	}

	@Test
	@Timeout(90)
	void demoSessionProjectsColonyAndMarchingCaravans() {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(7654321L, DHENIJANSAR));
		try {
			hs.startPaused();
			SessionSnapshot start = awaitSnapshot(hs, 0, 30_000);
			assertEquals(1, start.colonies().size(), "the demo has one colony");
			assertTrue(start.colonies().get(0).population() > 0, "the colony has a workforce");
			assertEquals("PAUSED", start.state());
			// no bands are hand-seeded any more (SessionHost): the colony musters its OWN foraging
			// explorers emergently over the winter, so there are none at tick 0.

			// step into the lean season and confirm the colony has mustered foraging bands
			hs.step(20);
			SessionSnapshot mid = awaitSnapshot(hs, 20, 60_000);
			assertFalse(mid.caravans().isEmpty(),
					"the colony musters foraging explorers over winter");

			// Confirm the march actually moves bands on the map. Two things make a single fixed
			// window the wrong probe, and both bite at MarchConfig.baseMovePoints=3.0:
			//   1. A band's lat/lon is its PROVINCE's — only moveTo() (a crossing) shifts it, while
			//      within-province progress accrues invisibly as progressPoints. A crossing takes
			//      ~24 days, so over a 20-day window a marching band reports the identical position.
			//   2. The colony CYCLES its levies: a band returns home (and is pruned) and a fresh one
			//      musters in its place, so over a long window the bands seen at the end are not the
			//      ones seen at the start, and comparing by leader finds nothing to compare.
			// So sample repeatedly and remember every leader's last known position: a band that
			// changes province between ANY two samples proves the march moves the map, whether or not
			// it is the same band throughout.
			Map<String, double[]> seen = new HashMap<>();
			for (CaravanView c : mid.caravans())
				seen.put(c.leader(), new double[] { c.latitude(), c.longitude() });

			boolean anyMoved = false;
			int tick = 20;
			for (int i = 0; i < 6 && !anyMoved; i++) {
				hs.step(10);
				tick += 10;
				SessionSnapshot s = awaitSnapshot(hs, tick, 60_000);
				assertEquals(tick, s.tick());
				for (CaravanView c : s.caravans()) {
					double[] p = seen.get(c.leader());
					if (p != null && (Math.abs(p[0] - c.latitude()) > 1e-9
							|| Math.abs(p[1] - c.longitude()) > 1e-9))
						anyMoved = true;
					seen.put(c.leader(), new double[] { c.latitude(), c.longitude() });
				}
			}
			assertTrue(anyMoved, "at least one caravan should have marched");
		} finally {
			hs.stop();
		}
	}

	@Test
	@Timeout(90)
	void commandAppliesAtItsExactTick() {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(20260709L, DHENIJANSAR));
		try {
			AtomicLong appliedAtTick = new AtomicLong(-1);
			// a command scheduled for tick 3: it records the tick it actually runs on
			hs.submit(new GameCommand() {
				@Override
				public long tick() {
					return 3;
				}

				@Override
				public void apply(HostedSession session) {
					appliedAtTick.set(session.tick());
				}
			});
			hs.startPaused();
			hs.step(6);
			awaitSnapshot(hs, 6, 60_000);
			assertEquals(3, appliedAtTick.get(),
					"the command must apply at the top of its scheduled tick");
			assertEquals(1, hs.commandLog().history().size(), "it enters the replay log once");
			assertEquals(0, hs.commandLog().pendingCount(), "nothing left pending");
		} finally {
			hs.stop();
		}
	}

	/**
	 * The session keeps its own clock — tick counted from the founding date — rather than asking
	 * the colonies what day it is ({@code docs/spectator-lobby.md} §Phase 0). The invariant that
	 * makes that switch safe: while a colony lives, the two agree exactly, because both are
	 * {@code startDate + steps} and a live colony steps once per tick.
	 */
	@Test
	@Timeout(90)
	void theSessionClockAgreesWithItsLivingColonies() {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(7654321L, DHENIJANSAR));
		try {
			hs.startPaused();
			SessionSnapshot start = awaitSnapshot(hs, 0, 30_000);
			assertEquals(hs.date().toString(), start.date(), "the snapshot reports the session clock");
			assertEquals(start.colonies().get(0).date(), start.date(),
					"at tick 0 the session and its colony are on the founding date");

			for (int step : new int[] { 1, 7, 30 }) {
				long before = hs.tick();
				hs.step(step);
				SessionSnapshot s = awaitSnapshot(hs, before + step, 60_000);
				assertEquals(s.colonies().get(0).date(), s.date(),
						"the session's clock must not drift from a living colony's");
				assertEquals(hs.date().toString(), s.date());
			}
		} finally {
			hs.stop();
		}
	}

	// block until the run is over (stopped or game over), or fail on timeout
	private static void awaitTerminal(HostedSession hs, long timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		while (!hs.isTerminal()) {
			if (System.nanoTime() > deadline)
				fail("timed out waiting for the run to end (state " + hs.state()
						+ ", tick " + hs.tick() + ")");
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("interrupted");
			}
		}
	}

	/**
	 * A run that ends itself reaches {@link HostedSession.State#GAME_OVER} carrying why — the
	 * distinction {@code docs/game-over.md} exists for. The demo colony dissolves into a caravan
	 * once its workforce crosses the floor (~tick 4100 at this seed, on the studio-sourced content),
	 * so this drives the real collapse rather than simulating one.
	 */
	@Test
	@Timeout(600)
	void aRunThatEndsItselfIsGameOverAndSaysWhy() {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(7654321L, DHENIJANSAR));
		try {
			hs.startPaused();
			awaitSnapshot(hs, 0, 30_000);
			hs.step(6000); // more credit than the collapse needs (~tick 4100); the loop breaks when it dies
			awaitTerminal(hs, 540_000);

			assertEquals(HostedSession.State.GAME_OVER, hs.state(),
					"a colony collapsing ends the run itself — that is not a STOPPED session");
			assertNotNull(hs.endReason(), "game over says why");
			assertTrue(hs.endReason().contains("abandoned")
					&& hs.endReason().contains("survivors"),
					"the demo colony dissolves into a band rather than dying outright: "
							+ hs.endReason());
			assertTrue(hs.isTerminal());

			// the client learns both from the final snapshot — the whole point of the field
			SessionSnapshot last = hs.currentSnapshot();
			assertEquals("GAME_OVER", last.state());
			assertEquals(hs.endReason(), last.endReason());

			// a finished run stays finished: an admin stop (or shutdown sweep) must not relabel it
			hs.stop();
			assertEquals(HostedSession.State.GAME_OVER, hs.state(),
					"stop() must not downgrade a finished run to STOPPED");
		} finally {
			hs.stop();
		}
	}

	/** A session stopped from OUTSIDE is not game over: it never reached its own end. */
	@Test
	@Timeout(90)
	void stoppingFromOutsideIsNotGameOver() {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(555L, DHENIJANSAR));
		try {
			hs.startPaused();
			awaitSnapshot(hs, 0, 30_000);
			hs.stop();

			assertEquals(HostedSession.State.STOPPED, hs.state());
			assertNull(hs.endReason(), "no end was reached, so there is no reason to give");
			assertTrue(hs.isTerminal(), "stopped is still over for good");
			assertEquals("STOPPED", hs.currentSnapshot().state());
			assertNull(hs.currentSnapshot().endReason());
		} finally {
			hs.stop();
		}
	}

	@Test
	@Timeout(90)
	void pauseAndResumeGateTheClock() {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(555L, DHENIJANSAR));
		try {
			hs.setTickRateMillis(0); // run flat out
			hs.start();
			awaitSnapshot(hs, 5, 60_000);
			hs.pause();
			// after pausing, at most one in-flight tick may complete, then it holds
			long paused = hs.tick();
			Thread.sleep(80);
			assertTrue(hs.tick() - paused <= 1, "a paused session must not keep ticking");
			assertNotNull(hs.currentSnapshot());
			assertFalse(hs.currentSnapshot().caravans().isEmpty());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("interrupted");
		} finally {
			hs.stop();
		}
	}
}
