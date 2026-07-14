package com.civstudio.server.render;

import java.util.List;

/**
 * A read-only projection of one <b>district plot</b> for the live feed — its position in
 * the colony's district-plot map and the buildings standing on it, as bare eos-native ids
 * (the verbatim C2C {@code BUILDING_*}). The web district view (see {@code
 * docs/district-buildout.md} Phase D5) joins each id against {@code /api/buildings} for its
 * category and sprite, and derives the plot's {@link com.civstudio.settlement.DistrictType}
 * from those categories (index 0 is the village center — {@code CITY_CENTER}).
 * <p>
 * Only plots that carry at least one building are projected (sparse) — in the first cut
 * auto-build places everything at the center, so this is typically just index 0.
 *
 * @param index     the plot's position in the colony's district-plot map (0 = village center)
 * @param buildings the eos-native building ids standing on the plot
 */
public record DistrictView(int index, List<String> buildings) {
}
