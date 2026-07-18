package com.civstudio.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of the imported C2C unit catalog ({@code generated/units.json}, from
 * {@code UnitInfoExporter}) — the caravan-relevant slice a {@link MarchingCaravan} reads to
 * <em>embody</em> a unit (its identity, role and stats). Bound from the JSON by
 * {@link UnitCatalog}; unknown fields (pedia, art, subcombats, builds, …) are ignored — the
 * catalog needs only what drives selection and the band's display.
 *
 * @param id          the verbatim C2C id ({@code UNIT_*}) — the unlock token and caravan {@code unitId}
 * @param name        the unit's display name (English), or {@code null}
 * @param role        the {@link CaravanRole} this unit belongs to
 * @param prereqTech  the primary unlocking tech
 * @param obsoleteTech the tech that obsoletes this unit, or {@code null}
 * @param combatClass the unit's {@code <Combat>} UnitCombat class, or {@code null}
 * @param iMoves      base move points, or {@code null}
 * @param iCombat     base combat strength, or {@code null}
 * @param iCost       build cost (hammers) — the interim "advancement" ordering key, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnitInfo(
		@JsonProperty("id") String id,
		@JsonProperty("name") String name,
		@JsonProperty("caravanRole") CaravanRole role,
		@JsonProperty("prereqTech") String prereqTech,
		@JsonProperty("obsoleteTech") String obsoleteTech,
		@JsonProperty("combatClass") String combatClass,
		@JsonProperty("iMoves") Integer iMoves,
		@JsonProperty("iCombat") Integer iCombat,
		@JsonProperty("iCost") Integer iCost) {

	/** The unit's display name, falling back to the id sans {@code UNIT_} when unnamed. */
	public String displayName() {
		return name != null && !name.isBlank() ? name
				: (id == null ? "Unit" : id.replace("UNIT_", ""));
	}
}
