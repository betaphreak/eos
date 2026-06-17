package eos.agent;

import java.util.Optional;

/**
 * The rank of an entity in the realm's social and political hierarchy — the
 * scope of what it controls, from a passive onlooker up through a household,
 * a holding, a settlement and on to a continent-spanning hegemony.
 * <p>
 * The ladder maps onto this model's existing concepts at its lower rungs:
 * <ul>
 * <li>{@link #SPECTATOR} — a passive entity that controls nothing.</li>
 * <li>{@link #HOUSEHOLD} — a single household (the player is one).</li>
 * <li>{@link #RETINUE} — a personal following, like the
 *     {@link PeasantPool peasant pool}.</li>
 * <li>{@link #HOLDING} — the firms and estates a {@link eos.agent.noble.Noble
 *     noble} owns.</li>
 * <li>{@link #VILLAGE} — a settlement, what a {@link eos.agent.ruler.Ruler
 *     ruler} leads.</li>
 * </ul>
 * The higher ranks ({@link #CITY} through {@link #HEGEMONY}) are larger polities
 * the model does not yet realise but reserves a place for.
 * <p>
 * Each rank carries a stable {@link #seq() sequence number} (its identity and
 * sort order), a {@link #label() label} (a translation key for the rank's name)
 * and the {@link #title() title} borne by whoever holds it (e.g. a holder of a
 * {@link #DUCHY} is a "Duke"). A few ranks also carry a short
 * {@link #shortCode() code}; it is {@code null} where absent.
 */
public enum Rank {

	/** A passive onlooker that controls nothing. */
	SPECTATOR(0, "TXT_RANK_SPECTATOR", "Spectator", null),

	/** A single household — the rank the player holds. */
	HOUSEHOLD(1, "TXT_RANK_HOUSEHOLD", "Master", null),

	/** A personal following, like the {@link PeasantPool peasant pool}. */
	RETINUE(2, "TXT_RANK_RETINUE", "Hero", "1"),

	/** The firms and estates a {@link eos.agent.noble.Noble noble} owns. */
	HOLDING(3, "TXT_RANK_HOLDING", "Owner", null),

	/** A settlement — what a {@link eos.agent.ruler.Ruler ruler} leads. */
	VILLAGE(4, "TXT_RANK_VILLAGE", "Leader", null),

	/** A city. */
	CITY(5, "TXT_RANK_CITY", "Mayor", null),

	/** A league of settlements. */
	LEAGUE(6, "TXT_RANK_LEAGUE", "Legate", null),

	/** A barony. */
	BARONY(7, "TXT_RANK_BARONY", "Baron", null),

	/** A faction. */
	FACTION(8, "TXT_RANK_FACTION", "Chief", null),

	/** A county. */
	COUNTY(9, "TXT_RANK_COUNTY", "Count", null),

	/** An estate. */
	ESTATE(10, "TXT_RANK_ESTATE", "Viscount", null),

	/** A duchy. */
	DUCHY(11, "TXT_RANK_DUCHY", "Duke", null),

	/** An archduchy. */
	ARCHDUCHY(12, "TXT_RANK_ARCHDUCHY", "Viceroy", null),

	/** A kingdom. */
	KINGDOM(13, "TXT_RANK_KINGDOM", "King", null),

	/** A federation of kingdoms. */
	FEDERATION(14, "TXT_RANK_FEDERATION", "High King", null),

	/** An empire. */
	EMPIRE(15, "TXT_RANK_EMPIRE", "Emperor", null),

	/** A continent-spanning hegemony — the highest rank. */
	HEGEMONY(16, "TXT_RANK_HEGEMONY", "Hegemon", null);

	// a stable sequence number giving the rank's identity and order in the
	// hierarchy. Currently equal to ordinal(), but kept as its own field so the
	// declaration order can change later without shifting each rank's number.
	private final int seq;
	private final String label;
	private final String title;
	private final String shortCode;

	Rank(int seq, String label, String title, String shortCode) {
		this.seq = seq;
		this.label = label;
		this.title = title;
		this.shortCode = shortCode;
	}

	// ranks indexed by seq, for the adjacency walk. seq == ordinal() today, so the
	// declaration order is the hierarchy order; kept as its own array so that, if a
	// seq is ever renumbered out of declaration order, only this initializer changes.
	private static final Rank[] BY_SEQ = values();

	/**
	 * The next rank up the hierarchy (one higher {@link #seq()}), or
	 * {@link Optional#empty()} at the top ({@link #HEGEMONY}). One step of a
	 * promotion up the ladder.
	 *
	 * @return the adjacent higher rank, or empty if this is the highest
	 */
	public Optional<Rank> promoted() {
		return seq + 1 < BY_SEQ.length ? Optional.of(BY_SEQ[seq + 1])
				: Optional.empty();
	}

	/**
	 * The next rank down the hierarchy (one lower {@link #seq()}), or
	 * {@link Optional#empty()} at the bottom ({@link #SPECTATOR}). One step of a
	 * demotion down the ladder.
	 *
	 * @return the adjacent lower rank, or empty if this is the lowest
	 */
	public Optional<Rank> demoted() {
		return seq > 0 ? Optional.of(BY_SEQ[seq - 1]) : Optional.empty();
	}

	/**
	 * This rank's stable sequence number — its identity and its position in the
	 * hierarchy (lower is humbler), independent of declaration order.
	 *
	 * @return the rank's sequence number
	 */
	public int seq() {
		return seq;
	}

	/**
	 * The translation key for this rank's name (e.g. {@code "TXT_RANK_DUCHY"}).
	 *
	 * @return the rank's label key
	 */
	public String label() {
		return label;
	}

	/**
	 * The title borne by whoever holds this rank (e.g. a {@link #DUCHY} holder is
	 * a "Duke").
	 *
	 * @return the holder's title
	 */
	public String title() {
		return title;
	}

	/**
	 * This rank's short code, or {@code null} if it has none.
	 *
	 * @return the short code, or {@code null}
	 */
	public String shortCode() {
		return shortCode;
	}
}
