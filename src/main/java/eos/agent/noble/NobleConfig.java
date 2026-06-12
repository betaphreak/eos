package eos.agent.noble;

import lombok.Builder;

/**
 * Behavioral parameters for a {@link Noble} (the owner of firms/banks who lives
 * off the surplus they produce). Immutable; {@link #DEFAULT} holds the canonical
 * values. Like the other agents' {@code *Config} records the owner stores it as
 * a {@code final} field and the constructor takes it as a required parameter, so
 * a heterogeneous aristocracy is possible.
 *
 * @param dividendRate
 *            fraction of each owned firm's <em>positive</em> profit the noble
 *            draws as a dividend each step (0 leaves the firm's earnings fully
 *            retained, as before nobles existed)
 * @param consumptionRate
 *            fraction of the noble's liquid wealth (checking + savings) spent on
 *            consumer goods each step; the rest is saved, so dividend income
 *            circulates back into the markets rather than being hoarded
 * @param necessityShare
 *            fraction of that consumption budget spent on necessity (the
 *            remainder on enjoyment)
 * @param necessityReserveDays
 *            if positive, the noble builds a necessity <b>reserve</b> toward a
 *            per-noble target of {@code necessityReserveDays ×
 *            (laborers/nobles)} units — so the nobles collectively hold {@code
 *            necessityReserveDays × laborers} units, i.e. that many days of the
 *            whole population's necessity consumption — buying toward it "if
 *            possible" and then holding. 0 (the default) means no reserve: the
 *            noble spends its whole necessity budget each step, as before
 */
@Builder(toBuilder = true)
public record NobleConfig(
		double dividendRate,
		double consumptionRate,
		double necessityShare,
		double necessityReserveDays) {

	/** The canonical noble parameters. */
	public static final NobleConfig DEFAULT =
			new NobleConfig(0.25, 0.05, 0.3, 0);
}
