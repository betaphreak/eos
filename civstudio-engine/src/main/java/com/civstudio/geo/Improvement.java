package com.civstudio.geo;

import java.util.Arrays;
import java.util.List;

/**
 * A Civ4 improvement — the "building on a plot" an on-plot firm operates (a farm,
 * mine, cottage…), the third leg of a plot's yield after terrain and feature. The
 * firm type fixes the improvement (necessity = a {@code FARM}), and the
 * improvement's yield change is that firm's land-productivity bonus. Exported
 * (curated to the firm-building subset) from {@code data/civ4/CIV4ImprovementInfos.xml}
 * by {@link com.civstudio.geo.export.ImprovementExporter} into {@code
 * /improvements.json}, loaded by {@link TerrainRegistry}. See {@code docs/plots.md}.
 * <p>
 * {@link #prereqTech()} is stored but <b>tech-gating is deferred</b> — every
 * curated improvement is buildable regardless of researched tech in this cut.
 *
 * @param type                 the Civ4 type key (e.g. {@code IMPROVEMENT_FARM})
 * @param yieldChanges         the {@code [df, dp, dc]} the built improvement adds
 *                             (length 3)
 * @param prereqTech           the tech that unlocks it (stored, dormant), or
 *                             {@code null}
 * @param hillsMakesValid      buildable on a {@code HILL} plot
 *                             ({@code <bHillsMakesValid>})
 * @param freshWaterMakesValid buildable next to fresh water
 *                             ({@code <bFreshWaterMakesValid>})
 * @param validTerrains        terrains it may be built on (from
 *                             {@code <TerrainMakesValids>})
 * @param validFeatures        features it may be built on (from
 *                             {@code <FeatureMakesValids>})
 * @param buildCost            the work to raise it ({@code <iAdvancedStartCost>})
 * @param healthPercent        stored but dormant (e.g. {@code MINE} −50)
 * @param upgradeType          the improvement this <b>grows into</b> when worked
 *                             ({@code <ImprovementUpgrade>}) — the settlement ladder's
 *                             next rung (Cottage→Hamlet→Village→Town→Suburbs), or
 *                             {@code null} at the top of a chain. See
 *                             {@code docs/settlement-tiers.md}.
 * @param upgradeTime          turns of work to advance to {@link #upgradeType()}
 *                             ({@code <iUpgradeTime>}; Cottage 10, Hamlet 20, Village 30,
 *                             Town 40) — the growth cost the tier ladder reads
 * @param culture              culture the built improvement radiates ({@code <iCulture>};
 *                             Cottage 2 … Suburbs 40) — a per-tier weight
 * @param actsAsCity           a fort/settlement-like strongpoint ({@code <bActsAsCity>})
 * @param techYieldChanges     yields that switch on as techs are researched
 *                             ({@code <TechYieldChanges>}) — e.g. a Town gains commerce at
 *                             Printing Press / Economics; the classic cottage-line growth
 */
public record Improvement(
		String type,
		int[] yieldChanges,
		String prereqTech,
		boolean hillsMakesValid,
		boolean freshWaterMakesValid,
		List<String> validTerrains,
		List<String> validFeatures,
		int buildCost,
		int healthPercent,
		String upgradeType,
		int upgradeTime,
		int culture,
		boolean actsAsCity,
		List<TechYield> techYieldChanges) {

	/** Normalize yields to length 3 and copy the list fields. */
	public Improvement {
		yieldChanges = Terrain.pad3(yieldChanges);
		validTerrains = validTerrains == null ? List.of() : List.copyOf(validTerrains);
		validFeatures = validFeatures == null ? List.of() : List.copyOf(validFeatures);
		techYieldChanges = techYieldChanges == null ? List.of()
				: List.copyOf(techYieldChanges);
	}

	/**
	 * A yield bonus that switches on once {@code tech} is researched — the Civ4
	 * {@code <TechYieldChange>} (a {@code [df, dp, dc]} triple gained at a tech). The
	 * mechanism behind the cottage line's growth: a Village/Town keeps adding commerce as
	 * printing, economics and the like come in.
	 *
	 * @param tech   the tech that turns this yield on
	 * @param yields the {@code [food, production, commerce]} it then adds (length 3)
	 */
	public record TechYield(String tech, int[] yields) {
		/** Normalize the yield triple to length 3. */
		public TechYield {
			yields = Terrain.pad3(yields);
		}
	}

	/** A yield-change component by index (0 = food, 1 = production, 2 = commerce). */
	public int yieldChange(int i) {
		return yieldChanges[i];
	}

	@Override
	public String toString() {
		return type + Arrays.toString(yieldChanges);
	}
}
