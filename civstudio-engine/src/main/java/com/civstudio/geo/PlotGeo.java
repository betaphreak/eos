package com.civstudio.geo;

/**
 * The raster-derived scalars of one plot: its {@code (x, y)} position in the province
 * silhouette and the fields read straight off the Anbennar rasters — the packed river code,
 * the heightmap elevation, and the 8-bit sea mask. Grouping them into one immutable value is
 * a deliberate refactor: a new per-plot raster attribute is now a one-line change <b>here</b>,
 * instead of shotgun surgery across {@link ProvincePlotField.ProvincePlot}, {@link
 * com.civstudio.settlement.Plot} and the persistence DTO (each of which used to grow another
 * constructor parameter). {@code ProvincePlot} and {@code Plot} both carry a {@code PlotGeo}
 * and delegate their positional/raster accessors to it. See {@code docs/plots.md}.
 *
 * @param x         raster x in the province silhouette ({@code -1} for a province-less plot)
 * @param y         raster y ({@code -1} for a province-less plot)
 * @param river     packed river code (width / flow / node — see {@link ProvinceRaster#classifyRiver})
 * @param elevation heightmap elevation, {@code 0..255} ({@code 0} where absent)
 * @param coast     8-bit sea mask (edge + corner water — see {@code docs/coastlines.md})
 */
public record PlotGeo(int x, int y, int river, int elevation, int coast) {

	/** The geography of a province-less plot (a legacy/test plot): no position, water or elevation. */
	public static final PlotGeo NONE = new PlotGeo(-1, -1, 0, 0, 0);
}
