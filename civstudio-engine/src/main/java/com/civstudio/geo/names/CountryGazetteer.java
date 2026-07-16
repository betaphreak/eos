package com.civstudio.geo.names;

import java.util.List;
import java.util.function.IntPredicate;

/**
 * The {@link GeoNamesPlace places} of one Earth country, held in a uniform
 * lat/lon grid for two spatial queries the {@code PlaceNamer} needs: the
 * largest-population place inside a rectangle (a plot's mapped cell), and the
 * nearest place to a point (the empty-cell fallback). Both skip places an
 * {@link IntPredicate} reports as already used, so a province builds unique
 * names by marking each pick used as it goes.
 * <p>
 * Immutable and built once per country by {@link #of(String, List)}; a country
 * with no kept places yields an {@link #isEmpty() empty} gazetteer whose queries
 * all return {@code null}.
 */
public final class CountryGazetteer {

	// feature-class tie-break priority (higher wins): populated > admin > mountain > area > water
	private static int classRank(char c) {
		return switch (c) {
			case 'P' -> 5;
			case 'A' -> 4;
			case 'T' -> 3;
			case 'L' -> 2;
			case 'H' -> 1;
			default -> 0;
		};
	}

	private final String country;
	private final GeoNamesPlace[] places;
	private final double latMin, latMax, lonMin, lonMax;
	private final double cell; // grid cell size, degrees
	private final int cols, rows;
	// CSR bucket index: places of cell k are order[cellStart[k] .. cellStart[k+1])
	private final int[] cellStart;
	private final int[] order;

	private CountryGazetteer(String country, GeoNamesPlace[] places, double latMin, double latMax,
			double lonMin, double lonMax, double cell, int cols, int rows, int[] cellStart, int[] order) {
		this.country = country;
		this.places = places;
		this.latMin = latMin;
		this.latMax = latMax;
		this.lonMin = lonMin;
		this.lonMax = lonMax;
		this.cell = cell;
		this.cols = cols;
		this.rows = rows;
		this.cellStart = cellStart;
		this.order = order;
	}

	/**
	 * Build a gazetteer from a country's places (defensively copied). The grid is
	 * sized to average a few places per cell.
	 *
	 * @param country the ISO country code (for diagnostics)
	 * @param list    the country's kept places; may be empty
	 * @return the gazetteer
	 */
	public static CountryGazetteer of(String country, List<GeoNamesPlace> list) {
		GeoNamesPlace[] places = list.toArray(GeoNamesPlace[]::new);
		if (places.length == 0)
			return new CountryGazetteer(country, places, 0, 0, 0, 0, 1, 1, 1, new int[] { 0, 0 }, new int[0]);

		double latMin = Double.POSITIVE_INFINITY, latMax = Double.NEGATIVE_INFINITY;
		double lonMin = Double.POSITIVE_INFINITY, lonMax = Double.NEGATIVE_INFINITY;
		for (GeoNamesPlace p : places) {
			latMin = Math.min(latMin, p.lat());
			latMax = Math.max(latMax, p.lat());
			lonMin = Math.min(lonMin, p.lon());
			lonMax = Math.max(lonMax, p.lon());
		}
		double latSpan = Math.max(latMax - latMin, 1e-6);
		double lonSpan = Math.max(lonMax - lonMin, 1e-6);
		// ~4 places per cell on average, clamped to a sane degree range
		double targetCells = Math.max(1.0, places.length / 4.0);
		double cell = Math.sqrt(latSpan * lonSpan / targetCells);
		cell = Math.min(5.0, Math.max(0.02, cell));
		int cols = (int) (lonSpan / cell) + 1;
		int rows = (int) (latSpan / cell) + 1;

		int nCells = cols * rows;
		int[] cellStart = new int[nCells + 1];
		int[] cellOf = new int[places.length];
		for (int i = 0; i < places.length; i++) {
			int c = colOf(places[i].lon(), lonMin, cell, cols);
			int r = rowOf(places[i].lat(), latMin, cell, rows);
			int k = r * cols + c;
			cellOf[i] = k;
			cellStart[k + 1]++;
		}
		for (int k = 0; k < nCells; k++)
			cellStart[k + 1] += cellStart[k];
		int[] order = new int[places.length];
		int[] cursor = cellStart.clone();
		for (int i = 0; i < places.length; i++)
			order[cursor[cellOf[i]]++] = i;

		return new CountryGazetteer(country, places, latMin, latMax, lonMin, lonMax, cell, cols, rows,
				cellStart, order);
	}

	/** Southernmost place latitude (0 if empty). */
	public double latMin() {
		return latMin;
	}

