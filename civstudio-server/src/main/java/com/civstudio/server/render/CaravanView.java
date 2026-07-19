package com.civstudio.server.render;

/**
 * A read-only projection of one {@link com.civstudio.agent.Caravan wandering band} for the
 * live feed — the hero of the Phase-A demo (six caravans marching over the world map). A
 * plain immutable record so it serializes straight to JSON and never exposes the live agent
 * graph. Assembled on the session thread by {@link Snapshots} between ticks.
 *
 * @param id        the band's stable id — what the client selects a band by (the leader name is not
 *                  unique and changes on succession); the key for {@code GET
 *                  /api/sessions/{sid}/caravan/{id}} (its composition detail)
 * @param label     a display label (the band's leader)
 * @param leader    the band leader's full name
 * @param latitude  current latitude (decimal degrees, north positive)
 * @param longitude current longitude (decimal degrees, east positive)
 * @param provinceId the band's province node ({@code -1} if off the graph)
 * @param province  the band's current province name (or a placeholder off-graph)
 * @param onGraph   whether the band sits on the province graph (so it can march)
 * @param settled   whether the band has reached a site and awaits re-founding
 * @param bandSize  the number of people following the band
 * @param larder    the band's remaining food larder (its countdown to starvation)
 * @param hoard     the band's carried money (copper)
 * @param role      the band's {@link com.civstudio.agent.CaravanRole role} name (SETTLER /
 *                  WORKER / EXPLORER / MILITARY) — what it is for, so the map can tell the
 *                  flavors apart
 * @param unitId    the imported C2C unit this band <em>embodies</em> ({@code UNIT_*}), or
 *                  {@code null} if it embodies none (docs/c2c-unit-import.md §1a)
 * @param unitName  the embodied unit's display name, or {@code null}
 * @param unitIcon  the embodied unit's icon sprite rect {@code [x,y,w,h]} into
 *                  {@code assets/units/unit-icons.webp}, or {@code null}
 * @param signatureSkill the band's role signature skill name (e.g. {@code "SURVIVAL"}), or
 *                  {@code null} for a non-marching band
 * @param leaderSkill    the band leader's level in that signature skill
 */
public record CaravanView(long id, String label, String leader, double latitude, double longitude,
		int provinceId, String province, boolean onGraph, boolean settled, int bandSize,
		double larder, double hoard, String role,
		String unitId, String unitName, int[] unitIcon, String signatureSkill, int leaderSkill) {
}
