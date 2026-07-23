package com.civstudio.settlement;

import java.util.List;
import java.util.Map;

/**
 * One imported C2C building row of {@code /buildings.json} — the runtime view of what
 * {@code BuildingInfoExporter} baked and the studio content chain serves (see
 * {@code docs/build-queue-plan.md} B2, {@code docs/c2c-building-import.md}). The id is
 * the C2C {@code BUILDING_*} type verbatim — the same id {@link Building} instances and
 * the tech tree's {@code Unlock} tokens carry, so availability is a token-set lookup.
 * <p>
 * Cost semantics: {@code cost} is the imported C2C {@code iCost}; {@code authoredCost}
 * is the studio-authored override (hand-set content, e.g. the housing line — C2C
 * auto-grants housing so it ships no {@code iCost}). {@link #effectiveCost()} prefers
 * the authored value; a building with neither is <b>unbuildable</b> ({@link
 * #buildable()} false) — which safely neutralizes C2C's bookkeeping autobuilds (civics,
 * pests, resource markers) without special-casing them.
 *
 * @param id           the C2C building id ({@code BUILDING_*}; the {@code Unlock} target)
 * @param name         the localized display name, or {@code null}
 * @param category     the advisor category ({@code ECONOMY}, …), or {@code null}
 * @param prereqTech   the primary prerequisite tech id
 * @param obsoleteTech the tech that obsoletes it, or {@code null} (may name a
 *                     past-horizon tech — then it never fires)
 * @param cost         the imported C2C {@code iCost}, or {@code null} (autobuilds)
 * @param authoredCost the studio-authored cost override, or {@code null}
 * @param autoBuild    C2C's {@code bAutoBuild} flag, or {@code null}
 * @param kind         {@code "housing"} for the {@code BUILDING_HOUSING_*} line, else
 *                     {@code null} (= a regular building)
 * @param flavors      the C2C AI build-weight map ({@code FLAVOR_* → weight}), or
 *                     {@code null} — the queue brain's ordering signal
 * @param replacedBy   the C2C replacement chain (upgrade successors, raw ids), or
 *                     {@code null}
 */
public record BuildingInfo(
		String id,
		String name,
		String category,
		String prereqTech,
		String obsoleteTech,
		Integer cost,
		Integer authoredCost,
		Boolean autoBuild,
		String kind,
		Map<String, Integer> flavors,
		List<String> replacedBy) {

	/** Whether this row is housing-kind (the {@code BUILDING_HOUSING_*} line). */
	public boolean housing() {
		return "housing".equals(kind);
	}

	/**
	 * The cost a build project must pay: the studio-authored override when present, else
	 * the imported C2C {@code iCost}, else {@code null} (unbuildable).
	 */
	public Integer effectiveCost() {
		return authoredCost != null ? authoredCost : cost;
	}

	/** Whether this building can be constructed at all (a costless row cannot). */
	public boolean buildable() {
		return effectiveCost() != null;
	}

	/**
	 * The summed flavor weight — the queue brain's default scalar ordering (a
	 * personality-weighted dot product is a later refinement). {@code 0} when unflavored.
	 */
	public int flavorSum() {
		if (flavors == null)
			return 0;
		int sum = 0;
		for (int v : flavors.values())
			sum += v;
		return sum;
	}
}
