package com.civstudio.advisor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Household;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.Skill;

/**
 * The colony's <b>privy council</b>: which named noble fills each {@link
 * AdvisorRole}. The advisors are real people from the leader's court — the
 * aristocracy raised by ennoblement, plus the ruler as a fallback — not the
 * faceless {@code Retinue} peasant pool. The roster is a deterministic
 * projection of the colony's living agents (no economic RNG), refreshed once a
 * day at the end of {@link Settlement#newDay()}.
 * <p>
 * <b>Sticky with succession.</b> Once a noble is seated in a role it keeps that
 * role for life — {@link #refresh()} only re-seats a role whose holder has died
 * or left the aristocracy (dropping out of the living-noble set). When that
 * happens the selection rule re-runs and auto-picks a successor, so the roster
 * is dynamic over time (the name + race behind a role change on succession)
 * while a healthy advisor is never displaced by a rising rival.
 * <p>
 * <b>Selection.</b> Skill-matched roles ({@link AdvisorRole#matchSkill()} != null)
 * are filled first, seating the top noble in that skill; the remaining roles are
 * filled by overall ability. Nobles are seated <b>distinctly</b> (one role each)
 * as far as they go; any role left empty for want of nobles is filled by the
 * ruler (who may therefore head several roles when the aristocracy is thin — the
 * sovereign advising himself until nobles rise). Ties break toward the lower
 * agent id, so a given colony state always yields the same roster.
 *
 * @see docs/privy-council.md §0
 */
public final class AdvisorRoster {

	private final Settlement colony;

	// the noble (or ruler) seated in each role; absent = unfilled. Holds the
	// Household object, so a role's name/race/portrait follow the head across a
	// same-household head promotion for free, and reads always see current state.
	private final EnumMap<AdvisorRole, Household> holders =
			new EnumMap<>(AdvisorRole.class);

	public AdvisorRoster(Settlement colony) {
		this.colony = colony;
	}

	/**
	 * Re-seat any role whose holder has died or left the aristocracy, then fill
	 * every still-empty role from the current court. Cheap and idempotent; called
	 * once a day after the colony's agent set has settled.
	 */
	public void refresh() {
		List<Household> nobles = livingNobles();
		Ruler ruler = colony.getRuler();

		// vacate seats that should not carry over. A NOBLE seat is sticky — kept for
		// life, vacated only when its noble is gone (dead or demoted out of the
		// aristocracy). A RULER seat is only a backstop, so it is provisional:
		// always vacated here so a noble who has since risen can claim the role in
		// pass 1, and re-applied in pass 2 for any role the nobles still cannot fill.
		for (AdvisorRole role : AdvisorRole.values()) {
			Household held = holders.get(role);
			if (held != null && !nobles.contains(held))
				holders.remove(role);
		}

		// pass 1 — seat distinct nobles, skill-matched roles first (enum order puts
		// the skill-matched roles ahead of the ability-filled ones)
		List<Household> assigned = new ArrayList<>(holders.values());
		for (AdvisorRole role : AdvisorRole.values()) {
			if (holders.containsKey(role))
				continue;
			Household pick = bestUnassignedNoble(nobles, role, assigned);
			if (pick != null) {
				holders.put(role, pick);
				assigned.add(pick);
			}
		}

		// pass 2 — the ruler backstops any role the aristocracy could not fill (the
		// ruler may repeat across empty roles, so the portrait UI is never blank
		// while a ruler lives)
		if (ruler != null && ruler.isAlive())
			for (AdvisorRole role : AdvisorRole.values())
				if (!holders.containsKey(role))
					holders.put(role, ruler);
	}

	/**
	 * The noble (or ruler) currently seated in <tt>role</tt>, or {@code null} if
	 * the role is unfilled.
	 *
	 * @param role
	 *            the advisor role
	 * @return the seated household, or {@code null}
	 */
	public Household holder(AdvisorRole role) {
		return holders.get(role);
	}

	/**
	 * A snapshot of the current seating, role → seated household. An unmodifiable
	 * copy; roles that are unfilled are absent.
	 *
	 * @return the current roster
	 */
	public Map<AdvisorRole, Household> assignments() {
		return new EnumMap<>(holders);
	}

	// the highest-scoring noble not already seated this refresh, or null if none is
	// available. Score is the role's matching-skill level, or overall ability when
	// the role has no matching skill; ties break toward the lower agent id.
	private Household bestUnassignedNoble(List<Household> nobles, AdvisorRole role,
			List<Household> assigned) {
		Skill skill = role.matchSkill();
		return nobles.stream()
				.filter(n -> !assigned.contains(n))
				.max(Comparator
						.comparingInt((Household n) -> score(n, skill))
						.thenComparing(n -> -n.getID()))
				.orElse(null);
	}

	// a household's fitness for a role: its level in the role's matching skill, or
	// its overall level when the role has none
	private static int score(Household h, Skill skill) {
		return skill != null
				? h.getHead().skills().level(skill)
				: h.getHead().skills().overallLevel();
	}

	// the colony's living nobles (the aristocracy), the pool advisors are drawn
	// from; the ruler is handled separately as a fallback
	private List<Household> livingNobles() {
		List<Household> nobles = new ArrayList<>();
		for (Agent a : colony.getAgents())
			if (a instanceof Noble noble && noble.isAlive())
				nobles.add(noble);
		return nobles;
	}
}
