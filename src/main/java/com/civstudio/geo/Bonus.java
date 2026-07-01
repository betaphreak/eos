package com.civstudio.geo;

import java.util.Arrays;
import java.util.List;

import com.civstudio.good.ResourceType;

/**
 * A Civ4 bonus resource — a discrete resource placed on a {@link
 * com.civstudio.settlement.Plot plot} (wheat, iron, gold, horse…), contributing an
 * additive Food/Production/Commerce yield change and belonging to a {@link
 * BonusClass}. Exported (the full set) from {@code data/civ4/CIV4BonusInfos.xml} by
 * {@link com.civstudio.geo.export.BonusExporter} into {@code /bonuses.json}, loaded
 * by {@link TerrainRegistry}. See {@code docs/plots.md}.
 * <p>
 * This is the Phase-0 data layer: the resource is parsed and indexed but not yet
 * placed on plots or read for yield. {@link #techReveal()}/{@link #techCityTrade()}
 * (tech gating), the health/happiness amenities and the latitude/plot-type
 * placement constraints are all stored but <b>dormant</b> in this cut.
 *
 * @param type                 the Civ4 type key (e.g. {@code BONUS_WHEAT})
 * @param bonusClass           its placement class ({@code <BonusClassType>})
 * @param yieldChanges         additive {@code [df, dp, dc]} the resource adds
 *                             (length 3)
 * @param techReveal           the tech that reveals it (stored, dormant), or
 *                             {@code null}
 * @param techCityTrade        the tech that enables trading it (dormant), or
 *                             {@code null}
 * @param health               health/amenity ({@code <iHealth>}, dormant)
 * @param happiness            happiness/amenity ({@code <iHappiness>}, dormant)
 * @param minLatitude          generation constraint — southernmost band
 *                             ({@code <iMinLatitude>})
 * @param maxLatitude          generation constraint — northernmost band
 *                             ({@code <iMaxLatitude>})
 * @param hills                may appear on a {@code HILL} plot ({@code <bHills>})
 * @param flatlands            may appear on a flat plot ({@code <bFlatlands>})
 * @param peaks                may appear on a {@code PEAK} plot ({@code <bPeaks>})
 * @param validTerrains        host terrains it may sit on (from
 *                             {@code <TerrainBooleans>})
 * @param validFeatures        host features it may sit under (from
 *                             {@code <FeatureBooleans>})
 * @param validFeatureTerrains terrains valid only when carrying a feature (from
 *                             {@code <FeatureTerrainBooleans>})
 */
public record Bonus(
		String type,
		BonusClass bonusClass,
		int[] yieldChanges,
		String techReveal,
		String techCityTrade,
		int health,
		int happiness,
		int minLatitude,
		int maxLatitude,
		boolean hills,
		boolean flatlands,
		boolean peaks,
		List<String> validTerrains,
		List<String> validFeatures,
		List<String> validFeatureTerrains) {

	/** Normalize yields to length 3 and copy the valid-type lists. */
	public Bonus {
		yieldChanges = Terrain.pad3(yieldChanges);
		validTerrains = validTerrains == null ? List.of() : List.copyOf(validTerrains);
		validFeatures = validFeatures == null ? List.of() : List.copyOf(validFeatures);
		validFeatureTerrains = validFeatureTerrains == null ? List.of()
				: List.copyOf(validFeatureTerrains);
	}

	/** A yield-change component by index (0 = food, 1 = production, 2 = commerce). */
	public int yieldChange(int i) {
		return yieldChanges[i];
	}

	/**
	 * The consumer-good category this resource supplies (e.g. a {@code CROP} bonus
	 * feeds {@link ResourceType#NECESSITY}), or {@code null} if
	 * its {@link BonusClass} is not a consumer good. Delegates to
	 * {@link BonusClass#resourceType()}.
	 */
	public ResourceType resourceType() {
		return bonusClass == null ? null : bonusClass.resourceType();
	}

	@Override
	public String toString() {
		return type + Arrays.toString(yieldChanges);
	}
}
