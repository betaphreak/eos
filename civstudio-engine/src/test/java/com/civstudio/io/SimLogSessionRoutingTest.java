package com.civstudio.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Guards Phase 0 of the client/server work (see {@code docs/client-server.md}): two
 * {@link GameSession}s in <b>one JVM</b> must each write their own clean,
 * non-interleaved event log. Before the de-globalization, {@link SimLog} installed a
 * single process-wide file handler bound to the first session's seed, so a second
 * session's records were misrouted into the first session's file. This exercises the
 * session-demultiplexing handler: a record routes to the log of the session bound on
 * the emitting thread, resolved from the colony's {@code seed}.
 */
class SimLogSessionRoutingTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	// distinct, test-only seeds (unlikely to collide with a scenario's output dir)
	private static final long SEED_A = 918273641L;
	private static final long SEED_B = 192837465L;

	private Settlement colony(GameSession s, String name) {
		return s.newSettlement(name, START, 35, 26, 5, 2, 51.5074, -0.1278);
	}

	@Test
	void twoSessionsInOneJvmWriteSeparateLogs() throws IOException {
		// This guard is exactly about PER-SEED routing, which the test tier otherwise collapses
		// to a single output/sim.log (-Dcivstudio.printers.skip). Each session opts into its own
		// seed-scoped log (per session, not a global toggle — so parallel classes can't race),
		// and the test deletes its two folders afterwards so the suite still leaves output/ clean.
		try {
			runRoutingAssertions();
		} finally {
			deleteTree(Path.of("output", Long.toString(SEED_A)));
			deleteTree(Path.of("output", Long.toString(SEED_B)));
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root))
			return;
		try (var walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort cleanup; a lingering handle is harmless (target of a later mvn clean)
				}
			});
		}
	}

	private void runRoutingAssertions() throws IOException {
		GameSession sessionA = new GameSession(SEED_A);
		GameSession sessionB = new GameSession(SEED_B);
		sessionA.setSeedScopedLog(true);
		sessionB.setSeedScopedLog(true);
		Settlement alpha = colony(sessionA, "Alpha");
		Settlement beta = colony(sessionB, "Beta");

		// a token unique to each session's records, so a file's contents can be
		// checked for its own records and against the other session's
		String tokenA = "ROUTING-TOKEN-ALPHA-" + SEED_A;
		String tokenB = "ROUTING-TOKEN-BETA-" + SEED_B;

		Logger log = Logger.getLogger(SimLogSessionRoutingTest.class.getName());

		// bind + init session A on this thread and emit a record; then the same for
		// session B. The handler must route each by the thread's bound colony, not by
		// whichever session installed the handlers first.
		SimLog.init(alpha);
		log.warning(tokenA);

		SimLog.init(beta); // handlers already installed — opens Beta's own file
		log.warning(tokenB);

		// re-bind A and log again, to prove routing follows the current binding rather
		// than latching to whichever session was last init'd
		SimLog.bind(alpha);
		log.warning(tokenA + "-SECOND");

		SimLog.closeSession(alpha);
		SimLog.closeSession(beta);
		SimLog.closeSession(alpha); // idempotent — a second close is a no-op

		Path logA = Path.of("output", Long.toString(SEED_A), SEED_A + ".log");
		Path logB = Path.of("output", Long.toString(SEED_B), SEED_B + ".log");
		assertTrue(Files.exists(logA), "session A's seed-scoped log should exist");
		assertTrue(Files.exists(logB), "session B's seed-scoped log should exist");

		String contentA = Files.readString(logA);
		String contentB = Files.readString(logB);

		// each file carries its own session's records...
		assertTrue(contentA.contains(tokenA), "A's log should hold A's record");
		assertTrue(contentA.contains(tokenA + "-SECOND"),
				"A's log should hold the record emitted after re-binding to A");
		assertTrue(contentB.contains(tokenB), "B's log should hold B's record");

		// ...and none of the other session's — no cross-talk, no interleaving
		assertFalse(contentA.contains(tokenB), "A's log must not hold B's record");
		assertFalse(contentB.contains(tokenA), "B's log must not hold A's record");

		// the records are prefixed with the emitting colony's name, per session
		assertTrue(contentA.contains("Alpha"), "A's log is prefixed with its colony");
		assertTrue(contentB.contains("Beta"), "B's log is prefixed with its colony");
	}
}
