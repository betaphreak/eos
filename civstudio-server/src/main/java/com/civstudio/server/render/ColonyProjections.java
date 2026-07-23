package com.civstudio.server.render;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillTracker;

/**
 * Projects a {@link Settlement colony} into its {@link ColonyDetail composition sheet} for the rail
 * panel. The settlement analogue of {@link CaravanProjections}: served on an HTTP thread off the
 * session thread, so it reads the colony's agent list <b>defensively</b> — a copy captured
 * mid-{@code newDay} could throw {@link java.util.ConcurrentModificationException}; a torn read
 * degrades to what was gathered so far rather than failing the request.
 */
public final class ColonyProjections {

	private ColonyProjections() {
	}

	/**
	 * Project a colony into its composition sheet as of its current date (for member ages).
	 *
	 * @param c the colony
	 * @return the composition sheet
	 */
	public static ColonyDetail of(Settlement c) {
		LocalDate today = c.getDate();
		List<Household> houses = new ArrayList<>();
		int poolSize = 0;
		try {
			for (Agent a : c.getAgents()) {
				if (!a.isAlive())
					continue;
				if (a instanceof Retinue r)
					poolSize += r.size();
				else if (a instanceof Household h)
					houses.add(h);
			}
		} catch (RuntimeException torn) {
			// mid-tick mutation of the agent list — keep whatever we gathered before it tore
		}

		// colony-average skill profile across the household heads (each house's skilled worker) — the
		// aggregate shape the caravan panel uses, not per-person
		List<ColonyDetail.SkillAvg> skills = new ArrayList<>(Skill.COUNT);
		for (Skill s : Skill.all()) {
			double sum = 0;
			int n = 0;
			for (Household h : houses) {
				SkillTracker t = h.getHead().skills();
				if (t != null) {
					sum += t.level(s);
					n++;
				}
			}
			skills.add(new ColonyDetail.SkillAvg(s.name().toLowerCase(Locale.ROOT),
					n == 0 ? 0 : sum / n));
		}

		// roster: the ruler and nobles first, then the laborers, each class ranked within itself by its
		// head's ablest skill descending (a STABLE sort keeps equal-skill houses in agent order). The
		// caravan sorts its whole roster by SURVIVAL; a colony leads with its leadership the same way a
		// band leads with its leader.
		List<Household> sorted = new ArrayList<>(houses);
		sorted.sort(Comparator.comparingInt(ColonyProjections::rank)
				.thenComparing(Comparator.comparingInt(ColonyProjections::topSkillLevel).reversed()));
		int population = 0, nobles = 0;
		String rulerName = null;
		List<ColonyDetail.Resident> roster = new ArrayList<>(sorted.size());
		for (Household h : sorted) {
			Member head = h.getHead();
			boolean ruler = h instanceof Ruler;
			boolean noble = "Noble".equals(h.role());
			if (ruler)
				rulerName = head.fullName();
			else if (noble)
				nobles++;
			else if (h.isWorkforce())
				population++;
			Skill top = topSkill(head);
			SkillTracker t = head.skills();
			roster.add(new ColonyDetail.Resident(head.fullName(), h.role(), head.race().id(),
					head.getAgeYears(today),
					top == null ? null : top.name().toLowerCase(Locale.ROOT),
					top == null || t == null ? 0 : t.level(top), ruler, noble));
		}

		String tier = c.getTier() == null ? null : c.getTier().name();
		String province = c.getProvince() == null ? null : c.getProvince().name();
		// what the crown could start today, in the build brain's own score order — the city
		// screen's decree picker. Empty for a colony without the build economy.
		var economy = c.getBuildEconomy();
		List<String> candidates = economy == null ? List.of()
				: economy.buildableCandidates().stream().map(b -> b.id()).toList();
		// canCommand is the caller's business, not the colony's — the controller fills it in
		return new ColonyDetail(c.getName(), tier, province, rulerName,
				population, nobles, poolSize, skills, roster, candidates, false);
	}

	// sort class: the ruler leads, then nobles, then everyone else (laborers)
	private static int rank(Household h) {
		if (h instanceof Ruler)
			return 0;
		return "Noble".equals(h.role()) ? 1 : 2;
	}

	// the head's ablest skill, or null when the head carries no skill tracker
	private static Skill topSkill(Member head) {
		SkillTracker t = head.skills();
		if (t == null)
			return null;
		Skill best = null;
		int bestLvl = -1;
		for (Skill s : Skill.all()) {
			int lvl = t.level(s);
			if (lvl > bestLvl) {
				bestLvl = lvl;
				best = s;
			}
		}
		return best;
	}

	// the head's ablest-skill level (the roster's within-class sort key); -1 when no tracker, so the
	// trackerless sort below the skilled
	private static int topSkillLevel(Household h) {
		SkillTracker t = h.getHead().skills();
		if (t == null)
			return -1;
		int best = -1;
		for (Skill s : Skill.all())
			best = Math.max(best, t.level(s));
		return best;
	}
}
