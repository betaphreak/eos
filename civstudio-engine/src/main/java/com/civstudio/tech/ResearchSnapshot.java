package com.civstudio.tech;

import com.civstudio.agent.Caravan;

import java.util.Set;

/**
 * An immutable snapshot of a colony's {@link ResearchState}, carried by a wandering
 * {@link Caravan band} across the settle/unsettle hinge so a re-founded
 * colony resumes the tech tree where the abandoned one left off (see
 * {@code docs/caravan.md} / {@code docs/tech-tree.md}). It records what the band
 * knows, which of those techs it actually <b>researched</b> (so the re-founded colony
 * can re-apply their effects and recover its productivity), and the focus it was part
 * way through.
 *
 * @param known
 *            every tech the band knows (pre-known baseline plus researched)
 * @param completed
 *            the techs it researched itself (a subset of {@code known}); their
 *            effects are re-applied on restore
 * @param focusType
 *            the id of the tech it was researching, or {@code null} if none
 * @param progress
 *            research points accumulated toward the focus (or buffered while it had
 *            no focus)
 */
public record ResearchSnapshot(Set<String> known, Set<String> completed,
		String focusType, double progress) {

	/** Canonical constructor: keep the carried sets immutable. */
	public ResearchSnapshot {
		known = Set.copyOf(known);
		completed = Set.copyOf(completed);
	}
}
