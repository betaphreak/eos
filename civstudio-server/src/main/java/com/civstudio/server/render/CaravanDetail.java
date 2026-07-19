package com.civstudio.server.render;

import java.util.List;

/**
 * The <b>composition</b> of one wandering band — served on demand from {@code GET
 * /api/sessions/{sid}/caravan/{id}} when a spectator clicks the band's map icon (its rail panel).
 * Unlike the per-tick {@link CaravanView} (aggregates only), this projects the band's people: a
 * band-average skill profile and the full roster, ordered by {@code SURVIVAL} descending (the ablest
 * survivors first — the leader-succession order, docs/caravan.md), the leader flagged.
 *
 * <p>Read on an HTTP thread off the session thread, so {@link CaravanProjections} reads the band's
 * following <b>defensively</b> (a torn mid-tick read degrades to the leader alone).
 *
 * @param id       the band's stable id (echoed for the client's cache key)
 * @param leader   the current leader's full name
 * @param unitName the embodied C2C unit's display name, or {@code null}
 * @param role     the band's role (SETTLER / WORKER / EXPLORER / MILITARY), or {@code null}
 * @param bandSize the band's head-count (leader + following)
 * @param larder   the carried food larder (its countdown to starvation)
 * @param hoard    the carried money (copper)
 * @param skills   the band-average level of each of the twelve skills (skill order)
 * @param members  the roster, ordered by {@code SURVIVAL} descending, the leader flagged
 */
public record CaravanDetail(long id, String leader, String unitName, String role,
		int bandSize, double larder, double hoard,
		List<SkillAvg> skills, List<Crew> members) {

	/**
	 * One skill's band-average level (across the living crew).
	 *
	 * @param skill the skill name (lower-case)
	 * @param avg   the mean level across the band
	 */
	public record SkillAvg(String skill, double avg) {
	}

	/**
	 * One band member in the roster.
	 *
	 * @param name     the member's full name
	 * @param race     the member's race id (slug)
	 * @param age      the member's age in years
	 * @param survival the member's {@code SURVIVAL} skill level (the roster's sort key)
	 * @param leader   whether this member leads the band
	 */
	public record Crew(String name, String race, int age, int survival, boolean leader) {
	}
}
