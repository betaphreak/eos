package com.civstudio.geo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.civstudio.util.Rng;

/**
 * The plot field of one province: its real land pixels as plots, generated from
 * the province's {@link ProvinceMask silhouette} and climate. Each land cell of
 * the mask becomes one {@link ProvincePlot} (1 raster pixel = 1 plot), carrying
 * its raster position, river flag, terrain, and relief. Generated lazily — the
 * first time a province is needed — and shared by all the settlements that found
 * into the province (which claim plots from it; ownership/claiming land in a later
 * phase). See {@code docs/province-plots.md}.
 * <p>
 * <b>This phase</b> assembles four stages: the relief ({@link PlotType}
 * flat/hill/peak) from the C2C-ported {@link ReliefGenerator} (spatially clustered
 * ranges), the ground {@link Terrain} from the province's climate-weighted pool
 * ({@link TerrainGenerator#next}), the wild {@link Feature} from the C2C-ported
 * {@link FeatureGenerator} (water-seeded forest/jungle) plus river <b>flood
 * plains</b> (flat, riverside), and the {@link Bonus} resource from {@link
 * BonusGenerator} (placed by each bonus's own terrain/feature/relief/latitude
 * constraints). The one remaining C2C stage — the temperature-driven terrain
 * refinement — is deferred (see {@code docs/province-plots.md}).
 */
public final class ProvincePlotField {

	/**
	 * One generated plot: its {@link PlotGeo raster-derived scalars} (position, river, elevation,
	 * sea mask) plus its terrain, relief, wild feature and resource. The positional/raster
	 * accessors delegate to {@link #geo()} — see {@link PlotGeo} for why they are grouped.
	 *
	 * @param geo      the raster-derived scalars (position, river code, elevation, sea mask)
	 * @param terrain  the ground (from the climate pool)
	 * @param plotType the relief (flat/hill/peak; from {@link ReliefGenerator})
	 * @param feature  the wild feature, or {@code null}
	 * @param bonus    the resource on this plot, or {@code null}
	 */
	public record ProvincePlot(PlotGeo geo, Terrain terrain, PlotType plotType, Feature feature, Bonus bonus) {

		/** Absolute raster x. */
		public int x() {
			return geo.x();
		}

		/** Absolute raster y. */
		public int y() {
			return geo.y();
		}

		/** The packed river code (0 = none; see {@link ProvinceRaster#classifyRiver}). */
		public int riverCode() {
			return geo.river();
		}

		/** The real heightmap elevation (0..255). */
		public int elevation() {
			return geo.elevation();
		}

		/** The 8-bit sea mask (edges + corners; see {@code docs/coastlines.md}). */
		public int coast() {
			return geo.coast();
		}

		/** Whether a river runs through this plot (any non-zero {@link #riverCode()}). */
		public boolean river() {
			return geo.river() != 0;
		}
	}

	/**
	 * Per-cell chance a terrain-implied feature is placed on an otherwise-bare cell —
	 * a wetland is its biome so it places densely ({@link #SWAMP_CHANCE}), while a
	 * desert/savanna feature places sparsely ({@link #SPARSE_CHANCE}) so the ground
	 * reads as a mix of bare and featured rather than a uniform blanket.
	 */
	private static final double SWAMP_CHANCE = 0.85;
	private static final double SPARSE_CHANCE = 0.35;

	private final Province province;
	private final List<ProvincePlot> plots;

	private ProvincePlotField(Province province, List<ProvincePlot> plots) {
		this.province = province;
		this.plots = plots;
	}

