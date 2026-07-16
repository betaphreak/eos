package com.civstudio.server.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the derivation of a {@link LogLine}'s {@code sev} and {@code curated} flags, and — the reason
 * this exists — that the two feeds fed by the same {@code SimLog} tap agree on them.
 */
class LogLineTest {

	// JUL level values
	private static final int INFO = 800, WARNING = 900, SEVERE = 1000;

	@Test
	void derivesSeverityFromTheJulLevel() {
		assertEquals("info", LogLine.of("1444-12-11", "x", INFO).sev());
		assertEquals("warn", LogLine.of("1444-12-11", "x", WARNING).sev());
		assertEquals("error", LogLine.of("1444-12-11", "x", SEVERE).sev());
		assertEquals("info", LogLine.of("1444-12-11", "x", 500).sev(), "below INFO is still info");
	}

	@Test
	void curatesNotableEventsAndWarnings() {
		assertTrue(LogLine.of("1444-12-11", "Dhenijansar was founded on 1444-12-11.", INFO).curated());
		assertTrue(LogLine.of("1445-02-10", "Dhenijansar starved down from METROPOLIS to TOWN", INFO).curated());
		assertTrue(LogLine.of("1445-02-10", "a laborer died of old age", INFO).curated());
		// a warning is curated whatever it says — an anomaly is always notable
		assertTrue(LogLine.of("1456-02-18", "Necessity skyrocketed to 51.51 (>10x init)", WARNING).curated());
		assertTrue(LogLine.of("1456-02-18", "nothing notable in this text at all", SEVERE).curated());
	}

	@Test
	void leavesRoutineChurnUncurated() {
		// the annual digest is the case the "digest" exclusion exists for: it MENTIONS deaths and
		// nobles, so without it the summary would match the allow-list and read as an event
		assertFalse(LogLine.of("1445-01-01",
				"annual digest: pop=402 children=0 nobles=3 firms=20 pool=483 poolKids=323 POI deaths=0 CPI=0.1",
				INFO).curated(), "the digest names deaths/nobles but is routine");
		assertFalse(LogLine.of("1445-01-01", "prices updated", INFO).curated());
	}

	@Test
	void toleratesANullMessage() {
		assertFalse(LogLine.of("1444-12-11", null, INFO).curated());
	}

	@Test
	void bothFeedsDeriveTheSameLine() {
		// THE regression this guards. SessionLogBuffer (the per-frame delta the stream carries) and
		// SessionEventLog (the retained tail /events serves) are fed by the SAME SimLog tap, and the
		// notification board reads BOTH — it seeds from the tail on connect, then is fed by the stream.
		// The tail used to flag only warnings as curated while the buffer also matched the allow-list,
		// so a founding was a full card live and a dim routine one-liner after a reload. Deriving both
		// through LogLine.of is what makes them agree; this asserts they still do.
		record Line(String date, String text, int level) {}
		List<Line> samples = List.of(
				new Line("1444-12-11", "Dhenijansar was founded on 1444-12-11.", INFO),
				new Line("1445-01-01", "annual digest: pop=402 nobles=3 deaths=0", INFO),
				new Line("1445-02-10", "Dhenijansar starved down from METROPOLIS to TOWN", INFO),
				new Line("1456-02-18", "Necessity skyrocketed to 51.51 (>10x init)", WARNING),
				new Line("1456-09-12", "Dhenijansar is dissolving into a Caravan", SEVERE));

		SessionLogBuffer buffer = new SessionLogBuffer();
		SessionEventLog tail = new SessionEventLog();
		for (Line s : samples) {
			buffer.add(s.date(), s.text(), s.level());
			tail.add(s.date(), s.text(), s.level());
		}

		List<LogLine> streamed = buffer.drain();
		List<LogLine> recovered = tail.query(null, null, null, null, 0);
		assertEquals(streamed, recovered,
				"a line served from the retained tail must be identical to the one the stream delivered");
	}
}
