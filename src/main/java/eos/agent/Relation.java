package eos.agent;

/**
 * The relative standing of a war's target to the aggressor, selecting which
 * {@link CasusBelli} a {@link Rank} may invoke: a strike against a peer
 * ({@link #EQUAL}), one against a lesser polity ({@link #LOWER}), or a revolt
 * against a higher authority ({@link #HIGHER}). A rank need not define one for
 * every relation — the bottom rank has no {@link #LOWER} and the top no
 * {@link #HIGHER} (see {@link Rank#casusBelli}).
 */
public enum Relation {

	/** A war against a polity of the same rank. */
	EQUAL,

	/** A war against a polity of a lower rank. */
	LOWER,

	/** A revolt against a polity of a higher rank. */
	HIGHER;
}
