package eos.name;

/**
 * A disjoint block of dynasty surnames dealt out of the session-wide {@link
 * DynastyPool} for a single colony to draw from. The two parallel arrays are
 * the same length: {@code weights[i]} is the original loaded weight of {@code
 * names[i]}, carried along so a recycled surname re-enters the colony's pool
 * with the weight it was loaded with.
 * <p>
 * A slice is the unit of partitioning that makes name picking thread-safe:
 * because slices are pairwise disjoint, two colonies (each on its own thread)
 * can draw and recycle surnames concurrently without touching shared mutable
 * state — yet every surname stays unique across the whole session.
 *
 * @param names
 *            the surnames in this slice
 * @param weights
 *            their loaded weights (parallel to {@code names})
 */
public record DynastySlice(String[] names, double[] weights) {
}
