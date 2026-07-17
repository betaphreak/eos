package com.civstudio.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * The two verbosity floors ({@link SimLog}) and the fact that only the sim's own records travel.
 *
 * <p>These are the two properties the notification board rests on: the live tap must see the FINE
 * dynasty/demographic narrative (every promotion, ennoblement, notable arrival and POI death is
 * logged there) while the file keeps its INFO default, and no third-party logger may reach either.
 */
class SimLogFloorsTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	// a distinct, test-only seed, so this never shares an output dir with a scenario
	private static final long SEED = 20260716L;

	// a colony is only needed as a routing key (colony -> session -> log path); it is never run
	private static Settlement colony() {
		return new GameSession(SEED).newSettlement("FloorTest", START, 35, 26, 5, 2, 51.5074, -0.1278);
	}

	@Test
	void theTapSeesTheFineNarrativeThatTheFileFloorHides() throws Exception {
		Settlement colony = colony();
		SimLog.init(colony);
		SimLog.bind(colony);
		List<SimLog.Entry> tapped = new ArrayList<>();
		try (AutoCloseable tap = SimLog.tap(colony, tapped::add)) {
			Logger log = Logger.getLogger("com.civstudio.io.SimLogFloorsTest");
			log.fine("Percy Ziemba was ennobled — risen from commoner to noble");
			log.info("FloorTest was founded");
		}
		// THE property: the default file floor is INFO, so a FINE line never reaches the file — but
		// it must still reach the board, or the board can only ever show lifecycle + the digest.
		assertEquals(2, tapped.size(), "the tap takes FINE and INFO: " + tapped);
		assertTrue(tapped.get(0).message().contains("ennobled"));
		assertEquals(Level.FINE.intValue(), tapped.get(0).level(), "the level rides along, so the client can rank it");
		assertTrue(tapped.get(0).date().matches("\\d{4}-\\d{2}-\\d{2}"), "a bound colony dates the line: " + tapped.get(0).date());
	}

	@Test
	void theTapIgnoresChurnBelowItsOwnFloor() throws Exception {
		Settlement colony = colony();
		SimLog.init(colony);
		SimLog.bind(colony);
		List<SimLog.Entry> tapped = new ArrayList<>();
		try (AutoCloseable tap = SimLog.tap(colony, tapped::add)) {
			Logger.getLogger("com.civstudio.io.SimLogFloorsTest").finer("a firm chartered — economic churn");
		}
		// the tap floor is FINE, so FINER churn (firm charter/dissolve, peasant starvation) stays off
		// the board. Raising -Deos.log.tap.level would let it through; the default must not.
		assertTrue(tapped.isEmpty(), "FINER is below the tap floor: " + tapped);
	}

	@Test
	void noThirdPartyRecordEverReachesTheTap() throws Exception {
		Settlement colony = colony();
		SimLog.init(colony);
		SimLog.bind(colony);
		List<SimLog.Entry> tapped = new ArrayList<>();
		try (AutoCloseable tap = SimLog.tap(colony, tapped::add)) {
			// THE regression. The dispatch used to hang off the ROOT logger with the root's level
			// raised, so at FINE every logger in the JVM was in scope — jdk.event.security dumps all
			// 144 trusted root CAs the moment the default SSLContext initialises, and they landed in
			// the session's log file. Had the tap taken FINE from the root, they would have become
			// 144 notification cards.
			Logger.getLogger("jdk.event.security").fine(
					"X509Certificate: Alg:SHA256withRSA, Serial:5c:33, Subject:C=DE, O=Atos, CN=Atos TrustedRoot 2011");
			Logger.getLogger("some.other.library").info("a third-party line");
			Logger.getLogger("com.civstudio.io.SimLogFloorsTest").fine("a real sim line");
		}
		assertEquals(1, tapped.size(), "only the com.civstudio record travels: " + tapped);
		assertTrue(tapped.get(0).message().contains("a real sim line"));
		assertFalse(tapped.stream().anyMatch(e -> e.message().contains("X509Certificate")),
				"the JDK's certificate dump must never reach the board");
	}

	@Test
	void theRootLoggerIsLeftAtItsDefault() {
		SimLog.init(colony());
		// the root's level is what governs whether third-party loggers PRODUCE records at all;
		// leaving it alone is what keeps the 144-certificate dump from ever being formatted
		assertFalse(Level.FINE.equals(Logger.getLogger("").getLevel()),
				"raising the root level is what invited jdk.event.security into the log");
		assertEquals(Level.FINE, Logger.getLogger("com.civstudio").getLevel(),
				"the sim's own logger carries the lowered floor instead");
	}
}
