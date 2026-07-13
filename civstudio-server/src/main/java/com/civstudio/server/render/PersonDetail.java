package com.civstudio.server.render;

import java.util.List;

/**
 * A read-only, JSON-serializable projection of one court member and their
 * household — the character sheet the advisor rail requests by agent id (see
 * {@code docs/privy-council.md} §2b). Assembled on demand from the POV colony's
 * live household/skill/mortality state.
 *
 * @param personId  the household's agent id (its key within the colony)
 * @param name      the head's full name
 * @param race      the head's race slug (e.g. {@code "human"})
 * @param gender    the head's gender ({@code "male"} / {@code "female"})
 * @param culture   the colony's Anbennar culture slug (founding province), or {@code null}
 * @param role      the household's role label ({@code "Noble"} / {@code "Ruler"})
 * @param ageYears  the head's age in whole years
 * @param skills    the head's twelve skills, each with level and passion
 * @param household the household's members (head first)
 */
public record PersonDetail(int personId, String name, String race, String gender,
		String culture, String role, int ageYears, List<SkillView> skills,
		List<MemberView> household) {

	/**
	 * One of the head's skills for the character sheet's skill bars.
	 *
	 * @param skill   the skill slug (e.g. {@code "intellectual"})
	 * @param level   the level, in {@code [0, 20]}
	 * @param passion the passion ({@code "none"} / {@code "minor"} / {@code "major"})
	 */
	public record SkillView(String skill, int level, String passion) {
	}

	/**
	 * One member of the household for the flat member list.
	 *
	 * @param name     the member's full name
	 * @param relation the member's relation to the head ({@code "head"} /
	 *                 {@code "spouse"} / {@code "child"})
	 * @param ageYears the member's age in whole years
	 * @param gender   the member's gender ({@code "male"} / {@code "female"})
	 * @param race     the member's race slug
	 * @param alive    whether the member is alive
	 */
	public record MemberView(String name, String relation, int ageYears, String gender,
			String race, boolean alive) {
	}
}
