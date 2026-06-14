package eos.agent.firm;

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
 * @param landWorkPerSlot build-units to clear one effective slot (firm-funded land)
 * @param roadWorkPerSlot build-units to lay one road slot (ruler-funded)
 * @param wallWorkPerSlot build-units to raise one wall slot before the wall
 *                        build-speed factor is applied (ruler-funded)
 */
@Builder(toBuilder = true)
public record BuilderConfig(
		double A,
		double beta,
		double scaffoldCap,
		double initWageBudget,
		double targetWageBudget,
		double landWorkPerSlot,
		double roadWorkPerSlot,
		double wallWorkPerSlot) {

	/** Canonical parameter values (placeholders pending calibration). */
	public static final BuilderConfig DEFAULT = new BuilderConfig(
			50, 0.5,   // output per unit labor (the scaffold cap binds when staffed)
			40,        // scaffold cap: at most 40 build-units per step
			200, 80,   // seed checking buffer, and a working wage budget that wins
			           // a few workers without starving the consumer firms
			20, 20, 20 // ~20 build-units per slot of land / road / wall
	);
}
