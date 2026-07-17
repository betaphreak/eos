package com.civstudio.server.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Rank;

/**
 * Pins the derivation of a {@link LogLine}'s {@code sev} and {@code curated} flags, and — the reason
 * this exists — that the two feeds fed by the same {@code SimLog} tap agree on them.
 */
class LogLineTest {

	// JUL level values
	private static final int INFO = 800, WARNING = 900, SEVERE = 1000;

	@Test
	void derivesSeverityFromTheJulLevel() {
		assertEquals("info", LogLine.of("1444-12-11", "x", INFO, null).sev());
		assertEquals("warn", LogLine.of("1444-12-11", "x", WARNING, null).sev());
		assertEquals("error", LogLine.of("1444-12-11", "x", SEVERE, null).sev());
		assertEquals("info", LogLine.of("1444-12-11", "x", 500, null).sev(), "below INFO is still info");
	}

	@Test
	void curatesNotableEventsAndWarnings() {
		assertTrue(LogLine.of("1444-12-11", "Dhenijansar was founded on 1444-12-11.", INFO, null).curated());
		assertTrue(LogLine.of("1445-02-10", "Dhenijansar starved down from METROPOLIS to TOWN", INFO, null).curated());
		assertTrue(LogLine.of("1445-02-10", "a laborer died of old age", INFO, null).curated());
		// a warning is curated whatever it says — an anomaly is always notable
		assertTrue(LogLine.of("1456-02-18", "Necessity skyrocketed to 51.51 (>10x init)", WARNING, null).curated());
		assertTrue(LogLine.of("1456-02-18", "nothing notable in this text at all", SEVERE, null).curated());
	}

	@Test
	void leavesRoutineChurnUncurated() {
		// the annual digest is the case the "digest" exclusion exists for: it MENTIONS deaths and
		// nobles, so without it the summary would match the allow-list and read as an event
		assertFalse(LogLine.of("1445-01-01",
				"annual digest: pop=402 children=0 nobles=3 firms=20 pool=483 poolKids=323 POI deaths=0 CPI=0.1",
				INFO, null).curated(), "the digest names deaths/nobles but is routine");
		assertFalse(LogLine.of("1445-01-01", "prices updated", INFO, null).curated());
	}

	@Test
	void toleratesANullMessage() {
		assertFalse(LogLine.of("1444-12-11", null, INFO, null).curated());
	}

	@Test
	void carriesTheEventsRankAndItsLevel() {
		LogLine poiDeath = LogLine.of("1445-06-24", "Tory Colandrea (notable laborer) died at age 61", INFO,
				Rank.HOUSEHOLD);
		assertEquals("HOUSEHOLD", poiDeath.rank());
		assertEquals(0, poiDeath.rankLevel());

		LogLine founding = LogLine.of("1444-12-11", "Dhenijansar was founded", INFO, Rank.VILLAGE);
		assertEquals("VILLAGE", founding.rank());
		// the LEVEL is what the client compares, so it must not have to carry a copy of the ladder
		assertEquals(3, founding.rankLevel());
	}

	@Test
	void anUnrankedLineIsMarkedAsSuch() {
		// a plain log.info/@Log call site carries no rank; -1 is below every real rank's level, so a
		// viewer's floor can never accidentally filter one out on a comparison
		LogLine plain = LogLine.of("1444-12-11", "prices updated", INFO, null);
		assertEquals(null, plain.rank());
		assertEquals(-1, plain.rankLevel());
		assertTrue(plain.rankLevel() < Rank.HOUSEHOLD.level(), "unranked sorts below the lowest rank");
	}

	@Test
	void theVisibilityRuleIsRankRelativeToTheViewer() {
		// THE rule the board filters on: a player plays AT a rank and wants everything above their
		// level plus at most one rung below — far enough to see how their vassals perform, no further.
		//   visible   <=> event.rankLevel >= viewer.level - 1
		//   full card <=> event.rankLevel >= viewer.level
		LogLine household = LogLine.of("d", "a family's news", INFO, Rank.HOUSEHOLD); // 0
		LogLine holding = LogLine.of("d", "a noble house's news", INFO, Rank.HOLDING); // 2
		LogLine village = LogLine.of("d", "the colony's news", INFO, Rank.VILLAGE);    // 3

		// a captain (CARAVAN, 1) knows every family in the band
		assertTrue(household.rankLevel() >= Rank.CARAVAN.level() - 1, "a captain sees a household");
		// a ruler (VILLAGE, 3) hears about their noble vassals (HOLDING, 2) but not the peasantry
		assertTrue(holding.rankLevel() >= Rank.VILLAGE.level() - 1, "a ruler sees their holdings");
		assertFalse(household.rankLevel() >= Rank.VILLAGE.level() - 1, "…but not one family's affairs");
		// a mayor (CITY, 4) sees the villages under them, and nothing smaller
		assertTrue(village.rankLevel() >= Rank.CITY.level() - 1, "a mayor sees a village");
		assertFalse(holding.rankLevel() >= Rank.CITY.level() - 1, "…but not a single holding");

		// prominence is the same axis: your level and above is a full card, the rung below is a dim
		// vassal line. So the SAME event reads differently by who is watching — which is the point.
		assertTrue(village.rankLevel() >= Rank.VILLAGE.level(), "a ruler's own colony news is a full card");
		assertFalse(holding.rankLevel() >= Rank.VILLAGE.level(), "a vassal's news is a dim one-liner");
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
			buffer.add(s.date(), s.text(), s.level(), Rank.VILLAGE);
			tail.add(s.date(), s.text(), s.level(), Rank.VILLAGE);
		}

		List<LogLine> streamed = buffer.drain();
		List<LogLine> recovered = tail.query(null, null, null, null, 0);
		assertEquals(streamed, recovered,
				"a line served from the retained tail must be identical to the one the stream delivered");
	}
}
