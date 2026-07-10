package com.civstudio.good;

import java.util.List;

/**
 * A <b>manufactured-good recipe</b> — one C2C processing building (a Tannery, a
 * Brickworks) whose output is a manufactured bonus, exported from
 * {@code data/civ4/Regular_CIV4BuildingInfos.xml} by
 * {@link com.civstudio.geo.export.RecipeExporter} into {@code /recipes.json}. The
 * full set is the <b>producer/recipe graph</b> of {@code docs/manufactured-bonuses.md}
 * (decision M7): each entry turns input goods into output goods, and an input that is
 * itself manufactured points at another entry's output — the multi-tier chain. The
 * extraction tier below the graph (raw plot resource → tier-1 good) is the separate
 * {@link TierOneSource} catalog.
 * <p>
 * Like {@link com.civstudio.settlement.HousingBuilding} this is the immutable
 * <b>reference/catalog</b> definition, exported faithfully from the XML; how the
 * runtime consumes it (the producer firm-agent, per-good markets, the monthly demand
 * graph) is the design's step 2. In particular the C2C <i>access-flag</i> semantics
 * are re-read quantitatively (the note's <i>access vs. flow</i> nuance): the input
 * legs are exported as structure, and the per-unit input quantity is an eos
 * calibration choice (M25), not data.
 * <ul>
 * <li>{@link #outputs()} — the manufactured bonuses the building grants
 * ({@code <ExtraFreeBonuses>}, filtered to the manufactured catalog; usually one).
 * The nominal {@code iNumFreeBonuses} is 1 throughout and is not kept.</li>
 * <li>{@link #bonus()} / {@link #prereqBonuses()} — the input legs: the single
 * primary input ({@code <Bonus>}) and the secondary any-of list
 * ({@code <PrereqBonuses>}). E.g. the Tannery: {@code BONUS_HIDE} +
 * {@code BONUS_TANNIN} → {@code BONUS_LEATHER}.</li>
 * <li>{@link #vicinityBonuses()} / {@link #rawVicinityBonuses()} — inputs that must
 * sit on a worked plot in the city's vicinity ({@code <PrereqVicinityBonuses>} /
 * {@code <PrereqRawVicinityBonuses>}) — a land-sourced input leg.</li>
 * <li>{@link #prereqTech()} — the unlocking tech (the M18 gate);
 * {@link #obsoleteTech()} — when the building is retired.</li>
 * <li>{@link #prereqBuildings()} ({@code <PrereqInCityBuildings>}, AND) /
 * {@link #prereqOrBuildings()} ({@code <PrereqOrBuildings>}, any-of) — upstream
 * building prerequisites.</li>
 * <li>{@link #prereqOrTerrains()} / {@link #prereqOrFeatures()} /
 * {@link #river()} / {@link #freshWater()} — any-of plot terrain/feature and water
 * requirements (kept for the few producers that are land-tied).</li>
 * </ul>
 *
 * @param type               the Civ4 building key (e.g. {@code BUILDING_TANNERY})
 * @param outputs            manufactured bonuses granted ({@code <ExtraFreeBonuses>},
 *                           filtered to the manufactured catalog)
 * @param bonus              the primary input bonus ({@code <Bonus>}), or {@code null}
 * @param prereqBonuses      secondary any-of input bonuses ({@code <PrereqBonuses>})
 * @param vicinityBonuses    inputs required on a vicinity plot
 *                           ({@code <PrereqVicinityBonuses>})
 * @param rawVicinityBonuses raw inputs required on a vicinity plot
 *                           ({@code <PrereqRawVicinityBonuses>})
 * @param prereqTech         the tech that unlocks the producer, or {@code null}
 * @param obsoleteTech       the tech that retires it, or {@code null}
 * @param prereqBuildings    buildings required first ({@code <PrereqInCityBuildings>},
 *                           AND)
 * @param prereqOrBuildings  any-of required buildings ({@code <PrereqOrBuildings>})
 * @param prereqOrTerrains   any-of required plot terrains ({@code <PrereqOrTerrain>})
 * @param prereqOrFeatures   any-of required plot features ({@code <PrereqOrFeature>})
 * @param river              requires a river plot ({@code <bRiver>})
 * @param freshWater         requires a fresh-water source ({@code <bFreshWater>})
 */
public record Recipe(
		String type,
		List<String> outputs,
		String bonus,
		List<String> prereqBonuses,
		List<String> vicinityBonuses,
		List<String> rawVicinityBonuses,
		String prereqTech,
		String obsoleteTech,
		List<String> prereqBuildings,
		List<String> prereqOrBuildings,
		List<String> prereqOrTerrains,
		List<String> prereqOrFeatures,
		boolean river,
		boolean freshWater) {

	/** Defensive-copy the lists. */
	public Recipe {
		outputs = copyOf(outputs);
		prereqBonuses = copyOf(prereqBonuses);
		vicinityBonuses = copyOf(vicinityBonuses);
		rawVicinityBonuses = copyOf(rawVicinityBonuses);
		prereqBuildings = copyOf(prereqBuildings);
		prereqOrBuildings = copyOf(prereqOrBuildings);
		prereqOrTerrains = copyOf(prereqOrTerrains);
		prereqOrFeatures = copyOf(prereqOrFeatures);
	}

	private static List<String> copyOf(List<String> l) {
		return l == null ? List.of() : List.copyOf(l);
	}

	@Override
	public String toString() {
		return type + " -> " + outputs;
	}
}
