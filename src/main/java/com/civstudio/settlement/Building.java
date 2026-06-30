package com.civstudio.settlement;

/**
 * A <b>center building</b> standing on a {@link Plot} — a Civ4-style <i>city</i>
 * building (a banking house, granary, workshop…) with no land footprint of its own,
 * which lives at the <b>village center</b> (plot 0) where the landless center-grouped
 * firms and banks reside (see {@code docs/village-founding.md}). It is deliberately
 * <b>distinct</b> from the tile {@link com.civstudio.geo.Improvement} leg a
 * land-working firm raises on its own plot: an improvement enters the plot's
 * {@link Plot#yields()}; a building does not (its economic effect is deferred — see
 * {@code docs/plots.md}, <i>Buildings vs. improvements</i>).
 * <p>
 * A building is keyed by its <b>eos-native id</b> — the same id the tech tree's
 * {@link com.civstudio.tech.TechEffect.Unlock} names (e.g. {@code
 * "FIRM_BANKING_HOUSE"}), so that researching the unlocking tech is what (in a later
 * phase) triggers the building's auto-construction at the center.
 * <p>
 * <b>Phase 1 is the data model only.</b> A plot tracks its buildings, but nothing
 * populates them and they carry no yield, so the change is byte-identical; the
 * tech-gated auto-build trigger and the building's economic effect are later phases.
 *
 * @param id the building's eos-native id (the {@code Unlock} target; non-blank)
 */
public record Building(String id) {

	/** Validate the id is present. */
	public Building {
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("building id must be non-blank");
	}
}
