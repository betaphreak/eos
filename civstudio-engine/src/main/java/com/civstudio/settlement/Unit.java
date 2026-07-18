package com.civstudio.settlement;

/**
 * A <b>buildable land unit</b> imported from Caveman2Cosmos and gated to the eos tech
 * horizon — the catalog identity a {@link com.civstudio.agent.MarchingCaravan caravan
 * band} <em>embodies</em> (its name, move-points, combat class and art), the way a
 * {@link Building} is the identity of a center building. The unit is a <b>loadout</b>,
 * not the band: a caravan is its people (each with the twelve skills) <em>plus</em> an
 * embodied {@code UNIT_*} that supplies identity/stats/art; the people carry the fighting
 * (via {@link com.civstudio.skill.Skill#WARFARE} and the role signature skill), the unit
 * supplies the equipment. See {@code docs/c2c-unit-import.md}.
 * <p>
 * Keyed by its <b>verbatim C2C id</b> ({@code UNIT_*}) — the same id the tech tree's
 * {@link com.civstudio.tech.TechEffect.Unlock} names and the caravan's {@code unitId}, so
 * there is no mapping table. The full stat/role/art catalog lives in the generated
 * {@code units.json}; this record is the engine-side key.
 * <p>
 * <b>Phase 1 is the data model only.</b> The catalog imports and the tech {@code Unlock}
 * overlay grants {@code UNIT_*} tokens, but nothing selects or musters a unit yet (the
 * producer-determined caravan realization is Phase 5), so no run changes.
 *
 * @param id the unit's verbatim C2C id (the {@code Unlock} target and caravan {@code
 *           unitId}; non-blank)
 */
public record Unit(String id) {

	/** Validate the id is present. */
	public Unit {
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("unit id must be non-blank");
	}
}
