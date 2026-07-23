package com.civstudio.server.render;

import java.util.List;

/**
 * The <b>composition</b> of one colony — served on demand from {@code GET
 * /api/sessions/{sid}/colony} when a spectator clicks one of the live vitals figures in the top bar
 * (its rail panel). The caravan analogue for a settlement: where the per-tick {@link ColonyView}
 * ships aggregates only, this projects the colony's <b>people</b> — a colony-average skill profile
 * across its household heads, and the full roster of households (the ruler and nobles first, then the
 * laborers, each ranked by their head's ablest skill).
 *
 * <p>Read on an HTTP thread off the session thread, so {@link ColonyProjections} reads the colony's
 * agent list <b>defensively</b> (a torn mid-{@code newDay} read degrades to what was gathered so far
 * rather than failing the request). See {@code docs/caravan.md} — this mirrors {@link CaravanDetail}.
 *
 * @param name       the colony's name
 * @param tier       the colony's {@link com.civstudio.settlement.SettlementTier} rung (raw enum name),
 *                   or {@code null} when it has none
 * @param province   the colony's founding-province display name, or {@code null}
 * @param rulerName  the ruling household's head, or {@code null} when the colony has no ruler
 * @param population the workforce (laborer households)
 * @param nobles     noble households
 * @param poolSize   the peasant-pool reserve size (a faceless labour reserve — a stat, not roster rows)
 * @param skills     the colony-average level of each of the twelve skills across household heads
 *                   (skill order)
 * @param members    the roster, ruler and nobles first, then laborers, each ranked within its class
 *                   by its head's ablest skill
 * @param candidates every building the crown could start at the center today, in the build brain's
 *                   score order — the city screen's decree picker (see {@code
 *                   docs/city-screen-plan.md}). Bare ids; the client joins {@code /api/buildings}
 *                   for name, cost and icon. Uncapped: this is an on-demand fetch, not a per-tick
 *                   frame, and a silently truncated list reads as "that is all you may build"
 * @param canCommand whether <b>this caller</b> may command this colony — the answer to {@link
 *                   com.civstudio.server.web.SessionAuthz#denyColonyCommand}, not a restatement of
 *                   its rule, so the client never re-implements the authz. {@code false} on the
 *                   projection itself; the controller fills it in per request
 */
public record ColonyDetail(String name, String tier, String province, String rulerName,
		int population, int nobles, int poolSize,
		List<SkillAvg> skills, List<Resident> members, List<String> candidates,
		boolean canCommand) {

	/**
	 * This sheet with its {@code canCommand} answered — the one field the projection cannot know
	 * (it depends on who is asking, which only the request knows).
	 *
	 * @param allowed whether the caller may command this colony
	 * @return a copy carrying the answer
	 */
	public ColonyDetail withCanCommand(boolean allowed) {
		return new ColonyDetail(name, tier, province, rulerName, population, nobles, poolSize,
				skills, members, candidates, allowed);
	}

	/**
	 * One skill's colony-average level (across the household heads).
	 *
	 * @param skill the skill name (lower-case)
	 * @param avg   the mean level across the heads
	 */
	public record SkillAvg(String skill, double avg) {
	}

	/**
	 * One household in the roster, projected through its head.
	 *
	 * @param name          the head's full name
	 * @param role          the household's population role ("Ruler", "Noble", a laborer label)
	 * @param race          the head's race id (slug)
	 * @param age           the head's age in years
	 * @param topSkill      the head's ablest skill name (lower-case), or {@code null} if the head
	 *                      carries no skill tracker
	 * @param topSkillLevel the head's ablest skill level (the roster's within-class sort key)
	 * @param ruler         whether this household rules the colony (badged in the roster)
	 * @param noble         whether this is a noble household
	 */
	public record Resident(String name, String role, String race, int age,
			String topSkill, int topSkillLevel, boolean ruler, boolean noble) {
	}
}
