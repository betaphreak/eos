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
 *            if positive, the noble builds a necessity <b>reserve</b> for its
 *            <b>own household</b> toward a target of {@code necessityReserveDays ×
 *            (household size)} units — that many days of its own consumption (a
 *            member eats one unit a day) — buying toward it "if possible" and then
 *            holding. The default {@code 30} keeps a noble from hoarding food: a
 *            noble buys necessity but never eats it, so without a cap it would
 *            accumulate without bound and starve the working population out of the
 *            necessity market. 0 means no cap (spend the whole necessity budget
 *            each step)
 */
@Builder(toBuilder = true)
public record NobleConfig(
		double dividendRate,
		double consumptionRate,
		double necessityShare,
		double necessityReserveDays) {

	/** The canonical noble parameters. */
	public static final NobleConfig DEFAULT =
			new NobleConfig(0.25, 0.05, 0.3, 30);
}
