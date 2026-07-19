package com.civstudio.server.render;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.civstudio.agent.Caravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillTracker;

/**
 * Projects a {@link Caravan wandering band} into its {@link CaravanDetail composition sheet} for the
 * rail panel. Like {@link PersonProjections}, this is served on an HTTP thread off the session
 * thread, so it reads the band's following <b>defensively</b> — a member list captured mid-{@code
 * tick} could throw {@link java.util.ConcurrentModificationException}; a torn read degrades to the
 * leader alone rather than failing the request.
 */
public final class CaravanProjections {

	private CaravanProjections() {
	}

	/**
	 * Project a band into its composition sheet as of <tt>today</tt> (for member ages).
	 *
	 * @param band  the band
	 * @param today the session's current date
	 * @return the composition sheet
	 */
	public static CaravanDetail of(Caravan band, LocalDate today) {
		Member leader = band.getLeader();
		List<Member> crew = crew(band, leader);

		// band-average skill profile (aggregate, not per-member — the chosen panel shape)
		List<CaravanDetail.SkillAvg> skills = new ArrayList<>(Skill.COUNT);
		for (Skill s : Skill.all()) {
			double sum = 0;
			int n = 0;
			for (Member m : crew) {
				SkillTracker t = m.skills();
				if (t != null) {
					sum += t.level(s);
					n++;
				}
			}
			skills.add(new CaravanDetail.SkillAvg(s.name().toLowerCase(Locale.ROOT),
					n == 0 ? 0 : sum / n));
		}

		// roster ordered by SURVIVAL descending — a STABLE sort, so ties keep crew order (the leader,
		// added first, leads equal-survival followers). This is the leader-succession order.
		List<Member> sorted = new ArrayList<>(crew);
		sorted.sort(Comparator.comparingInt(CaravanProjections::survival).reversed());
		List<CaravanDetail.Crew> roster = new ArrayList<>(sorted.size());
		for (Member m : sorted)
			roster.add(new CaravanDetail.Crew(m.fullName(), m.race().id(), m.getAgeYears(today),
					survival(m), m == leader));

		String unitName = null, role = null;
		double larder = 0;
		if (band instanceof MarchingCaravan mc) {
			unitName = mc.getUnitName();
			role = mc.role().name();
			larder = mc.getFollowing().getLarder();
		}
		// the deduped crew is the honest head-count (leader + distinct followers): a settler's leader
		// is carried apart from the following, an explorer's leader is one of the draftees.
		return new CaravanDetail(band.getId(), leader == null ? "?" : leader.fullName(),
				unitName, role, crew.size(), larder, band.getHoard(), skills, roster);
	}

	// the band's living crew: the leader, then the following's members. Read defensively — a torn
	// mid-tick read of the following degrades to the leader alone (the panel tolerates a stale frame).
	private static List<Member> crew(Caravan band, Member leader) {
		List<Member> crew = new ArrayList<>();
		if (leader != null && leader.isAlive())
			crew.add(leader);
		if (band instanceof MarchingCaravan mc) {
			try {
				// an explorer levy's leader is itself a draftee (it leads from the ranks), so skip it
				// here or it would be counted twice; a settler's leader is carried apart (never here).
				for (Member m : mc.getFollowing().members())
					if (m != leader && m.isAlive())
						crew.add(m);
			} catch (RuntimeException torn) {
				// mid-tick mutation — keep the leader we already have
			}
		}
		return crew;
	}

	private static int survival(Member m) {
		SkillTracker t = m.skills();
		return t == null ? -1 : t.level(Skill.SURVIVAL);
	}
}