	/**
	 * Generate a province's plot field deterministically off the terrain {@code rng}.
	 * Relief is generated first (consuming the stream for its clustering), then one
	 * terrain is drawn per land cell in row-major order — so the same {@code (rng
	 * seed, province)} yields the same field.
	 *
	 * @param province the province to build a field for
	 * @param registry the curated terrain/feature definitions
	 * @param raster   the raster reader (supplies the province mask)
	 * @param rng      the dedicated terrain stream (salted apart from the economy)
	 * @return the province's plot field
	 */
	public static ProvincePlotField generate(Province province, TerrainRegistry registry,
			ProvinceRaster raster, Rng rng) throws IOException {
		ProvinceMask mask = raster.mask(province.id());
		PlotType[] relief = ReliefGenerator.generate(mask, ReliefGenerator.Params.forProvince(province), rng);
		TerrainGenerator terrainGen = new TerrainGenerator(registry, province.climate(),
				province.winter(), province.monsoon());
		ClimateProfile climate = ClimateProfile.of(province);
		double treeCover = treeCover(mask);
		Feature[] vegetation = FeatureGenerator.generate(mask, climate, treeCover, registry, rng);
		Feature floodPlains = registry.feature("FEATURE_FLOOD_PLAINS");
		List<Bonus> bonuses = registry.bonuses();

		int w = mask.width(), h = mask.height();
		List<ProvincePlot> out = new ArrayList<>(mask.landCount());
		for (int ly = 0; ly < h; ly++) {
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				int idx = ly * w + lx;
				// ground the plot on the real terrain.bmp where the map classifies it,
				// keeping a climate-weighted draw as the fallback for water/unmapped
				// pixels (always drawn, so the rng stream stays deterministic per seed)
				Terrain drawn = terrainGen.next(rng);
				Terrain mapped = MapTerrainCodec.ground(mask.terrainIndex(lx, ly), registry);
				Terrain terrain = mapped != null ? mapped : drawn;
				// relief is the rougher of the generator's clustered ranges and the real
				// hill/mountain the terrain.bmp palette encodes, so map mountains survive
				PlotType plotType = rougher(relief[idx], MapTerrainCodec.relief(mask.terrainIndex(lx, ly)));
				int riverCode = mask.riverCode(lx, ly);
				boolean river = riverCode != 0;
				// the wild feature, in priority: flood plains on a valid flat riverside
				// plot; else, where vegetation grew, the map's tree class (forest/jungle/
				// …); else a sparse terrain-implied feature (swamp/cactus/…). Every choice
				// is validity-gated against the plot's terrain/relief (see featureFor).
				Feature feature = featureFor(terrain, plotType, river, vegetation[idx] != null,
						mask.treeIndex(lx, ly), mask.terrainIndex(lx, ly), climate, floodPlains,
						registry, rng);
				Bonus bonus = BonusGenerator.pick(terrain, plotType, feature,
						province.latitude(), bonuses, rng);
				// elevation is a pure heightmap lookup (no rng), so adding it leaves the
				// terrain/relief/feature/bonus draws — and thus the field — otherwise identical
				int elevation = mask.elevation(lx, ly);
				int coast = mask.coast(lx, ly);
				PlotGeo geo = new PlotGeo(mask.originX() + lx, mask.originY() + ly, riverCode, elevation, coast);
				out.add(new ProvincePlot(geo, terrain, plotType, feature, bonus));
			}
		}
		return new ProvincePlotField(province, out);
	}

	// the wooded fraction of the province's land from the real trees.bmp overlay
	// ({@link MapTerrainCodec#isWoody}), the density signal the feature stage spreads
	// from; -1 when the overlay is absent (no tree index on any land cell), so the
	// feature stage falls back to the climate humidity.
	private static double treeCover(ProvinceMask mask) {
		int land = 0, wooded = 0;
		boolean any = false;
		for (int ly = 0; ly < mask.height(); ly++)
			for (int lx = 0; lx < mask.width(); lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				land++;
				int ti = mask.treeIndex(lx, ly);
				if (ti >= 0) {
					any = true;
					if (MapTerrainCodec.isWoody(ti))
						wooded++;
				}
			}
		return (any && land > 0) ? wooded / (double) land : -1;
	}

	// the rougher of two reliefs (FLAT < HILL < PEAK), by enum ordinal — used to let
	// a real map mountain/hill override the generator's flatland without flattening
	// the generator's own clustered ranges.
	private static PlotType rougher(PlotType a, PlotType b) {
		return b.ordinal() > a.ordinal() ? b : a;
	}

	// the plot's wild feature, in priority order, every candidate validity-gated
	// against the plot's terrain/relief so no invalid terrain/feature combo is placed
	// (which would mis-gate bonus eligibility and yields):
	//  1. flood plains on a valid flat riverside plot;
	//  2. where vegetation grew (FeatureGenerator's water-seeded spread), the feature
	//     the map's tree class implies — forest/jungle/oasis/savanna/swamp — falling
	//     back to the climate kind / forest / savanna for an invalid host;
	//  3. otherwise a sparse terrain-implied feature (swamp on marsh, cactus on desert).
	private static Feature featureFor(Terrain terrain, PlotType relief, boolean river,
			boolean vegetated, int treeIdx, int terrainIdx, ClimateProfile climate,
			Feature floodPlains, TerrainRegistry reg, Rng rng) {
		if (river && relief == PlotType.FLAT && valid(floodPlains, terrain, relief))
			return floodPlains;
		if (vegetated)
			return vegetationFeature(terrain, relief, treeIdx, climate, reg);
		return terrainDrivenFeature(terrain, relief, terrainIdx, reg, rng);
	}

	// the feature for a vegetated cell: the first of {the map tree class, the climate
	// kind, forest, savanna} that is a valid host of this terrain/relief — so a hot
	// province whose real ground is plains (where jungle cannot grow) still greens as
	// forest/savanna rather than placing an invalid jungle.
	private static Feature vegetationFeature(Terrain terrain, PlotType relief, int treeIdx,
			ClimateProfile climate, TerrainRegistry reg) {
		String[] keys = { MapTerrainCodec.treeFeatureKey(treeIdx),
				climate.isHot() ? "FEATURE_JUNGLE" : "FEATURE_FOREST",
				"FEATURE_FOREST", "FEATURE_SAVANNA" };
		for (String key : keys) {
			Feature f = key == null ? null : reg.feature(key);
			if (valid(f, terrain, relief))
				return f;
		}
		return null;
	}

	// the sparse terrain-implied feature for a bare cell (swamp/cactus/savanna),
	// validity-gated; always consumes exactly one rng draw so the stream stays
	// deterministic whether or not a feature lands. A wetland places densely (it is
	// the biome), the dry features sparsely (a mix of bare and featured ground).
	private static Feature terrainDrivenFeature(Terrain terrain, PlotType relief,
			int terrainIdx, TerrainRegistry reg, Rng rng) {
		double r = rng.uniform();
		String key = MapTerrainCodec.terrainFeatureKey(terrainIdx);
		Feature f = key == null ? null : reg.feature(key);
		if (!valid(f, terrain, relief))
			return null;
		double chance = "FEATURE_SWAMP".equals(f.type()) ? SWAMP_CHANCE : SPARSE_CHANCE;
		return r < chance ? f : null;
	}

	// whether a feature may sit on a plot: a valid host terrain, and flat ground when
	// the feature requires it (the river requirement is handled by the caller).
	private static boolean valid(Feature f, Terrain terrain, PlotType relief) {
		if (f == null)
			return false;
		if (f.requiresFlatlands() && relief != PlotType.FLAT)
			return false;
		return f.validTerrains().contains(terrain.type());
	}

	/** The province this field belongs to. */
	public Province province() {
		return province;
	}

	/** The generated plots (one per province land pixel), row-major. */
	public List<ProvincePlot> plots() {
		return plots;
	}

	/** The number of plots (== the province's land-pixel count). */
	public int size() {
		return plots.size();
	}
}
