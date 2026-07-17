package com.civstudio.server.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit coverage for the retained event tail's filtering, ordering and bounded eviction. */
class SessionEventLogTest {

	private static final int INFO = 700, WARN = 900, ERROR = 1000;

	@Test
	void returnsAllInChronologicalOrder() {
		SessionEventLog log = new SessionEventLog();
		log.add("1444-11-11", "founded", INFO, null);
		log.add("1444-11-12", "a death", WARN, null);
		log.add("1444-11-13", "starvation", ERROR, null);
		List<LogLine> all = log.query(null, null, null, null, 0);
		assertEquals(List.of("founded", "a death", "starvation"), all.stream().map(LogLine::text).toList());
	}

	@Test
	void minimumSeverityFilters() {
		SessionEventLog log = new SessionEventLog();
		log.add("1444-11-11", "routine", INFO, null);
		log.add("1444-11-12", "a death", WARN, null);
		log.add("1444-11-13", "starvation", ERROR, null);
		assertEquals(List.of("a death", "starvation"),
				log.query("warn", null, null, null, 0).stream().map(LogLine::text).toList());
		assertEquals(List.of("starvation"),
				log.query("error", null, null, null, 0).stream().map(LogLine::text).toList());
	}

	@Test
	void inclusiveDateRangeAndGrep() {
		SessionEventLog log = new SessionEventLog();
		log.add("1444-11-10", "Alpha founded", INFO, null);
		log.add("1445-06-01", "Beta founded", INFO, null);
		log.add("1446-01-01", "Gamma founded", INFO, null);
		// dates sort lexically, so an ISO string range is a correct date range (bounds inclusive)
		List<LogLine> ranged = log.query(null, "1445-01-01", "1445-12-31", null, 0);
		assertEquals(List.of("Beta founded"), ranged.stream().map(LogLine::text).toList());
		// grep is case-insensitive substring
		assertEquals(1, log.query(null, null, null, "gamma", 0).size());
		assertTrue(log.query(null, null, null, "founded", 0).size() == 3);
	}

	@Test
	void limitKeepsTheMostRecent() {
		SessionEventLog log = new SessionEventLog();
		for (int i = 0; i < 10; i++)
			log.add("1444-11-" + (10 + i), "e" + i, INFO, null);
		List<LogLine> last3 = log.query(null, null, null, null, 3);
		assertEquals(List.of("e7", "e8", "e9"), last3.stream().map(LogLine::text).toList());
	}

	@Test
	void evictsOldestBeyondCap() {
		SessionEventLog log = new SessionEventLog();
		for (int i = 0; i < 5000; i++)
			log.add("1444-11-11", "line-" + i, INFO, null);
		List<LogLine> all = log.query(null, null, null, null, 100_000);
		assertEquals(4096, all.size(), "the ring is bounded to its cap");
		assertEquals("line-904", all.get(0).text(), "the oldest surviving line is 5000-4096");
		assertFalse(all.stream().anyMatch(l -> l.text().equals("line-0")), "line-0 aged out");
	}
}
