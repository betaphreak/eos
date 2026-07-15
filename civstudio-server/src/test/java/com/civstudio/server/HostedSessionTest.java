package com.civstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

			// remember where each band sits mid-run
			Map<String, double[]> from = new HashMap<>();
			for (CaravanView c : mid.caravans())
				from.put(c.leader(), new double[] { c.latitude(), c.longitude() });

			// advance more days deterministically and confirm at least one band marched
			hs.step(20);
			SessionSnapshot later = awaitSnapshot(hs, 40, 60_000);
			assertEquals(40, later.tick());

			boolean anyMoved = false;
			for (CaravanView c : later.caravans()) {
				double[] p = from.get(c.leader());
				if (p != null && (Math.abs(p[0] - c.latitude()) > 1e-9
						|| Math.abs(p[1] - c.longitude()) > 1e-9))
					anyMoved = true;
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
