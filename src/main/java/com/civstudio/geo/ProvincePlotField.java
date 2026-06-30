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
 * <b>This phase</b> assembles three stages: the relief ({@link PlotType}
 * flat/hill/peak) from the C2C-ported {@link ReliefGenerator} (spatially clustered
 * ranges), the ground {@link Terrain} from the province's climate-weighted pool
 * ({@link TerrainGenerator#next}), and the wild {@link Feature} from the C2C-ported
 * {@link FeatureGenerator} (water-seeded forest/jungle) plus river <b>flood
 * plains</b> (flat, riverside). The remaining C2C per-tile stages — the
 * temperature-driven terrain refinement and resource placement — are staged for the
 * next slices.
 */
public final class ProvincePlotField {

	/**
	 * One generated plot: its absolute raster position, whether it carried a river
	 * pixel, its terrain, its relief, and (later) its wild feature.
	 *
	 * @param x        absolute raster x
	 * @param y        absolute raster y
	 * @param river    whether a river pixel fell on this plot
	 * @param terrain  the ground (from the climate pool)
	 * @param plotType the relief (flat/hill/peak; from {@link ReliefGenerator})
	 * @param feature  the wild feature, or {@code null} (feature growth is a later phase)
	 */
	public record ProvincePlot(int x, int y, boolean river, Terrain terrain,
			PlotType plotType, Feature feature) {
	}

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
		Feature[] vegetation = FeatureGenerator.generate(mask, climate, registry, rng);
		Feature floodPlains = registry.feature("FEATURE_FLOOD_PLAINS");

		int w = mask.width(), h = mask.height();
		List<ProvincePlot> out = new ArrayList<>(mask.landCount());
		for (int ly = 0; ly < h; ly++) {
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				int idx = ly * w + lx;
				Terrain terrain = terrainGen.next(rng);
				PlotType plotType = relief[idx];
				boolean river = mask.isRiver(lx, ly);
				// flood plains take a flat riverside plot; otherwise the grown vegetation
				Feature feature = (river && plotType == PlotType.FLAT && floodPlains != null)
						? floodPlains
						: vegetation[idx];
				out.add(new ProvincePlot(mask.originX() + lx, mask.originY() + ly,
						river, terrain, plotType, feature));
			}
		}
		return new ProvincePlotField(province, out);
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
