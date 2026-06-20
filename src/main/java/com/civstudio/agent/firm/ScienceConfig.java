package com.civstudio.agent.firm;

import lombok.Builder;

/**
 * Tunable model parameters for a {@link ScienceFirm}. Immutable. Modeled on
 * {@link StrategicFirmConfig}: the firm converts the intellectual labor it hires
 * into research points by {@code A · L^beta}, but it sells nothing — its output is
 * delivered to the colony's research rather than exported — so there is no export
 * price, and the {@code wageBudget} it bids is funded by the ruler's treasury (a
 * crown-funded public good), not by sales.
 *
 * @param A          technology coefficient in the research production function
 *                   {@code A · L^beta} (research points yielded per step)
 * @param beta       sensitivity of research output to labor (the power on L);
 *                   &lt; 1 gives diminishing returns to scholarly labor
 * @param wageBudget fixed per-step wage budget the firm bids for scholarly labor,
 *                   funded each step from the ruler's treasury; held constant so the
 *                   firm wins a steady amount of labor and research advances steadily.
 *                   The same value seeds the firm's opening checking balance so it can
 *                   pay its first scholars before the first funding transfer
 */
@Builder(toBuilder = true)
public record ScienceConfig(double A, double beta, double wageBudget) {

	/** Canonical parameter values (mirrors the strategic firm's curve). */
	public static final ScienceConfig DEFAULT = new ScienceConfig(2, 0.5, 40);
}
