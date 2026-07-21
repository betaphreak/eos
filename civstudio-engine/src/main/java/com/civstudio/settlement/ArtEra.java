package com.civstudio.settlement;

/**
 * The <b>art era</b> a colony's district view is drawn in — the CivStudio analogue of a
 * Civ6 {@code ARTERA_*} ({@code docs/district-generator.md} §1 Layer 2, the
 * {@code EraDistribution} that swaps the building palette so a city visually ages). The
 * eos tech horizon runs Prehistoric&rarr;Atomic (capped at
 * {@code TECH_INFORMATION_LIFESTYLE}), but only Ancient&rarr;Renaissance district art is
 * baked, so the era palette still caps at {@link #RENAISSANCE} (the art assets are the
 * limiter, not the tech horizon).
 * <p>
 * The era is a <b>projection of research progress</b> — how far the colony is through
 * the kept-tech tree — via {@link #fromProgress(int, int)}. Being a pure function of
 * the colony's researched-tech count (already in the render snapshot as the known-tech
 * set, cf. building-import Phase 3c's {@code ColonyView.knownTechs}), it is computed
 * where that data lives (the server feed), the same way {@link DistrictType} is.
 */
public enum ArtEra {

	ANCIENT,
	CLASSICAL,
	MEDIEVAL,
	RENAISSANCE;

	/**
	 * The art era for a colony that has researched {@code researched} of {@code total}
	 * kept techs — the tree's completion fraction bucketed evenly across the eras. A
	 * colony with no research (or an unknown total) reads {@link #ANCIENT}; a fully
	 * researched tree reads {@link #RENAISSANCE}.
	 *
	 * @param researched the number of techs the colony has researched
	 * @param total      the number of kept techs in the tree
	 * @return the projected art era
	 */
	public static ArtEra fromProgress(int researched, int total) {
		if (total <= 0 || researched <= 0)
			return ANCIENT;
		double fraction = Math.min(1.0, (double) researched / total);
		ArtEra[] eras = values();
		int index = (int) Math.floor(fraction * eras.length);
		return eras[Math.min(index, eras.length - 1)];
	}
}
