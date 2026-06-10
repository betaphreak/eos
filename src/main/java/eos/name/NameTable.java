package eos.name;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import eos.util.Rng;

/**
 * An immutable, weighted table of names loaded from one of the JSON resources
 * in {@code src/main/resources} (e.g. {@code /male-human.json}). The source
 * files are tiered: each tier owns the cumulative-weight band
 * {@code (prev, percent]} and lists the names sharing it, so a name in a tier
 * carries weight {@code (percent - prev) / namesInTier}. Common names sit in
 * wide low bands; rare names are bucketed together near the top.
 * <p>
 * The table flattens those tiers into a per-name weight list and draws from it
 * with {@link #pick(Rng)} (with replacement). For draws <em>without</em>
 * replacement (e.g. unique dynasty surnames) see {@link NameRegistry}.
 */
public final class NameTable {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// one entry per (unique) name; weights[i] is the weight of names[i]
	private final String[] names;
	private final double[] weights;
	private final double total;

	private NameTable(String[] names, double[] weights, double total) {
		this.names = names;
		this.weights = weights;
		this.total = total;
	}

	/**
	 * Load and flatten a name table from a classpath resource.
	 *
	 * @param resource
	 *            absolute classpath resource path, e.g. {@code /male-human.json}
	 * @return the loaded table
	 */
	public static NameTable load(String resource) {
		try (InputStream in = NameTable.class.getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException("Name resource not found: " + resource);
			List<Tier> tiers = MAPPER.readValue(in, new TypeReference<List<Tier>>() {
			});
			return fromTiers(tiers);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load name resource: " + resource, e);
		}
	}

	private static NameTable fromTiers(List<Tier> tiers) {
		List<String> names = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		double total = 0;
		for (Tier tier : tiers) {
			if (tier.names == null || tier.names.isEmpty())
				continue;
			// names unique to this tier (and not already seen in an earlier one)
			List<String> tierNames = new ArrayList<>();
			for (String name : tier.names) {
				if (name == null)
					continue;
				String trimmed = name.trim();
				if (!trimmed.isEmpty() && seen.add(trimmed))
					tierNames.add(trimmed);
			}
			if (tierNames.isEmpty())
				continue;
			int prev = (tier.prev == null) ? 0 : tier.prev;
			double band = tier.percent - prev;
			// the tiers are monotonic, so band > 0; clamp defensively so every
			// listed name stays drawable even if the data has a zero-width tier
			double weight = Math.max(band, tierNames.size()) / tierNames.size();
			for (String name : tierNames) {
				names.add(name);
				weights.add(weight);
				total += weight;
			}
		}
		double[] w = new double[weights.size()];
		for (int i = 0; i < w.length; i++)
			w[i] = weights.get(i);
		return new NameTable(names.toArray(new String[0]), w, total);
	}

	/**
	 * Draw a weighted random name, with replacement.
	 *
	 * @param rng
	 *            the random-number generator to draw from
	 * @return a name
	 */
	public String pick(Rng rng) {
		double r = rng.uniform() * total;
		for (int i = 0; i < names.length; i++) {
			r -= weights[i];
			if (r < 0)
				return names[i];
		}
		// floating-point slack: fall back to the last name
		return names[names.length - 1];
	}

	/** Number of distinct names in the table. */
	public int size() {
		return names.length;
	}

	/* package-private accessors so NameRegistry can build a consumable pool */

	String[] namesCopy() {
		return names.clone();
	}

	double[] weightsCopy() {
		return weights.clone();
	}

	double total() {
		return total;
	}

	/** Jackson binding for one tier of a name JSON file. */
	static final class Tier {
		public int percent;
		public List<String> names;
		public Integer prev; // lower bound of this tier's band; null for the first
		public Integer size; // present in the JSON, unused by the model
	}
}
