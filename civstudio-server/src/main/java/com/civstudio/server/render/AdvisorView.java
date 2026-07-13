package com.civstudio.server.render;

/**
 * A read-only projection of one seat in a colony's privy council (see {@link
 * com.civstudio.advisor.AdvisorRoster}) for the live feed: which named court
 * member — a noble, or the ruler as a fallback — currently fills an advisor
 * role, and the identity the frontend needs to label the advisor and pick its
 * race-based portrait. Assembled on the session thread by {@link Snapshots}
 * between ticks, so its reads see a settled roster.
 *
 * @param role     the advisor role slug (e.g. {@code "technology"}) — the
 *                 frontend advisor id this seat belongs to
 * @param personId the seated household's agent id (unique within the colony) —
 *                 the key the person-detail request resolves against
 * @param name     the seated head's full name
 * @param race     the seated head's race slug (e.g. {@code "human"}) — selects
 *                 the portrait art
 * @param gender   the seated head's gender ({@code "male"} / {@code "female"})
 */
public record AdvisorView(String role, int personId, String name, String race,
		String gender) {
}
