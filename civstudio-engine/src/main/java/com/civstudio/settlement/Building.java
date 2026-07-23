package com.civstudio.settlement;

/**
 * A <b>placed building</b> standing on a {@link Plot} — a Civ4-style building instance
 * (a banking house, granary, a household's hut…), distinct from the tile
 * {@link com.civstudio.geo.Improvement} leg: an improvement enters the plot's
 * {@link Plot#yields()}; a building does not (its economic effect is deferred — see
 * {@code docs/plots.md}, <i>Buildings vs. improvements</i>).
 * <p>
 * A building is keyed by its <b>catalog id</b> — the same id the tech tree's
 * {@link com.civstudio.tech.TechEffect.Unlock} names and {@link BuildingCatalog} /
 * {@link HousingCatalog} carry (e.g. {@code "BUILDING_HOUSING_BARK_HUTS"}) — and since
 * B3 carries an <b>owner</b> (docs/build-queue-plan.md): the agent id of the household
 * (or ruler) that raised it, or {@code null} for an <b>unowned</b> building — orphaned
 * by its owner's death (the estate seam: a dead household's house is orphaned, and the
 * successor seated on the plot <b>adopts</b> it), or inherited ground from a previous
 * colony (buildings are durable — they outlive the colony on the plot).
 */
public final class Building {

	// the catalog id (the Unlock target; non-blank, immutable)
	private final String id;

	// the owning agent's id, or null when unowned (orphaned/inherited) — mutable: an
	// owner dies (orphaning it) and a successor adopts it
	private Integer ownerId;

	/**
	 * Create a placed building.
	 *
	 * @param id      the building's catalog id (non-blank)
	 * @param ownerId the owning agent's id, or {@code null} for unowned
	 */
	public Building(String id, Integer ownerId) {
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("building id must be non-blank");
		this.id = id;
		this.ownerId = ownerId;
	}

	/** The building's catalog id (the {@code Unlock} target). */
	public String id() {
		return id;
	}

	/** The owning agent's id, or {@code null} when unowned (orphaned/inherited). */
	public Integer ownerId() {
		return ownerId;
	}

	/** Whether this building is a housing rung (the {@code BUILDING_HOUSING_*} line). */
	public boolean isHousing() {
		return id.startsWith("BUILDING_HOUSING_");
	}

	/**
	 * Transfer (or clear, with {@code null}) ownership — a dying owner orphans its
	 * buildings; a successor household adopts an orphaned house on its plot.
	 *
	 * @param ownerId the new owner's agent id, or {@code null} to orphan
	 */
	public void setOwnerId(Integer ownerId) {
		this.ownerId = ownerId;
	}

	@Override
	public String toString() {
		return id + (ownerId == null ? " (unowned)" : " (owner #" + ownerId + ")");
	}
}
