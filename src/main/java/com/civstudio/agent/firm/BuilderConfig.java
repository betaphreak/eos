package com.civstudio.agent.firm;

import lombok.Builder;

/**
 * Tunable model parameters for a {@link BuilderFirm}. Immutable.
 * <p>
 * The numbers in {@link #DEFAULT} are placeholders. No bundled simulation currently
 * exercises the builder (the {@code BuilderEconomy} worked example was removed once
 * universal pool-founding made every ruler-bearing colony collapse before it could
 * grow); the mechanism remains, but these would need re-validating before use.
 *
 * @param A               technology coefficient in the build curve {@code A · L^beta}
 *                        (build-units produced per step from {@code L} units of labor)
 * @param beta            sensitivity of build output to labor (the power on L)
 * @param scaffoldCap     hard ceiling on build-units the firm can deliver in one
 *                        step, however much labor it hires — the "scaffold cap",
 *                        the limit on how much building can happen at once
 * @param initWageBudget  the firm's opening checking balance, so it can pay its
 *                        first round of building labor before billing recoups it
 * @param targetWageBudget wage budget the firm bids while it has work queued (it
 *                        bids nothing — and so lays its workers off — when idle)
 * @param landWorkPerPlot build-units to open one plot when its occupant raises no
 *                        improvement — the fallback land-clearance cost; an on-plot
 *                        firm (an {@code NFirm} → a {@code FARM}) is costed instead
 *                        from its improvement's build cost plus any feature clear
 *                        cost, &times; the terrain build modifier (Phase 3, see
 *                        {@code Settlement.clearanceWork})
 */
@Builder(toBuilder = true)
public record BuilderConfig(
		double A,
		double beta,
		double scaffoldCap,
		double initWageBudget,
		double targetWageBudget,
		double landWorkPerPlot) {

	/** Canonical parameter values (placeholders pending calibration). */
	public static final BuilderConfig DEFAULT = new BuilderConfig(
			50, 0.5,   // output per unit labor (the scaffold cap binds when staffed)
			40,        // scaffold cap: at most 40 build-units per step
			200, 80,   // seed checking buffer, and a working wage budget that wins
			           // a few workers without starving the consumer firms
			20         // ~20 build-units to open one plot
	);
}
