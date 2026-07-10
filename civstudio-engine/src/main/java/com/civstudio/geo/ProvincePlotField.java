package com.civstudio.geo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * ranges); the ground {@link Terrain} from the <b>real {@code terrain.bmp}</b> ({@link
 * MapTerrainCodec#ground}), with the province's climate-weighted pool ({@link
 * TerrainGenerator#next}) as the fallback for unmapped pixels; the wild {@link Feature}
 * from the C2C-ported {@link FeatureGenerator} (the {@code addFeatures} seed-and-spread
 * — per-cell weighted jungle/forest/swamp with peak-seeding and the jungle→forest cold
 * substitution), plus river <b>flood plains</b> and the generic <b>appearance-probability
 * scatter</b> ({@link #featureFor}); and the {@link Bonus} resource from {@link
 * BonusGenerator} (placed by each bonus's own terrain/feature/relief/latitude
 * constraints). The C2C stage's <b>terrain rewriting</b> (jungle greening desert, …) is
 * deliberately not applied — eos's ground is the real EU4 map and is left intact (see
 * {@code docs/c2c-generator-port.md} §2, "feature consequences only").
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
	// special-terrain province types → a weighted terrain pool their plots generate from,
	// replacing the raster's per-plot palette (see docs/underworld.md §special terrains). Each pool
	// is dominated by the type's signature terrain but mixes in a little compatible ground so the
	// province reads as generated, not a flat monolith. The four underground types share the cavern
	// pool (and flatten relief to a floor); the surface terrains keep their relief and trees.
	private static final Map<ProvinceType, Map<String, Double>> SPECIAL_POOL = Map.ofEntries(
			Map.entry(ProvinceType.CAVERN, cavernPool()),
			Map.entry(ProvinceType.DWARVEN_HOLD, cavernPool()),
			Map.entry(ProvinceType.DWARVEN_HOLD_SURFACE, cavernPool()),
			Map.entry(ProvinceType.DWARVEN_ROAD, cavernPool()),
			Map.entry(ProvinceType.ANCIENT_FOREST,
					Map.of("TERRAIN_ANCIENT_FOREST", 7.0, "TERRAIN_GRASSLAND", 1.0, "TERRAIN_LUSH", 1.0)),
			Map.entry(ProvinceType.GLADEWAY,
					Map.of("TERRAIN_GLADEWAY", 7.0, "TERRAIN_GRASSLAND", 2.0)),
			Map.entry(ProvinceType.FEY_GLADEWAY,
					Map.of("TERRAIN_FEY_GLADEWAY", 7.0, "TERRAIN_LUSH", 2.0)),
			Map.entry(ProvinceType.BLOODGROVES,
					Map.of("TERRAIN_BLOODGROVES", 7.0, "TERRAIN_GRASSLAND", 1.0)),
			Map.entry(ProvinceType.MUSHROOM_FOREST,
					Map.of("TERRAIN_MUSHROOM_FOREST", 7.0, "TERRAIN_MARSH", 1.0, "TERRAIN_MUDDY", 1.0)),
			Map.entry(ProvinceType.SHADOW_SWAMP,
					Map.of("TERRAIN_SHADOW_SWAMP", 6.0, "TERRAIN_MARSH", 2.0, "TERRAIN_MUDDY", 1.0)),
			Map.entry(ProvinceType.GLACIER,
					Map.of("TERRAIN_GLACIER", 6.0, "TERRAIN_PERMAFROST", 2.0, "TERRAIN_TUNDRA", 1.0)));

	private static Map<String, Double> cavernPool() {
		return Map.of("TERRAIN_CAVERN", 8.0, "TERRAIN_ROCKY", 1.0);
	}

	// special surface terrains whose signature feature the trees.bmp density signal misses (they
	// are terrain-override provinces, not painted wooded/marshy): the forests carry FEATURE_FOREST,
	// the shadow swamp FEATURE_SWAMP, over most of their plots (see SPECIAL_FEATURE_COVER).
	private static final Map<ProvinceType, String> SPECIAL_FEATURE = Map.of(
			ProvinceType.ANCIENT_FOREST, "FEATURE_FOREST",
			ProvinceType.GLADEWAY, "FEATURE_FOREST",
			ProvinceType.FEY_GLADEWAY, "FEATURE_FOREST",
			ProvinceType.BLOODGROVES, "FEATURE_FOREST",
			ProvinceType.SHADOW_SWAMP, "FEATURE_SWAMP");
	// fraction of a special-terrain province's (non-peak) plots that carry its signature feature
	private static final double SPECIAL_FEATURE_COVER = 0.90;

	private static final double SWAMP_CHANCE = 0.85;
	private static final double SPARSE_CHANCE = 0.35;

	/**
	 * How far (Chebyshev pixels) from dry land a sea/lake province still gets water plots — its
	 * <b>coastal shelf</b>. {@code 1} = COAST (touching land), {@code 2..SHELF_MAX} = SEA;
	 * cells further out get no plot (the web draws them as the open-sea ripple). This is where
	 * the Civ4 sea bonuses live, so it bounds the added plot count to a near-shore ring. See
	 * {@code docs/coastlines.md}.
	 */
	private static final int SHELF_MAX = 3;

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
		// sea/lake provinces grow a coastal-shelf water field instead of the land field; every
		// other type (LAND, and IMPASSABLE wasteland) goes through the land path below
		if (province.type() == ProvinceType.SEA || province.type() == ProvinceType.LAKE)
			return generateWater(province, registry, raster, rng);
		ProvinceMask mask = raster.mask(province.id());
		int w = mask.width(), h = mask.height();
		PlotType[] relief = ReliefGenerator.generate(mask, ReliefGenerator.Params.forProvince(province), rng);
		TerrainGenerator terrainGen = new TerrainGenerator(registry, province.climate(),
				province.winter(), province.monsoon());
		ClimateProfile climate = ClimateProfile.of(province);

		// ground each land cell first, so the feature stage can read the whole terrain +
		// relief grid (it seeds off peaks and chooses per-cell by terrain category). The
		// ground is the real terrain.bmp where the map classifies it, with a
		// climate-weighted draw as the fallback for unmapped pixels (always drawn, in
		// row-major order, so the rng stream stays deterministic per seed); the composed
		// relief is the rougher of the generator's clustered ranges and the map's
		// hill/mountain, so real mountains survive.
		Terrain[] ground = new Terrain[w * h];
		PlotType[] composed = new PlotType[w * h];
		for (int ly = 0; ly < h; ly++)
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				int idx = ly * w + lx;
				Terrain drawn = terrainGen.next(rng);
				Terrain mapped = MapTerrainCodec.ground(mask.terrainIndex(lx, ly), registry);
				ground[idx] = mapped != null ? mapped : drawn;
				composed[idx] = rougher(relief[idx], MapTerrainCodec.relief(mask.terrainIndex(lx, ly)));
			}

		// latitude climate: the imported terrain.bmp ignores latitude (it paints the far north green
		// grassland), so cool a normal LAND province's warm ground toward taiga/tundra/permafrost as
		// its latitude — and its winter severity — rise. Ported from the C2C planet generator (see
		// LatitudeClimate); ramped by coldFraction so the temperate zone is untouched. Special-terrain
		// provinces (glacier/forests/caves) keep their biome (handled below), and mountains stay.
		if (province.type() == ProvinceType.LAND) {
			// a region's relative temperature modifier shifts its effective temperature — e.g. the
			// Yarikhoi run warm, a temperate pocket in the deep north — so the cold override (and thus
			// permafrost/taiga) recedes or advances with it. See LatitudeClimate#regionTempOffset.
			double temp = LatitudeClimate.effectiveTemperature(province.latitude(), province.winter())
					+ LatitudeClimate.regionTempOffset(province);
			double coldFrac = LatitudeClimate.coldFraction(temp);
			if (coldFrac > 0) {
				TerrainGenerator coldGen = new TerrainGenerator(registry, LatitudeClimate.coldPool(temp));
				for (int idx = 0; idx < ground.length; idx++)
					if (ground[idx] != null && LatitudeClimate.isWarm(ground[idx])
							&& rng.uniform() < coldFrac)
						ground[idx] = coldGen.next(rng);
			}
		}

		// special-terrain provinces generate their ground from a type-specific weighted pool
		// (a cavern floor, an ancient forest, a glacier, …) instead of the raster's per-plot
		// palette — the raster reads them as generic mountain/forest, so membership drives the
		// ground. Done before the feature/bonus stages so those read the real ground (grass-based
		// terrains still spawn trees; underground folds to PyTerrain.OTHER → bare). The underground
		// types also flatten relief to a walkable floor; surface terrains keep their hills/peaks.
		// See docs/underworld.md.
		Map<String, Double> specialPool = SPECIAL_POOL.get(province.type());
		if (specialPool != null) {
			TerrainGenerator specialGen = new TerrainGenerator(registry, specialPool);
			boolean flatten = province.isUnderground();
			for (int idx = 0; idx < ground.length; idx++)
				if (ground[idx] != null) { // land cells only (water/off-mask stay null)
					ground[idx] = specialGen.next(rng);
					if (flatten)
						composed[idx] = PlotType.FLAT;
				}
		}

		// De-speckle the ground into coherent regions. Terrain is sampled 1 raster pixel = 1 plot, and
		// the latitude-cooling and special-pool passes above draw each cell independently — so the raw
		// ground is salt-and-pepper (an isolated plot of a different terrain in nearly every cell), which
		// reads as a hard grid at the deepest city-builder zoom no matter how the web blends plot edges.
		// A few passes of majority (mode) smoothing coalesce the speckle into natural patches while
		// keeping each terrain's overall share. Reads a per-pass snapshot (order-independent) and consumes
		// NO rng, so the deterministic, row-major terrain draws above are untouched (see the seed contract).
		despeckle(ground, w, h);

		// the C2C-ported feature seed-and-spread: the per-cell vegetation intent
		// (jungle/forest/swamp or bare), which this loop validity-gates below
		double treeCover = treeCover(mask);
		Feature[] vegetation = FeatureGenerator.generate(mask, ground, composed,
				province.latitude(), climate, treeCover, registry, rng);
		Feature floodPlains = registry.feature("FEATURE_FLOOD_PLAINS");
		List<Bonus> bonuses = registry.bonuses();

		// resolve the wild feature of every land cell into a grid, in priority: flood
		// plains on a valid flat riverside plot; else the C2C vegetation pick
		// (validity-gated, with the real tree-class fallback for an invalid host); else a
		// sparse terrain-implied feature (swamp/cactus/…) or the appearance scatter. Every
		// choice is validity-gated (see featureFor). A grid (not the final plot) so the
		// oasis pass can score a cell's neighbours before the bonuses read the feature.
		Feature[] feature = new Feature[w * h];
		List<int[]> cells = new ArrayList<>(mask.landCount());
		for (int ly = 0; ly < h; ly++)
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				int idx = ly * w + lx;
				cells.add(new int[] { lx, ly });
				feature[idx] = featureFor(ground[idx], composed[idx], mask.riverCode(lx, ly) != 0,
						vegetation[idx], mask.treeIndex(lx, ly), mask.terrainIndex(lx, ly),
						climate, floodPlains, registry, rng);
			}
		// special forest/swamp terrains: the C2C stage above leaves them bare (no trees.bmp
		// coverage), so stamp their signature feature over ~90% of non-peak plots. See
		// docs/underworld.md.
		String specialFeatureKey = SPECIAL_FEATURE.get(province.type());
		if (specialFeatureKey != null) {
			Feature specialFeature = registry.feature(specialFeatureKey);
			for (int ly = 0; ly < h; ly++)
				for (int lx = 0; lx < w; lx++) {
					if (!mask.isLand(lx, ly))
						continue;
					int idx = ly * w + lx;
					feature[idx] = composed[idx] != PlotType.PEAK
							&& rng.uniform() < SPECIAL_FEATURE_COVER ? specialFeature : null;
				}
		}
		// C2C oasis scoring (slice 5, addFeatures L3076–3135): scatters oases across the
		// best inland-desert cells, scored by their surroundings — a feature-only pass,
		// so it never rewrites the real ground
		placeOases(mask, ground, composed, feature, registry.feature("FEATURE_OASIS"), rng);
		// C2C bonus placement (slice 8): a per-province pass, resources laid in placement
		// order at target densities with group spacing (see BonusGenerator)
		Bonus[] bonusGrid = BonusGenerator.place(w, h, cells, ground, composed, feature,
				province.latitude(), bonuses, rng, false);

		List<ProvincePlot> out = new ArrayList<>(mask.landCount());
		for (int ly = 0; ly < h; ly++) {
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				int idx = ly * w + lx;
				Terrain terrain = ground[idx];
				PlotType plotType = composed[idx];
				int riverCode = mask.riverCode(lx, ly);
				Bonus bonus = bonusGrid[idx];
				// elevation is a pure heightmap lookup (no rng), so adding it leaves the
				// terrain/relief/feature/bonus draws — and thus the field — otherwise identical
				int elevation = mask.elevation(lx, ly);
				int coast = mask.coast(lx, ly);
				PlotGeo geo = new PlotGeo(mask.originX() + lx, mask.originY() + ly, riverCode, elevation, coast);
				out.add(new ProvincePlot(geo, terrain, plotType, feature[idx], bonus));
			}
		}
		return new ProvincePlotField(province, out);
	}

	/**
	 * The coastal-shelf water field of a sea/lake province: each of its own water cells within
	 * {@link #SHELF_MAX} pixels of dry land becomes a {@code FLAT} water plot — COAST (touching
	 * land) or SEA further out, in the province's climate variant ({@link MapTerrainCodec#water})
	 * — carrying a sea resource from the same {@link BonusGenerator} the land uses (fish, crab,
	 * whale, pearls, … place themselves by the water terrain). Deep-water cells beyond the shelf
	 * get no plot, so the added count is a near-shore ring and the web keeps drawing open water as
	 * the sea ripple. Cold water carries {@code FEATURE_ICE} (the C2C polar-cap + drift-ice model).
	 * Deterministic off the same per-province terrain stream as the land path — an ice draw per
	 * cell only where ice can form, then the bonus draws, in row-major order. See {@code
	 * docs/coastlines.md}.
	 */
	private static ProvincePlotField generateWater(Province province, TerrainRegistry registry,
			ProvinceRaster raster, Rng rng) throws IOException {
		ProvinceMask mask = raster.mask(province.id());
		boolean lake = province.type() == ProvinceType.LAKE;
		List<Bonus> bonuses = registry.bonuses();
		double latitude = province.latitude();
		// FEATURE_ICE covers polar water — sea ice thickening toward the pole. A province is one
		// climate band (its latitude), so either all its water is polar (ice draws) or none is;
		// that keeps the per-cell draw order deterministic. Absent registry ice → no ice, no draws.
		Feature ice = registry.feature("FEATURE_ICE");
		// C2C sea ice (addFeatures §3, L2746–2780): a polar-cap coverage that thickens
		// toward the pole, combined with temperature-driven drift ice on cold open water.
		// A province is one climate band (its latitude), so either all its water ices or
		// none does, keeping the per-cell draw order deterministic. Absent registry ice →
		// no ice, no draws. With the default temperature tent the 0 °C isotherm sits at
		// ~67°, where the water terrain already bands to its polar variant (the only host
		// FEATURE_ICE lists), so the drift-ice term coincides with the polar cap here — it
		// generalises the mechanism should the climate model change.
		double temp = ClimateProfile.pyTemperature(latitude);
		final double ICE_ON_WATER = 0.5;
		double driftIce = temp < -40 ? ICE_ON_WATER * 2   // L2766–2780
				: temp < -25 ? ICE_ON_WATER
				: temp < -10 ? ICE_ON_WATER / 2
				: temp < -5 ? ICE_ON_WATER / 3
				: temp < 0 ? ICE_ON_WATER / 4
				: 0;
		double polarCap = Math.abs(latitude) >= 66.0   // matches MapTerrainCodec's polar band
				? Math.min(0.9, 0.15 + (Math.abs(latitude) - 66.0) / 16.0 * 0.75) : 0;
		double iceCover = Math.min(0.95, Math.max(polarCap, driftIce));
		boolean anyIce = ice != null && iceCover > 0;
		int w = mask.width(), h = mask.height();
		// first pass: assemble the shelf cells and their terrain/ice grids (the ice draw
		// per cell, in row-major order, so the stream stays deterministic)
		Terrain[] terrainGrid = new Terrain[w * h];
		Feature[] featureGrid = new Feature[w * h];
		List<int[]> cells = new ArrayList<>();
		for (int ly = 0; ly < h; ly++)
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly)) // the province's own water pixels
					continue;
				int dist = mask.landDist(lx, ly);
				if (dist < 1 || dist > SHELF_MAX) // keep only the near-shore shelf ring
					continue;
				Terrain terrain = MapTerrainCodec.water(lake, dist, latitude, registry);
				if (terrain == null) // registry lacks the shelf water terrains — no water plots
					continue;
				int idx = ly * w + lx;
				terrainGrid[idx] = terrain;
				// sea ice, validity-gated to the polar water terrain (freshwater lakes get none)
				featureGrid[idx] = anyIce && rng.uniform() < iceCover
						&& ice.validTerrains().contains(terrain.type()) ? ice : null;
				cells.add(new int[] { lx, ly });
			}
		// sea resources (fish/crab/whale/…) via the same per-province placement pass (relief-free)
		Bonus[] bonusGrid = BonusGenerator.place(w, h, cells, terrainGrid, null, featureGrid,
				latitude, bonuses, rng, true);

		List<ProvincePlot> out = new ArrayList<>(cells.size());
		for (int[] c : cells) {
			int lx = c[0], ly = c[1], idx = ly * w + lx;
			PlotGeo geo = new PlotGeo(mask.originX() + lx, mask.originY() + ly, 0,
					mask.elevation(lx, ly), mask.coast(lx, ly));
			out.add(new ProvincePlot(geo, terrainGrid[idx], PlotType.FLAT, featureGrid[idx], bonusGrid[idx]));
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

	// number of majority-smoothing passes over the ground grid (see the call site). More passes
	// grow patches larger; 4 dissolves the salt-and-pepper without erasing genuine terrain regions.
	private static final int DESPECKLE_PASSES = 3;

	// Majority (mode) smoothing of the per-cell ground terrain: each land cell adopts the terrain most
	// common among its 8 land neighbours when that plurality outnumbers the cell's own terrain there —
	// i.e. an isolated speck surrenders to the region around it, while a cell inside a genuine region
	// keeps its terrain (it is the local plurality). Iterated a few times so specks coalesce into
	// patches. Tallied by Terrain.type() (records hold an int[] yields, so keying by the type string is
	// safe regardless of instance interning). Reads a snapshot per pass so the result is independent of
	// scan order, and never touches the rng — the deterministic terrain draws already happened above.
	private static void despeckle(Terrain[] ground, int w, int h) {
		for (int pass = 0; pass < DESPECKLE_PASSES; pass++) {
			Terrain[] src = ground.clone();
			for (int ly = 0; ly < h; ly++)
				for (int lx = 0; lx < w; lx++) {
					int idx = ly * w + lx;
					Terrain self = src[idx];
					if (self == null)
						continue; // water / off-mask cell
					Map<String, Integer> counts = new HashMap<>();
					Map<String, Terrain> byType = new HashMap<>();
					for (int dy = -1; dy <= 1; dy++)
						for (int dx = -1; dx <= 1; dx++) {
							if (dx == 0 && dy == 0)
								continue;
							int nx = lx + dx, ny = ly + dy;
							if (nx < 0 || nx >= w || ny < 0 || ny >= h)
								continue;
							Terrain t = src[ny * w + nx];
							if (t == null)
								continue;
							counts.merge(t.type(), 1, Integer::sum);
							byType.putIfAbsent(t.type(), t);
						}
					int selfCount = counts.getOrDefault(self.type(), 0);
					String bestType = self.type();
					int best = selfCount;
					for (Map.Entry<String, Integer> e : counts.entrySet())
						if (e.getValue() > best) { // strict: ties keep the current terrain (stable)
							best = e.getValue();
							bestType = e.getKey();
						}
					if (!bestType.equals(self.type()))
						ground[idx] = byType.get(bestType);
				}
		}
	}

	// the plot's wild feature, in priority order, every candidate validity-gated
	// against the plot's terrain/relief so no invalid terrain/feature combo is placed
	// (which would mis-gate bonus eligibility and yields):
	//  1. flood plains on a valid flat riverside plot;
	//  2. the C2C vegetation pick (FeatureGenerator's seed-and-spread) where it grew,
	//     used directly if a valid host of this terrain/relief, else falling back to the
	//     map's tree class / climate kind / forest / savanna (vegetationFeature) — this
	//     is where the curated eos feature/terrain rules override the C2C intent;
	//  3. otherwise a sparse terrain-implied feature (swamp on marsh, cactus on desert).
	private static Feature featureFor(Terrain terrain, PlotType relief, boolean river,
			Feature vegetation, int treeIdx, int terrainIdx, ClimateProfile climate,
			Feature floodPlains, TerrainRegistry reg, Rng rng) {
		Feature feature;
		if (river && relief == PlotType.FLAT && valid(floodPlains, terrain, relief))
			feature = floodPlains;
		else if (vegetation != null)
			feature = valid(vegetation, terrain, relief) ? vegetation
					: vegetationFeature(terrain, relief, treeIdx, climate, reg);
		else
			feature = terrainDrivenFeature(terrain, relief, terrainIdx, reg, rng);
		// C2C generic appearance-probability scatter (slice 4, addFeatures L3168): any
		// plot still bare rolls each curated feature's <iAppearance> — this is the only
		// path that places the rarer curated features (forest_ancient / bamboo /
		// very_tall_grass), which no seed-and-spread or terrain-implied rule reaches
		if (feature == null)
			feature = appearanceScatter(terrain, relief, river, reg, rng);
		return feature;
	}

	// C2C oasis scoring & placement (addFeatures L3076–3135). Two steps over the resolved
	// feature grid: (1) score every eligible inland-desert cell (a valid, flat, bare oasis
	// host that is neither riverside/fresh nor coastal) by its 8 neighbours — water and
	// wet/green/featured neighbours drag the score down, dry empty desert lifts it; (2)
	// place an oasis on a random third of the positive-scoring candidates, skipping any
	// adjacent to an oasis already placed, aborting after 20 such misses. Mutates {@code
	// feature} in place; a feature-only pass (no ground rewrite). Consumes one rng draw
	// per candidate placement, so the stream stays deterministic.
	private static void placeOases(ProvinceMask mask, Terrain[] ground, PlotType[] relief,
			Feature[] feature, Feature oasis, Rng rng) {
		if (oasis == null)
			return;
		int w = mask.width(), h = mask.height();
		List<Integer> candidates = new ArrayList<>();
		for (int ly = 0; ly < h; ly++)
			for (int lx = 0; lx < w; lx++) {
				if (!mask.isLand(lx, ly))
					continue;
				int idx = ly * w + lx;
				// eligible = a bare, valid flat oasis host (desert-category), not fresh/riverside
				// and not coastal — the dry interior the oasis punctuates
				if (feature[idx] != null || !valid(oasis, ground[idx], relief[idx])
						|| mask.isRiver(lx, ly) || coastal(mask, lx, ly))
					continue;
				int score = 10;
				for (int[] d : DIRS8) {
					int nx = lx + d[0], ny = ly + d[1];
					if (!mask.isLand(nx, ny))
						continue; // out-of-province / open water neighbour — unreadable, skip
					int ni = ny * w + nx;
					if (mask.isRiver(nx, ny))
						score -= 40;       // a river cell is both fresh and riverside (−20 each)
					if (relief[ni] == PlotType.PEAK)
						score -= 2;
					Feature nf = feature[ni];
					if (nf == null)
						score += 1;
					else if ("FEATURE_JUNGLE".equals(nf.type()))
						score -= 5;
					else if ("FEATURE_FOREST".equals(nf.type()))
						score -= 3;
					else if ("FEATURE_FLOOD_PLAINS".equals(nf.type()))
						score -= 20;
					score += switch (PyTerrain.of(ground[ni])) {
						case DESERT -> 1;
						case PLAINS -> -2;
						case GRASS -> -6;
						case TUNDRA, SNOW -> -20;   // script terrainTundra / terrainSnow
						default -> 0;
					};
					if (score < 0)
						break; // the script bails as soon as the neighbourhood is hostile
				}
				if (score > 0)
					candidates.add(idx);
			}
		int place = candidates.size() / 3;
		int misses = 0;
		for (int i = 0; i < place && !candidates.isEmpty(); i++) {
			int c = candidates.remove(rng.uniform(candidates.size())); // random, without replacement
			int cx = c % w, cy = c / w;
			boolean nearOasis = false;
			for (int[] d : DIRS8) {
				int nx = cx + d[0], ny = cy + d[1];
				if (mask.isLand(nx, ny) && feature[ny * w + nx] == oasis) {
					nearOasis = true;
					break;
				}
			}
			if (nearOasis) {
				if (++misses > 20)
					break;
				continue;
			}
			feature[c] = oasis;
		}
	}

	// the 8 neighbour offsets (oasis scoring reads the full neighbourhood)
	private static final int[][] DIRS8 = {
			{ -1, 0 }, { -1, 1 }, { 0, 1 }, { 1, 1 }, { 1, 0 }, { 1, -1 }, { 0, -1 }, { -1, -1 } };

	// a land cell is coastal if an orthogonal neighbour is outside the province (open sea)
	private static boolean coastal(ProvinceMask mask, int x, int y) {
		return !mask.isLand(x - 1, y) || !mask.isLand(x + 1, y)
				|| !mask.isLand(x, y - 1) || !mask.isLand(x, y + 1);
	}

	// the generic appearance-probability pass over an otherwise-bare plot (L3171–3175):
	// each curated feature whose <iAppearance> is set and whose terrain/relief/river the
	// plot satisfies is rolled at prob appearance/10000; the last that hits is placed
	// (matching the script's overwrite). One rng draw per valid candidate, so the stream
	// stays deterministic whether or not a feature lands.
	private static Feature appearanceScatter(Terrain terrain, PlotType relief, boolean river,
			TerrainRegistry reg, Rng rng) {
		Feature chosen = null;
		for (Feature f : reg.features()) {
			if (f.appearance() <= 0 || !valid(f, terrain, relief) || (f.requiresRiver() && !river))
				continue;
			if (rng.uniform() < f.appearance() / 10000.0)
				chosen = f;
		}
		return chosen;
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
