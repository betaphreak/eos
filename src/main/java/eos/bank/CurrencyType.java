package eos.bank;

/**
 * The unit of currency a {@link Bank} denominates its accounts in. A colony may
 * run banks in different currencies — e.g. a copper bank for commoners and a
 * silver bank for nobles (see {@code BimetallicEconomy}). The three metals are
 * listed in ascending order of value (copper &lt; silver &lt; gold); {@link #GOLD}
 * is defined for future use, though no bundled simulation uses it yet.
 * <p>
 * Currency is presently a <b>label</b>: cross-currency payments move nominal
 * amounts one-for-one (there is no exchange rate). An exchange-rate mechanism
 * would build on this enum.
 */
public enum CurrencyType {

	/** Copper — the lowest-value currency, used by commoners. */
	COPPER,

	/** Silver — a higher-value currency, used by nobles. */
	SILVER,

	/** Gold — the highest-value currency (reserved for future use). */
	GOLD
}
