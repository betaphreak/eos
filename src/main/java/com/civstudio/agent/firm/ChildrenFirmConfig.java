package com.civstudio.agent.firm;

import lombok.Builder;

/**
 * Tunable parameters for the {@link ChildrenFirm} — the colony's civic school.
 * Immutable, with a {@link #DEFAULT} holding the canonical values; set per run via
 * {@code SimulationHarness.setChildrenFirmConfig}. See {@code docs/births.md}.
 *
 * @param capacity
 *            the number of places the school offers each step; when more children
 *            than this seek to attend, the oldest are enrolled and the rest wait
 * @param xpPerTick
 *            raw experience a child gains, in one randomly chosen skill, for each
 *            step it is enrolled (passion-scaled by the skill's learn curve)
 */
@Builder(toBuilder = true)
public record ChildrenFirmConfig(int capacity, double xpPerTick) {

	/** The canonical values — 20 places, 1 XP per enrolled child per step. */
	public static final ChildrenFirmConfig DEFAULT = new ChildrenFirmConfig(20, 1.0);
}
