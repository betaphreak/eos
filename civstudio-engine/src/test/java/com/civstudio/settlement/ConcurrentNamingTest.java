package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

/**
 * Verifies that name picking is <b>thread-safe</b> and <b>deterministic per
 * colony</b> now that each colony owns a disjoint surname slice on its own
 * naming generator: several colonies of one session can draw names from
 * different threads at once without colliding, and a colony's surname sequence
 * does not depend on how the threads interleave.
 */
class ConcurrentNamingTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);
	private static final int COLONIES = 6;
	private static final int DRAWS = 300;

	private Settlement newColony(GameSession s, int i) {
		return s.newSettlement("Colony " + i, START, 35, 26, 5, 2, 51.5, -0.1);
	}

	@Test
	void concurrentDrawsAcrossColoniesNeverCollide() throws InterruptedException {
		GameSession s = new GameSession(31415);
		List<Settlement> colonies = new ArrayList<>();
		for (int i = 0; i < COLONIES; i++)
			colonies.add(newColony(s, i));

		// every surname any colony draws, gathered across all threads; a duplicate
		// would mean two colonies' slices overlapped (or a draw raced)
		Set<String> allSurnames = ConcurrentHashMap.newKeySet();
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		// release the threads together so the draws genuinely overlap
		CyclicBarrier gate = new CyclicBarrier(COLONIES);

		List<Thread> threads = new ArrayList<>();
		for (Settlement colony : colonies) {
			Thread t = new Thread(() -> {
				try {
					gate.await();
					for (int d = 0; d < DRAWS; d++)
						allSurnames.add(colony.getNames().nextHead().surname());
				} catch (Throwable e) {
					failures.add(e);
				}
			});
			threads.add(t);
			t.start();
		}
		for (Thread t : threads)
			t.join();

		assertTrue(failures.isEmpty(), () -> "a naming thread failed: " + failures);
		// no collisions: every one of the COLONIES*DRAWS surnames is distinct
		assertEquals(COLONIES * DRAWS, allSurnames.size(),
				"surnames collided across concurrently-naming colonies");
	}

	@Test
	void perColonySurnamesAreDeterministicRegardlessOfThreadTiming() throws Exception {
		// the same seed must give each colony the same surname sequence whether its
		// draws run sequentially or concurrently with the other colonies' — each
		// colony has its own slice and generator, so timing cannot change them
		List<List<String>> sequential = drawSequential(new GameSession(2024));
		List<List<String>> concurrent = drawConcurrent(new GameSession(2024));
		assertEquals(sequential, concurrent,
				"a colony's surname sequence must not depend on thread interleaving");
	}

	private List<List<String>> drawSequential(GameSession s) {
		List<List<String>> out = new ArrayList<>();
		for (int i = 0; i < COLONIES; i++) {
			Settlement colony = newColony(s, i);
			List<String> seq = new ArrayList<>();
			for (int d = 0; d < DRAWS; d++)
				seq.add(colony.getNames().nextHead().surname());
			out.add(seq);
		}
		return out;
	}

	private List<List<String>> drawConcurrent(GameSession s) throws Exception {
		List<Settlement> colonies = new ArrayList<>();
		for (int i = 0; i < COLONIES; i++)
			colonies.add(newColony(s, i));

		List<List<String>> out = new ArrayList<>(Collections.nCopies(COLONIES, null));
		CyclicBarrier gate = new CyclicBarrier(COLONIES);
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < COLONIES; i++) {
			final int idx = i;
			Thread t = new Thread(() -> {
				try {
					gate.await();
					List<String> seq = new ArrayList<>();
					for (int d = 0; d < DRAWS; d++)
						seq.add(colonies.get(idx).getNames().nextHead().surname());
					out.set(idx, seq);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			threads.add(t);
			t.start();
		}
		for (Thread t : threads)
			t.join();
		return out;
	}
}
