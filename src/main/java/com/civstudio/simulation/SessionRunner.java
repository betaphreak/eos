package com.civstudio.simulation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Phaser;

import com.civstudio.agent.Caravan;
import com.civstudio.io.CsvMerger;
import com.civstudio.io.printer.CaravanMarchPrinter;
import com.civstudio.mortality.Demography;
import com.civstudio.name.NameRegistry;
import com.civstudio.io.SimLog;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

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
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		// the colonies all belong to one session — the home of the realm's wandering
		// bands, which advance once per lockstep day (see the barrier below)
		GameSession session = harnesses.isEmpty() ? null
				: harnesses.get(0).getColony().getSession();
		// the session's colonies, so a band tick (which belongs to no single colony)
		// can date its log from a representative one (see tickBands / SimLog.asRealm)
		List<Settlement> colonies = new ArrayList<>(harnesses.size());
		for (SimulationHarness h : harnesses)
			colonies.add(h.getColony());
		// if the session already carries wandering bands, open the caravan march journal
		// (scoped to output/<seed>/, like the colonies' CSVs) and enable their nightly
		// camps — the journal is written from the single-threaded day-barrier below
		CaravanMarchPrinter journal = null;
		if (session != null && !session.getCaravans().isEmpty()) {
			journal = new CaravanMarchPrinter("output/" + session.getSeed());
			for (Caravan band : session.getCaravans())
				band.setCampingEnabled(true);
		}
		final CaravanMarchPrinter marchJournal = journal;
		// one party for this (coordinating) thread, so the phaser stays alive while
		// we register and start the colony threads. The day-barrier also drives the
		// session's bands: onAdvance runs once, single-threaded, each time every
		// still-running colony has finished the day and before any starts the next —
		// the natural deterministic point to move bands on the session band RNG.
		Phaser barrier = new Phaser(1) {
			@Override
			protected boolean onAdvance(int phase, int registeredParties) {
				tickBands(session, colonies, failures, marchJournal);
				return registeredParties == 0; // terminate exactly as the default would
			}
		};
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

		if (marchJournal != null)
			marchJournal.close();

		if (!failures.isEmpty()) {
			Throwable first = failures.get(0);
			throw new RuntimeException("a colony thread failed: " + first.getMessage(),
					first);
		}

		// every colony has finished and flushed its CSVs: fold the per-settlement
		// files of each table into one tidy output/<seed>/<Table>.csv (with a leading
		// Settlement column) and demote the raw files to by-settlement/, so a
		// many-colony run is read as one file per table rather than N×tables loose
		// files. Single-threaded here, so it stays deterministic at any colony count.
		if (session != null && !colonies.isEmpty()) {
			List<String> names = new ArrayList<>(colonies.size());
			for (Settlement c : colonies)
				names.add(c.getName());
			CsvMerger.mergeSessionOutput(session.getSeed(), names);
		}
	}

	// advance the session's wandering bands by one day — run from the day-barrier's
	// onAdvance, so it executes once on a single thread with every colony paused. Draws
	// nothing when there are no bands, so band-free runs are byte-identical; a band that
	// misbehaves is recorded as a failure rather than left to corrupt the barrier.
	private static void tickBands(GameSession session, List<Settlement> colonies,
			List<Throwable> failures, CaravanMarchPrinter journal) {
		if (session == null)
			return;
		List<Caravan> bands = session.getCaravans();
		if (bands.isEmpty())
			return;
		// date the band records from the furthest-advanced colony (in lockstep they
		// share the day, but one that died early has a frozen date), and label them
		// (realm) rather than that colony's name — the bands belong to no one colony
		Settlement furthest = colonies.isEmpty() ? null : colonies.get(0);
		for (Settlement c : colonies)
			if (furthest == null || c.getTimeStep() > furthest.getTimeStep())
				furthest = c;
		final Settlement dateSource = furthest;
		SimLog.asRealm(dateSource, () -> {
			try {
				Rng rng = session.getBandRng();
				// the bands share the lockstep day; date them from the furthest-advanced
				// colony so the daylight-bounded march reads the right in-game date
				LocalDate date = dateSource == null ? null : dateSource.getDate();
				for (Caravan band : bands) {
					band.tick(date, rng);
					if (journal != null && band.getLastReport() != null)
						journal.record(band.getLastReport());
				}
			} catch (Throwable t) {
				failures.add(t);
			}
		});
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
