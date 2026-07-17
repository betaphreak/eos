package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.io.SimLog;

/**
 * The mass-death rule ({@code Settlement.noteDeathToll}): one aggregate event for a day whose
 * household death toll spikes above the colony's own recent norm.
 *
 * <p>Ordinary deaths are never logged individually — that volume is exactly what the annual digest
 * exists to avoid — so without this a starvation wave is invisible until the yearly summary, or until
 * the colony is already gone. The rule is measured against a trailing baseline rather than a fixed
 * count because colonies differ by orders of magnitude: a metropolis buries people daily, a hamlet
 * almost never, and one threshold cannot serve both.
 */
class SettlementDeathTollTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);
	private static final long SEED = 20260717L;

	private static final int BASELINE_DAYS = 30;   // mirrors Settlement.DEATH_BASELINE_DAYS

	private Settlement colony() {
		return new GameSession(SEED).newSettlement("TollTest", START, 35, 26, 5, 2, 51.5074, -0.1278);
	}

	/** Drive `days` days at `perDay` deaths, collecting whatever reaches the live tap. */
	private List<String> run(Settlement colony, int warmPerDay, int... spikes) throws Exception {
		SimLog.init(colony);
		SimLog.bind(colony);
		List<String> lines = new ArrayList<>();
		try (AutoCloseable tap = SimLog.tap(colony, e -> lines.add(e.message()))) {
			for (int d = 0; d < BASELINE_DAYS; d++)
				colony.noteDeathToll(warmPerDay);
			for (int s : spikes)
				colony.noteDeathToll(s);
		}
		return lines.stream().filter(l -> l.contains("households died")).toList();
	}

	@Test
	void aDaySpikingAboveTheRecentNormRaisesOneEvent() throws Exception {
		// a quiet colony (1 death/day for a month) that suddenly buries 20
		List<String> events = run(colony(), 1, 20);
		assertEquals(1, events.size(), "exactly one aggregate event, never one per death: " + events);
		assertTrue(events.get(0).contains("20 households died"), events.get(0));
		assertTrue(events.get(0).contains("20x"), "it says how far above the norm it is: " + events.get(0));
		assertTrue(events.get(0).contains("died"),
				"'died' is what makes it a full card rather than routine churn on the board");
	}

	@Test
	void anOrdinaryDayIsSilent() throws Exception {
		// the norm itself is never news — 1/day forever, then another 1
		assertEquals(List.of(), run(colony(), 1, 1));
		// nor is a mild wobble: 2 is under the 3x factor AND under the 3-death minimum
		assertEquals(List.of(), run(colony(), 1, 2));
	}

	@Test
	void aTinyTollIsNeverASpikeEvenAgainstAQuietColony() throws Exception {
		// THE floor. A colony that buries nobody has a norm of 0, so ANY death is an infinite
		// multiple — without the minimum, a single death would be "a mass death" every time.
		assertEquals(List.of(), run(colony(), 0, 1), "one death is not a wave");
		assertEquals(List.of(), run(colony(), 0, 2), "two is not either");
		assertEquals(1, run(colony(), 0, 3).size(), "three, in a colony that buried nobody all month, is");
	}

	@Test
	void aColonyThatBuriedNobodyReadsWithoutADivideByZero() throws Exception {
		List<String> events = run(colony(), 0, 9);
		assertEquals(1, events.size());
		// the norm is 0.00 here, so the message must not try to print a multiple of it
		assertTrue(events.get(0).contains("buried no one"), events.get(0));
		assertTrue(events.get(0).contains("9 households died"), events.get(0));
	}

	@Test
	void nothingFiresUntilTheBaselineWindowIsFull() throws Exception {
		Settlement colony = colony();
		SimLog.init(colony);
		SimLog.bind(colony);
		List<String> lines = new ArrayList<>();
		try (AutoCloseable tap = SimLog.tap(colony, e -> lines.add(e.message()))) {
			// a founding colony has no past to be judged against: with an empty window every death is
			// an infinite multiple of nothing, so a fresh colony would cry wolf on day one
			for (int d = 0; d < BASELINE_DAYS - 1; d++)
				colony.noteDeathToll(50);
		}
		assertTrue(lines.stream().noneMatch(l -> l.contains("households died")),
				"no baseline yet, so no verdict: " + lines);
	}

	@Test
	void aSustainedWaveStopsBeingNewsAsItBecomesTheNorm() throws Exception {
		Settlement colony = colony();
		SimLog.init(colony);
		SimLog.bind(colony);
		List<String> lines = new ArrayList<>();
		try (AutoCloseable tap = SimLog.tap(colony, e -> lines.add(e.message()))) {
			for (int d = 0; d < BASELINE_DAYS; d++)
				colony.noteDeathToll(0);
			// the wave breaks — then simply continues at the same rate for a year
			for (int d = 0; d < 365; d++)
				colony.noteDeathToll(10);
		}
		long events = lines.stream().filter(l -> l.contains("households died")).count();
		// the point of a trailing baseline: the first days are a spike, but once 10/day IS the norm it
		// is no longer a spike, so a long collapse doesn't post a card every single day
		assertTrue(events >= 1, "the wave breaking is news");
		assertTrue(events < 40, "…but a year of it is not 365 cards (got " + events + ")");
	}
}
