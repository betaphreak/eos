package com.civstudio.server.render;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillTracker;

/**
 * Projects a {@link Household} into a {@link PersonDetail} character sheet for the
 * advisor rail. Unlike {@link Snapshots} (assembled on the session thread between
 * ticks), this is served on demand from an HTTP thread, so it reads the live
 * household <b>defensively</b> — a member list captured mid-{@code newDay} could
 * otherwise throw {@link java.util.ConcurrentModificationException}; a torn read
 * degrades to the head alone rather than failing the request. A spectator detail
 * panel tolerates the rare stale frame; the client can re-request.
 */
public final class PersonProjections {

	private PersonProjections() {
	}

	/**
	 * Project a household into its character sheet as of <tt>today</tt>.
	 *
	 * @param h     the household (a noble or the ruler)
	 * @param today the colony's current date, for computing ages
	 * @return the character sheet
	 */
	public static PersonDetail of(Household h, LocalDate today) {
		Member head = h.getHead();
		return new PersonDetail(h.getID(), head.fullName(), head.race().id(),
				gender(head), h.role(), head.getAgeYears(today), skills(head),
				household(h, today));
	}

	// the head's twelve skills (a stable order by skill index), each level + passion;
	// empty if the head somehow carries no skill tracker
	private static List<PersonDetail.SkillView> skills(Member head) {
		SkillTracker tracker = head.skills();
		if (tracker == null)
			return List.of();
		List<PersonDetail.SkillView> views = new ArrayList<>(Skill.COUNT);
		for (Skill s : Skill.all())
			views.add(new PersonDetail.SkillView(s.name().toLowerCase(Locale.ROOT),
					tracker.level(s), tracker.getSkill(s).getPassion().name()
							.toLowerCase(Locale.ROOT)));
		return views;
	}

	// the household's members, head first — read defensively (a copy taken while
	// newDay mutates the list can throw; fall back to the head alone)
	private static List<PersonDetail.MemberView> household(Household h, LocalDate today) {
		List<Member> members;
		try {
			members = new ArrayList<>(h.getMembers());
		} catch (RuntimeException torn) {
			members = List.of(h.getHead());
		}
		List<PersonDetail.MemberView> views = new ArrayList<>(members.size());
		for (int i = 0; i < members.size(); i++) {
			Member m = members.get(i);
			views.add(new PersonDetail.MemberView(m.fullName(), relation(i, m),
					m.getAgeYears(today), gender(m), m.race().id(), m.isAlive()));
		}
		return views;
	}

	// the member's relation to the head: member 0 is the head; a member with known
	// parentage was born into the house (a child); anyone else wed in (a spouse)
	private static String relation(int index, Member m) {
		if (index == 0)
			return "head";
		if (m.getMother() != null || m.getFather() != null)
			return "child";
		return "spouse";
	}

	private static String gender(Member m) {
		return m.gender().name().toLowerCase(Locale.ROOT);
	}
}
