package eos.agent;

/**
 * The three registers a {@link Rank}'s holder may be styled in, each with its own
 * {@link TitleSet} (gendered titles + a description) on every rank:
 * <ul>
 * <li>{@link #ADMINISTRATIVE} — the legitimate, in-office title (e.g. a
 *     {@link Rank#CITY} mayor is a "Mayor"/"Mayoress").</li>
 * <li>{@link #MILITARY} — the illegitimate / rebel form the holder takes when it
 *     turns to force (e.g. a "Demagogue" riling the urban mob).</li>
 * <li>{@link #DIPLOMATIC} — the external-facing envoy title used in dealings with
 *     other polities (e.g. a "Magistrate").</li>
 * </ul>
 * Nothing consumes these yet; they are the typed vocabulary the future
 * diplomacy/warfare system will read.
 */
public enum TitleMode {

	/** The legitimate, in-office title. */
	ADMINISTRATIVE,

	/** The illegitimate / rebel title the holder takes when it turns to force. */
	MILITARY,

	/** The external-facing envoy title used in dealings with other polities. */
	DIPLOMATIC;
}
