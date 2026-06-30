package com.civstudio.settlement;

import java.util.Arrays;
import java.util.List;

/**
 * A <b>housing building</b> catalog entry — one rung of the C2C housing ladder
 * (lean-tos → hovels → cottages → … → arcologies), exported from
 * {@code data/SpecialBuildings_CIV4BuildingInfos.xml} by
 * {@link com.civstudio.geo.export.HousingExporter} into {@code /housing.json}.
 * <p>
 * This is the immutable <b>reference/catalog</b> definition (like
 * {@link com.civstudio.geo.Improvement} for tile improvements), distinct from the
 * placed-instance {@link Building} a {@link Plot} tracks: this carries the
 * <b>prerequisites</b> that decide <i>whether</i> a village may raise the building,
 * while a {@code Building} is the bare id of one actually standing at the center.
 * <p>
 * Every housing building is {@code bAutoBuild} in Civ4 — the colony does not choose
 * to build it; once its prerequisites are met it is raised automatically at the
 * village center (plot 0), the highest-tier qualifying rung superseding the lower
 * ones via {@link #replacements()}. The prereq legs this record keeps:
 * <ul>
 * <li>{@link #prereqTech()} — the unlocking tech (the primary gate; the only leg
 * the tech tree's {@code Unlock} seam reads today). {@code null} for the
 * baseline {@code BUILDING_HOUSING_HOMELESS} fallback.</li>
 * <li>{@link #prereqPopulation()} — minimum settlement population.</li>
 * <li>{@link #freshWater()} — requires a fresh-water source.</li>
 * <li>{@link #bonus()} / {@link #prereqBonuses()} — the construction material(s)
 * the colony must have access to (the single Civ4 {@code <Bonus>} plus the
 * {@code <PrereqBonuses>} AND-list).</li>
 * <li>{@link #prereqBuildings()} — other center buildings required first
 * ({@code <PrereqInCityBuildings>}, AND); {@link #prereqOrBuildings()} — an
 * any-of alternative ({@code <PrereqOrBuildings>}).</li>
 * <li>{@link #prereqOrFeatures()} / {@link #prereqOrTerrains()} — an any-of
 * plot feature/terrain requirement (e.g. a longhouse wants nearby forest).</li>
 * </ul>
 * The ladder structure ({@link #obsoleteTech()}, {@link #obsoletesToBuilding()},
 * {@link #replacements()}) records how a rung is retired or upgraded.
 * <p>
 * The <b>effect</b> fields ({@link #health()}, {@link #happiness()},
 * {@link #yieldChanges()}, {@link #commerceChanges()}) are <b>stored but dormant</b>
 * — kept for fidelity and a later cut, exactly as the building's economic role is
 * deferred in {@code docs/plots.md} (<i>Buildings vs. improvements</i>). Tech-gating
 * itself is likewise wired only when the auto-build phase consumes the {@code Unlock}
 * seam; this record is the data that phase reads.
 *
 * @param type                the Civ4 type key (e.g. {@code BUILDING_HOUSING_HOVELS})
 * @param prereqTech          the tech that unlocks it, or {@code null}
 * @param obsoleteTech        the tech that retires it, or {@code null}
 * @param obsoletesToBuilding the building it becomes on obsolescence, or {@code null}
 * @param prereqPopulation    minimum population ({@code <iPrereqPopulation>}, 0 if none)
 * @param freshWater          needs fresh water ({@code <bFreshWater>})
 * @param autoBuild           auto-raised when prereqs met ({@code <bAutoBuild>}; always
 *                            true for housing, kept for fidelity)
 * @param bonus               the single construction-material bonus ({@code <Bonus>}),
 *                            or {@code null}
 * @param prereqBonuses       additional required bonuses ({@code <PrereqBonuses>}, AND)
 * @param prereqBuildings     center buildings required first
 *                            ({@code <PrereqInCityBuildings>}, AND)
 * @param prereqOrBuildings   any-of required buildings ({@code <PrereqOrBuildings>})
 * @param prereqOrFeatures    any-of required plot features ({@code <PrereqOrFeature>})
 * @param prereqOrTerrains    any-of required plot terrains ({@code <PrereqOrTerrain>})
 * @param replacements        the buildings that supersede it ({@code <ReplacementBuildings>})
 * @param health              the {@code <iHealth>} change (stored, dormant)
 * @param happiness           the {@code <iHappiness>} change (stored, dormant)
 * @param yieldChanges        the {@code [food, production, commerce]} change (stored,
 *                            dormant; length 3)
 * @param commerceChanges     the {@code [gold, research, culture, espionage]} change
 *                            (stored, dormant; length 4)
 */
public record HousingBuilding(
		String type,
		String prereqTech,
		String obsoleteTech,
		String obsoletesToBuilding,
		int prereqPopulation,
		boolean freshWater,
		boolean autoBuild,
		String bonus,
		List<String> prereqBonuses,
		List<String> prereqBuildings,
		List<String> prereqOrBuildings,
		List<String> prereqOrFeatures,
		List<String> prereqOrTerrains,
		List<String> replacements,
		int health,
		int happiness,
		int[] yieldChanges,
		int[] commerceChanges) {

	/** Defensive-copy the lists and normalize the effect arrays to their widths. */
	public HousingBuilding {
		prereqBonuses = copyOf(prereqBonuses);
		prereqBuildings = copyOf(prereqBuildings);
		prereqOrBuildings = copyOf(prereqOrBuildings);
		prereqOrFeatures = copyOf(prereqOrFeatures);
		prereqOrTerrains = copyOf(prereqOrTerrains);
		replacements = copyOf(replacements);
		yieldChanges = pad(yieldChanges, 3);
		commerceChanges = pad(commerceChanges, 4);
	}

	private static List<String> copyOf(List<String> l) {
		return l == null ? List.of() : List.copyOf(l);
	}

	private static int[] pad(int[] a, int len) {
		return a != null && a.length == len ? a : Arrays.copyOf(a == null ? new int[0] : a, len);
	}

	@Override
	public String toString() {
		return type + (prereqTech == null ? "" : " <- " + prereqTech);
	}
}
