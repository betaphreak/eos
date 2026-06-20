package com.civstudio.tech;

import java.util.List;

import com.civstudio.era.Era;

/**
 * One node of the tech tree: a researchable technology, parsed from
 * {@code techs.json} (a Caveman2Cosmos tech graph) and reduced to the fields eos
 * uses. The Civ4-specific effect flags and the localization/asset keys in the
 * source are discarded at load (see {@link TechTree}); a tech's <em>eos</em> effects
 * come from a separate overlay in a later phase (see {@code docs/tech-tree.md}).
 * <p>
 * Prerequisites follow the source's Civ4 semantics, split across two lists:
 * <ul>
 * <li>{@link #orPrereqs()} — the tech needs <b>any one</b> of these (an empty list
 *     means no "or" constraint; only the tree's single root tech has none at all);</li>
 * <li>{@link #andPrereqs()} — the tech needs <b>all</b> of these (an empty list means
 *     no "and" constraint).</li>
 * </ul>
 * A tech is researchable once {@link TechTree#prereqsSatisfied(Tech, java.util.Set)}
 * holds for the set of techs already known.
 *
 * @param type
 *            the tech's unique id (e.g. {@code "TECH_MERCANTILISM"})
 * @param era
 *            the era it belongs to
 * @param advisor
 *            its advisor branch
 * @param cost
 *            its research cost ({@code iCost} in the source)
 * @param orPrereqs
 *            techs of which any one is required (may be empty)
 * @param andPrereqs
 *            techs of which all are required (may be empty)
 */
public record Tech(String type, Era era, Advisor advisor, int cost,
		List<String> orPrereqs, List<String> andPrereqs) {

	/** Canonical constructor: keep the prereq lists immutable. */
	public Tech {
		orPrereqs = List.copyOf(orPrereqs);
		andPrereqs = List.copyOf(andPrereqs);
	}
}
