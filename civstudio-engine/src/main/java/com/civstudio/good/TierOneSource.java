package com.civstudio.good;

import java.util.List;

/**
 * A <b>tier-1 provider</b> — the extraction rung at the bottom of the manufactured
 * production chain, exported from {@code data/civ4/zProviders_CIV4BuildingInfos.xml}
 * (+ the gatherer definitions in {@code Regular_CIV4BuildingInfos.xml}) by
 * {@link com.civstudio.geo.export.RecipeExporter} into {@code /tier1-providers.json}.
 * This is where the {@link Recipe} graph <b>terminates in land</b>
 * ({@code docs/manufactured-bonuses.md}, decisions M16/M29): each provider grants one
 * extracted good (wood, hide, bone, the metal ingots…) when <i>any</i> of its
 * tile-based <b>gatherer</b> buildings exists, and each gatherer's terrain/feature/
 * vicinity-bonus prerequisites are the good's <b>raw plot source</b> — e.g.
 * {@code BONUS_WOOD} ← a lumber gatherer, whose prereq is a forest/jungle feature. A
 * colony can produce a tier-1 good only if a claimed plot satisfies at least one
 * gatherer (the M19 hard geography gate).
 * <p>
 * In C2C the provider is a zero-cost auto-built bookkeeping building
 * ({@code BUILDING_RESOURCES_*}); its {@code <PrereqOrBuildings>} names the real
 * gatherers. The exporter inlines each gatherer's plot prerequisites here so the
 * runtime never needs the 2403-building file. A gatherer with no terrain/feature/
 * bonus legs at all is unconditional (e.g. a customs house).
 *
 * @param type      the provider's Civ4 building key (e.g.
 *                  {@code BUILDING_RESOURCES_WOOD})
 * @param output    the extracted good it grants (the single
 *                  {@code <ExtraFreeBonuses>} entry, e.g. {@code BONUS_WOOD})
 * @param gatherers the alternative tile gatherers (any one suffices), each carrying
 *                  its own tech gate and plot-source prerequisites
 */
public record TierOneSource(
		String type,
		String output,
		List<Gatherer> gatherers) {

	/** Defensive-copy the gatherer list. */
	public TierOneSource {
		gatherers = gatherers == null ? List.of() : List.copyOf(gatherers);
	}

	/**
	 * One tile-based gatherer building (a lumber camp, a slaughterhouse…) — an
	 * alternative way of extracting the provider's good, with the plot
	 * prerequisites that are the good's raw source (M29). The field semantics
	 * mirror {@link Recipe}'s prereq legs.
	 *
	 * @param type               the gatherer's Civ4 building key
	 * @param prereqTech         the tech that unlocks it, or {@code null}
	 * @param bonus              a required bonus ({@code <Bonus>}), or {@code null}
	 * @param prereqBonuses      any-of required bonuses ({@code <PrereqBonuses>})
	 * @param vicinityBonuses    bonuses required on a vicinity plot
	 *                           ({@code <PrereqVicinityBonuses>})
	 * @param rawVicinityBonuses raw bonuses required on a vicinity plot
	 *                           ({@code <PrereqRawVicinityBonuses>})
	 * @param prereqOrTerrains   any-of required plot terrains
	 *                           ({@code <PrereqOrTerrain>})
	 * @param prereqOrFeatures   any-of required plot features
	 *                           ({@code <PrereqOrFeature>})
	 * @param river              requires a river plot ({@code <bRiver>})
	 * @param freshWater         requires a fresh-water source ({@code <bFreshWater>})
	 */
	public record Gatherer(
			String type,
			String prereqTech,
			String bonus,
			List<String> prereqBonuses,
			List<String> vicinityBonuses,
			List<String> rawVicinityBonuses,
			List<String> prereqOrTerrains,
			List<String> prereqOrFeatures,
			boolean river,
			boolean freshWater) {

		/** Defensive-copy the lists. */
		public Gatherer {
			prereqBonuses = copyOf(prereqBonuses);
			vicinityBonuses = copyOf(vicinityBonuses);
			rawVicinityBonuses = copyOf(rawVicinityBonuses);
			prereqOrTerrains = copyOf(prereqOrTerrains);
			prereqOrFeatures = copyOf(prereqOrFeatures);
		}

		private static List<String> copyOf(List<String> l) {
			return l == null ? List.of() : List.copyOf(l);
		}
	}

	@Override
	public String toString() {
		return type + " -> " + output + " (x" + gatherers.size() + " gatherers)";
	}
}
