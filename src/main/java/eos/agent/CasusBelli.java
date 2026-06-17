package eos.agent;

/**
 * A justification for war available to a {@link Rank}, keyed by the {@link Relation
 * relative standing} of the target. Carries the named pretext and a short
 * description of the conflict it sanctions (e.g. a {@link Rank#CITY} invokes a
 * "Commercial Monopoly" against a peer, "Urban Expansion" against a lesser polity,
 * "League Defection" against the bloc above it).
 * <p>
 * Nothing consumes these yet; they are the typed vocabulary the future
 * diplomacy/warfare system will read. A relation with no available pretext is
 * represented by the absence of an entry, not a {@code CasusBelli} (see
 * {@link Rank#casusBelli}).
 *
 * @param name
 *            the named pretext for the war
 * @param description
 *            a short description of the conflict it sanctions
 */
public record CasusBelli(String name, String description) {
}
