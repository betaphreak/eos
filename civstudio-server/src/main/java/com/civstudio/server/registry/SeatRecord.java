package com.civstudio.server.registry;

/**
 * The durable record of one player's seat in a run — a row of {@code session_seat}.
 * <p>
 * A seat is <b>who founded which colony, where</b>. Two things depend on it surviving a restart:
 * <ul>
 * <li><b>One seat per player.</b> The rule is a {@code UNIQUE (session_id, user_id)} constraint, so
 * it holds across restarts and races — not merely because a live map happened to remember.</li>
 * <li><b>Rebuilding a Timeline.</b> A Timeline founds no colony of its own: its world is its spec
 * <em>plus its roster</em>. Founding is deterministic (seed + anchor + join order), so replaying
 * these rows in {@code seatedAt} order re-founds exactly the same colonies in the same provinces.
 * The province is recorded anyway, so a restore can be checked rather than trusted.</li>
 * </ul>
 *
 * @param sessionId  the run this seat belongs to
 * @param userId     the {@code app_user} who holds it
 * @param colonyName the colony they founded (named for its province)
 * @param provinceId the province it was founded into
 * @param seatOrder  the order this seat was taken in — the join order the site picker consumed, so
 *                   a rebuild reproduces the same sites
 */
public record SeatRecord(String sessionId, String userId, String colonyName, int provinceId,
		int seatOrder) {
}