	/** Northernmost place latitude (0 if empty). */
	public double latMax() {
		return latMax;
	}

	/** Westernmost place longitude (0 if empty). */
	public double lonMin() {
		return lonMin;
	}

	/** Easternmost place longitude (0 if empty). */
	public double lonMax() {
		return lonMax;
	}

	private static int colOf(double lon, double lonMin, double cell, int cols) {
		return Math.min(cols - 1, Math.max(0, (int) ((lon - lonMin) / cell)));
	}

	private static int rowOf(double lat, double latMin, double cell, int rows) {
		return Math.min(rows - 1, Math.max(0, (int) ((lat - latMin) / cell)));
	}

	/** The ISO country code this gazetteer holds. */
	public String country() {
		return country;
	}

	/** Number of places. */
	public int size() {
		return places.length;
	}

	/** Whether this country contributed no kept places. */
	public boolean isEmpty() {
		return places.length == 0;
	}

	/**
	 * The highest-ranked unused place whose coordinate lies within the rectangle
	 * {@code [latLo,latHi] × [lonLo,lonHi]}. Rank is population, then feature-class
	 * priority, then id (for determinism).
	 *
	 * @param isUsed reports a place id already taken by this province
	 * @return the best place, or {@code null} if the rectangle holds none unused
	 */
	public GeoNamesPlace largestInRect(double latLo, double latHi, double lonLo, double lonHi,
			IntPredicate isUsed) {
		if (places.length == 0)
			return null;
		int cLo = colOf(lonLo, lonMin, cell, cols), cHi = colOf(lonHi, lonMin, cell, cols);
		int rLo = rowOf(latLo, latMin, cell, rows), rHi = rowOf(latHi, latMin, cell, rows);
		GeoNamesPlace best = null;
		for (int r = rLo; r <= rHi; r++)
			for (int c = cLo; c <= cHi; c++) {
				int k = r * cols + c;
				for (int j = cellStart[k]; j < cellStart[k + 1]; j++) {
					GeoNamesPlace p = places[order[j]];
					if (p.lat() < latLo || p.lat() > latHi || p.lon() < lonLo || p.lon() > lonHi)
						continue;
					if (isUsed.test(p.id()))
						continue;
					if (best == null || betterName(p, best))
						best = p;
				}
			}
		return best;
	}

	private static boolean betterName(GeoNamesPlace a, GeoNamesPlace b) {
		if (a.population() != b.population())
			return a.population() > b.population();
		int ra = classRank(a.featureClass()), rb = classRank(b.featureClass());
		if (ra != rb)
			return ra > rb;
		return a.id() < b.id();
	}

	/**
	 * The nearest unused place to {@code (lat,lon)} by planar degree distance
	 * (adequate within one country). Expanding-ring grid search.
	 *
	 * @param isUsed reports a place id already taken by this province
	 * @return the nearest unused place, or {@code null} if all are used/empty
	 */
	public GeoNamesPlace nearestUnused(double lat, double lon, IntPredicate isUsed) {
		if (places.length == 0)
			return null;
		int c0 = colOf(lon, lonMin, cell, cols), r0 = rowOf(lat, latMin, cell, rows);
		int bestIdx = -1;
		double bestD2 = Double.POSITIVE_INFINITY;
		int maxRing = cols + rows;
		for (int ring = 0; ring <= maxRing; ring++) {
			// once we have a hit, no cell in ring >= ceil(bestDist/cell) can beat it
			if (bestIdx >= 0 && (double) (ring - 1) * cell > Math.sqrt(bestD2))
				break;
			for (int r = r0 - ring; r <= r0 + ring; r++) {
				if (r < 0 || r >= rows)
					continue;
				boolean edgeRow = (r == r0 - ring || r == r0 + ring);
				int cStart = c0 - ring, cEnd = c0 + ring;
				for (int c = cStart; c <= cEnd; c++) {
					if (c < 0 || c >= cols)
						continue;
					// only the ring's border cells (interior already scanned in prior rings)
					if (!edgeRow && c != cStart && c != cEnd)
						continue;
					int k = r * cols + c;
					for (int j = cellStart[k]; j < cellStart[k + 1]; j++) {
						GeoNamesPlace p = places[order[j]];
						if (isUsed.test(p.id()))
							continue;
						double dlat = p.lat() - lat, dlon = p.lon() - lon;
						double d2 = dlat * dlat + dlon * dlon;
						if (d2 < bestD2 || (d2 == bestD2 && (bestIdx < 0 || p.id() < places[bestIdx].id()))) {
							bestD2 = d2;
							bestIdx = order[j];
						}
					}
				}
			}
		}
		return bestIdx < 0 ? null : places[bestIdx];
	}
}
