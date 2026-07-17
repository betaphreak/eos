package com.civstudio.server.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry for a server whose runs are not meant to outlive it — a test, a local dev session.
 * Correct, not a stub: it enforces the same one-seat-per-player rule, it simply forgets everything
 * when the process does. Configure a datasource to get {@link JdbcSessionRegistry} instead.
 */
public final class InMemorySessionRegistry implements SessionRegistry {

	// insertion-ordered so all() and seats() report the order things happened, matching the JDBC
	// implementation's ORDER BY — a caller must not be able to tell the two apart
	private final Map<String, SessionRecord> records = new LinkedHashMap<>();
	private final Map<String, List<SeatRecord>> seats = new LinkedHashMap<>();

	@Override
	public synchronized void save(SessionRecord record) {
		records.put(record.id(), record);
	}

	@Override
	public synchronized void updateProgress(String id, String state, String endReason, long tick) {
		SessionRecord r = records.get(id);
		if (r == null)
			return; // nothing remembered to update — the caller's run was never recorded
		records.put(id, new SessionRecord(r.id(), r.scenario(), r.seed(), r.provinceId(), r.owner(),
				state, endReason, tick));
	}

	@Override
	public synchronized Optional<SessionRecord> find(String id) {
		return Optional.ofNullable(records.get(id));
	}

	@Override
	public synchronized List<SessionRecord> all() {
		return new ArrayList<>(records.values());
	}

	@Override
	public synchronized void seat(SeatRecord seat) {
		List<SeatRecord> taken = seats.computeIfAbsent(seat.sessionId(), k -> new ArrayList<>());
		for (SeatRecord s : taken)
			if (s.userId().equals(seat.userId()))
				throw new SeatTakenException(
						seat.userId() + " already holds a seat in " + seat.sessionId());
		taken.add(seat);
	}

	@Override
	public synchronized void forget(String id) {
		records.remove(id);
		seats.remove(id);
	}

	@Override
	public synchronized List<SeatRecord> seats(String sessionId) {
		return new ArrayList<>(seats.getOrDefault(sessionId, List.of()));
	}

	@Override
	public synchronized Optional<SeatRecord> seatOf(String sessionId, String userId) {
		for (SeatRecord s : seats.getOrDefault(sessionId, List.of()))
			if (s.userId().equals(userId))
				return Optional.of(s);
		return Optional.empty();
	}
}
