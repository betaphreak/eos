package com.civstudio.geo.names;

import java.util.HashSet;
import java.util.Set;

/**
 * Assigns a real Earth place name to each plot of one region, position-preserving
 * and unique within a province.
 * <p>
 * A plot's absolute pixel {@code (x,y)} is normalized within the region's
 * {@link PixelBox} to {@code [0,1]²}, then projected into the mapped
 * {@link CountryGazetteer country}'s lat/lon box (north-up). The plot takes the
 * largest-population unused place in its mapped cell; if the cell is empty it
 * takes the {@link CountryGazetteer#nearestUnused nearest unused} place; if the
 * country's pool is exhausted it reuses the nearest real name with a numeric
 * suffix. Every returned name is therefore unique within the current province.
 * <p>
 * State is per-province: call {@link #beginProvince()} before each province, then
 * {@link #name(int, int)} once per plot. A single instance serves one region
 * (fixed bbox + country); the bake creates one per region. Fully deterministic —
 * no RNG — so a re-bake reproduces identical names.
 */
public final class PlaceNamer {

	private final PixelBox region;
	private final CountryGazetteer country;
	private final double latSpan, lonSpan;
	// one region-pixel's share of the country box (the plot's mapped cell size)
	private final double cellLat, cellLon;

	// per-province state: place ids and names already handed out
	private final Set<Integer> usedIds = new HashSet<>();
	private final Set<String> usedNames = new HashSet<>();

	/**
	 * @param region  the region's pixel footprint
	 * @param country the gazetteer of the Earth country the region maps to
	 */
	public PlaceNamer(PixelBox region, CountryGazetteer country) {
		this.region = region;
		this.country = country;
		this.latSpan = country.latMax() - country.latMin();
		this.lonSpan = country.lonMax() - country.lonMin();
		this.cellLat = latSpan / region.height();
		this.cellLon = lonSpan / region.width();
	}

	/** Reset the used-name/id state for a new province. */
	public void beginProvince() {
		usedIds.clear();
		usedNames.clear();
	}

	/**
	 * Name the plot at absolute raster pixel {@code (px,py)}, marking its pick used
	 * for the rest of the province.
	 *
	 * @return a name unique within the current province
	 */
	public String name(int px, int py) {
		double u = region.u(px), v = region.v(py);
		double lon = country.lonMin() + u * lonSpan;
		double lat = country.latMax() - v * latSpan; // v=0 is the north edge
		// largest-population place in the plot's mapped cell, else nearest unused
		GeoNamesPlace pick = country.largestInRect(lat - cellLat / 2, lat + cellLat / 2,
				lon - cellLon / 2, lon + cellLon / 2, usedIds::contains);
		if (pick == null)
			pick = country.nearestUnused(lat, lon, usedIds::contains);

		String base;
		if (pick != null) {
			usedIds.add(pick.id());
			base = pick.name();
		} else {
			// pool exhausted for this province — reuse the nearest real name (ignoring used)
			GeoNamesPlace anchor = country.nearestUnused(lat, lon, id -> false);
			base = anchor != null ? anchor.name() : country.country();
		}
		String name = uniquify(base);
		usedNames.add(name);
		return name;
	}

	// make a name unique within the province by appending " 2", " 3", … if taken
	private String uniquify(String base) {
		if (!usedNames.contains(base))
			return base;
		for (int n = 2;; n++) {
			String candidate = base + " " + n;
			if (!usedNames.contains(candidate))
				return candidate;
		}
	}
}
