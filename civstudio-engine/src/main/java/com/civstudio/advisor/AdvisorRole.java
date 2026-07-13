package com.civstudio.advisor;

import com.civstudio.skill.Skill;

/**
 * A seat in the leader's <b>privy council</b> — a court advisor role filled by a
 * named member of the aristocracy (see {@link AdvisorRoster}). These are the
 * four Civ4-style advisors that carry a race-based court portrait in the
 * frontend (Technology, Foreign, Religion, Globe); the map-only "advisors"
 * (Main Map, Zeitgeist) have no seat here.
 * <p>
 * Each role optionally names the {@link Skill} that best fits it: the roster
 * seats the top-skilled noble in that skill (a scholar heads Technology, a
 * social operator heads Foreign). Roles with no matching skill — Religion and
 * Globe, whose crafts have no skill in the twelve — are seated by overall
 * ability instead. The role selection is a deterministic presentation contract;
 * see {@code docs/privy-council.md} §0.
 */
public enum AdvisorRole {

	/** The natural scientist — the colony's ablest scholar heads research. */
	TECHNOLOGY("technology", Skill.INTELLECTUAL),

	/** The diplomat — the ablest social operator handles foreign affairs. */
	FOREIGN("foreign", Skill.SOCIAL),

	/** The theologian — no matching skill, so seated by overall ability. */
	RELIGION("religion", null),

	/** The navigator — no matching skill, so seated by overall ability. */
	GLOBE("globe", null);

	// stable slug the feed labels this role with (matches the frontend advisor id)
	private final String id;

	// the skill this role is filled by, or null to fill by overall ability
	private final Skill matchSkill;

	AdvisorRole(String id, Skill matchSkill) {
		this.id = id;
		this.matchSkill = matchSkill;
	}

	/**
	 * This role's stable slug (e.g. {@code "technology"}) — the frontend advisor
	 * id the render feed keys the roster entry by.
	 *
	 * @return the role slug
	 */
	public String id() {
		return id;
	}

	/**
	 * The skill this role is preferentially filled by, or {@code null} to fill by
	 * {@linkplain com.civstudio.skill.SkillTracker#overallLevel() overall ability}
	 * (a role whose craft has no skill in the twelve).
	 *
	 * @return the matching skill, or {@code null}
	 */
	public Skill matchSkill() {
		return matchSkill;
	}
}
