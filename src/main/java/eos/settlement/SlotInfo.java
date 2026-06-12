package eos.settlement;

/**
 * The slot geometry of a settlement at one {@code size}: the precalculated row
 * of the {@link SlotTable}, derived from the disc model where a settlement of
 * radius {@code size} has {@code total = floor(pi * size^2)} build slots. Of
 * those, a linearly-growing share are consumed by <b>roads</b>
 * ({@code road = floor((size/100) * total)} — the congestion mechanic) and a
 * circumference-scaling share by <b>walls</b>; what remains is the
 * <b>effective</b> capacity available to firms and (later) housing and other
 * buildings ({@code effective = total - road - wall}). {@code maxSpecialSites}
 * is how many out-of-band "special sites" (enormous buildings/projects not
 * subject to the normal slot limit) the size unlocks.
 * <p>
 * Values come straight from {@code /slots.json} (the exported design
 * spreadsheet) and are loaded once per {@link eos.settlement.GameSession}; the
 * table is pure geometry, independent of seed and location.
 *
 * @param size            the settlement size (disc radius) this row describes
 * @param total           total build slots, {@code floor(pi * size^2)} (1 at size 0)
 * @param road            slots consumed by roads, {@code floor((size/100) * total)}
 * @param wall            slots consumed by walls (scales with circumference)
 * @param effective       usable slots, {@code total - road - wall}
 * @param maxSpecialSites number of special sites the size unlocks
 */
public record SlotInfo(
		int size,
		int total,
		int road,
		int wall,
		int effective,
		int maxSpecialSites) {

	/**
	 * The "wall max build time" multiplier for this size, as a percentage: how
	 * much faster the <em>next</em> wall slots build, equal to the square of the
	 * wall share ({@code (100 * wall / total)^2}). High when the settlement is
	 * small (walls are quick to extend) and falling below 100% as it grows.
	 *
	 * @return the wall build-time multiplier, in percent (0 when there are no slots)
	 */
	public double wallBuildTimePercent() {
		if (total == 0)
			return 0;
		double wallPercent = 100.0 * wall / total;
		return wallPercent * wallPercent;
	}
}
