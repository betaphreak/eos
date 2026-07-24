package com.civstudio.settlement;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The <b>village-larder subsystem</b> (city-of-hamlets V2, {@code docs/city-of-hamlets-plan.md}): the
 * per-hamlet {@link Larder} food pools that organize a city's food per <b>village</b> rather than per
 * household. Present only when {@code SimulationConfig.villageLarder} is on ({@link
 * Settlement#enableVillageLarders()}); a flag-off colony never builds one and stays byte-identical
 * (its {@link Settlement#getVillageLarders()} is {@code null}, and food keeps running through the
 * per-household necessity stock).
 * <p>
 * A larder is keyed by its hamlet's <b>seat plot identity</b> (matching {@link Settlement#hamlets()}),
 * created on first use. This V2 slice lands the pools and their lifecycle; the storeys that fill them
 * (village production), drain them (provisioned eating), and top them up (leader-funded market
 * imports) are wired incrementally on top — see the plan's V2/V3.
 */
public final class VillageLarders {

	// the owning colony, for the hamlet grouping and food sources the larders read
	@SuppressWarnings("unused") // held for the fill/drain wiring the next V2 slices add
	private final Settlement colony;

	// one larder per hamlet, keyed by its seat plot identity (IdentityHashMap: two distinct plots
	// with equal fields never share a larder)
	private final Map<Plot, Larder> larders = new IdentityHashMap<>();

	VillageLarders(Settlement colony) {
		this.colony = colony;
	}

	/**
	 * The larder for a hamlet whose seat is {@code seat}, created on first use so every hamlet the
	 * colony projects has exactly one.
	 *
	 * @param seat the hamlet's seat plot
	 * @return the hamlet's larder (never {@code null})
	 */
	Larder larderFor(Plot seat) {
		return larders.computeIfAbsent(seat, k -> new Larder());
	}

	/**
	 * The larder for a hamlet whose seat is {@code seat}, or {@code null} if none has been created yet
	 * — a non-creating lookup (a plot with no larder has none).
	 *
	 * @param seat the hamlet's seat plot
	 * @return the existing larder, or {@code null}
	 */
	Larder larderIfPresent(Plot seat) {
		return larders.get(seat);
	}

	/** How many hamlet larders exist (a test/report seam). */
	int count() {
		return larders.size();
	}
}
