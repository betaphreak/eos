package com.civstudio.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Phaser;

import com.civstudio.mortality.Demography;
import com.civstudio.name.NameRegistry;
import com.civstudio.io.SimLog;
import com.civstudio.settlement.Settlement;

/**
 * Runs several colonies of one {@code GameSession} <b>concurrently</b> — one
 * thread per settlement — in <b>lockstep</b>: every colony advances exactly one
 * in-game day, then they all rendezvous at a day-barrier before any starts the
 * next day. So at every barrier the colonies share a single in-game date, which
 * keeps the run deterministic per colony (no colony can read another mid-day)
 * and gives a clean synchronization point for future inter-colony interaction
 * (caravan trade between settlements).
 * <p>
 * Each colony is otherwise self-contained — its own economic, naming, mortality
 * and skill generators, its own {@link NameRegistry} surname slice and
 * {@link Demography} (see {@code GameSession}) — so the threads
 * touch no shared mutable economic state during a day. The only cross-colony
 * shared state (the session's name pool dispensing, caravan registration, the
 * log handler) is synchronized or per-thread.
 * <p>
 * The barrier is a {@link Phaser} rather than a fixed-party {@code
 * CyclicBarrier} so colonies with different lifespans don't deadlock it: a
 * colony that dies (or reaches its horizon) early simply
 * {@linkplain Phaser#arriveAndDeregister() deregisters}, and the survivors carry
 * on among themselves.
 */
public final class SessionRunner {

	private SessionRunner() {
	}

	/**
	 * Run every harness's colony concurrently to completion (each to its own
	 * configured horizon or until it dies), coordinated by a lockstep day-barrier,
	 * then clean up its printers. Returns once all colonies have finished. If any
	 * colony's thread fails, the first failure is rethrown after all threads have
	 * settled.
	 *
	 * @param harnesses
	 *            the colonies to run together (built but not yet run)
	 */
	public static void runConcurrently(List<SimulationHarness> harnesses) {
		// one party for this (coordinating) thread, so the phaser stays alive while
		// we register and start the colony threads
		Phaser barrier = new Phaser(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		List<Thread> threads = new ArrayList<>(harnesses.size());

		for (SimulationHarness h : harnesses) {
			barrier.register();
			Thread t = new Thread(() -> runColony(h, barrier, failures),
					"colony-" + h.getColony().getName());
			threads.add(t);
			t.start();
		}
		// drop the coordinating party: from here the colony threads rendezvous only
		// among themselves
		barrier.arriveAndDeregister();

		for (Thread t : threads) {
			try {
				t.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("interrupted waiting for colony threads", e);
			}
		}

		if (!failures.isEmpty()) {
			Throwable first = failures.get(0);
			throw new RuntimeException("a colony thread failed: " + first.getMessage(),
					first);
		}
	}

	// drive one colony's day loop in lockstep with its peers
	private static void runColony(SimulationHarness h, Phaser barrier,
			List<Throwable> failures) {
		Settlement colony = h.getColony();
		// records emitted on this thread carry this colony's in-game date
		SimLog.bind(colony);
		int steps = h.getCfg().numStep();
		try {
			colony.start();
			// advance one day, then wait for every still-running colony to reach the
			// same day before starting the next — stopping early once this colony dies
			for (int day = 0; day < steps && !colony.isDead(); day++) {
				colony.printAnnualProgress();
				colony.newDay();
				barrier.arriveAndAwaitAdvance();
			}
		} catch (Throwable t) {
			failures.add(t);
		} finally {
			// leave the barrier before teardown so peers still running aren't blocked
			// waiting on our finalization
			barrier.arriveAndDeregister();
		}
		// finalize outside the barrier: dissolve into a caravan if the workforce
		// drained, and flush this colony's CSVs
		try {
			colony.finishRun();
			colony.cleanUpPrinters();
		} catch (Throwable t) {
			failures.add(t);
		}
	}
}
